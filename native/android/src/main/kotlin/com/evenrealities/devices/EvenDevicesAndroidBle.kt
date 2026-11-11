package com.evenrealities.devices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.fzfstudio.ezw_ble.ble.BleCommandLease
import com.fzfstudio.ezw_ble.ble.BleEC
import com.fzfstudio.ezw_ble.ble.BleBusinessConnectionAttempt
import com.fzfstudio.ezw_ble.ble.BleBusinessConnectionStatus
import com.fzfstudio.ezw_ble.ble.BleG2OtaEndpointAction
import com.fzfstudio.ezw_ble.ble.BleG2OtaEndpointIdentity
import com.fzfstudio.ezw_ble.ble.BleManager
import com.fzfstudio.ezw_ble.ble.BleNativeEventSink
import com.fzfstudio.ezw_ble.ble.EnterpriseAuth
import com.fzfstudio.ezw_ble.ble.models.BleConfig
import com.fzfstudio.ezw_ble.ble.models.BlePrivateService
import com.fzfstudio.ezw_ble.ble.models.BleScan
import com.fzfstudio.ezw_ble.ble.models.BleSecurityGate
import com.fzfstudio.ezw_ble.ble.models.BleSnRule
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class NativeBleEvent(val type: BleEC, val value: Any?)

/** Logical pair result; raw connectFinish is only transport readiness. */
data class G2AuthEvent(val sn: String, val generation: Long, val ready: Boolean, val failedEndpoint: String? = null)

/** Device-settings TimeSync result; timeout and firmware rejection are both unsuccessful. */
data class G2TimeSyncEvent(val endpoint: String, val generation: Long, val operation: Long, val success: Boolean)

/** Native progress observations; only G2AuthEvent.ready grants whole-pair READY. */
data class G2ConnectionEvent(val sn: String, val generation: Long, val endpoint: String,
                             val phase: String, val session: Long? = null, val attempt: Long? = null,
                             val failure: String? = null)

/** Endpoint identity found by the scanner. G2 connection needs both exact addresses. */
data class G2Endpoint(val address: String, val name: String)

private data class G2Pair(val sn: String, val left: G2Endpoint, val right: G2Endpoint,
                          val proofPayload: ByteArray) {
    fun endpoint(address: String): G2Endpoint? = listOf(left, right).firstOrNull {
        it.address.equals(address, ignoreCase = true)
    }
}

/**
 * Flutter-free host bridge to the audited Android BLE engine. A G2 pair becomes ready only
 * after each enterprise gate and its 8401/8402 AuthMgr exchange commit the exact GATT attempt.
 */
class EvenDevicesAndroidBle private constructor() {
    private val manager = BleManager.instance
    private var licenseBundle: EnterpriseLicenseBundle? = null
    private var initialized = false
    private var appContext: Context? = null
    private val callbackHandler by lazy { Handler(Looper.getMainLooper()) }
    private var g2AuthListener: ((G2AuthEvent) -> Unit)? = null
    private var timeSyncListener: ((G2TimeSyncEvent) -> Unit)? = null
    private var connectionListener: ((G2ConnectionEvent) -> Unit)? = null
    private var otaListener: ((G2OtaEvent) -> Unit)? = null
    private var evenHubListener: ((G2EvenHubEvent) -> Unit)? = null
    // GATT events arrive on the main queue while hosts may replace a pair from another thread.
    @Volatile private var pair: G2Pair? = null
    @Volatile private var pairGeneration = 0L
    private val enterpriseRetry = G2EnterpriseRetryBudget()
    private val authTimers = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "even-g2-business-auth").apply { isDaemon = true }
    }
    private val timeSync = G2TimeSyncCoordinator(
        send = { endpoint, packet, session, attempt ->
            manager.sendCmd(endpoint, packet, expectedSessionGeneration = session,
                expectedAttemptGeneration = attempt,
                expectedAttempt = BleBusinessConnectionAttempt(endpoint, session, attempt))
        },
        onResult = { endpoint, generation, operation, success ->
            callbackHandler.post {
                if (generation == pairGeneration && businessAuth.isReady(generation)) {
                    timeSyncListener?.invoke(G2TimeSyncEvent(endpoint, generation, operation, success))
                }
            }
        },
    )
    // Page timers and protocol parsing live here, not in the Flutter adapter or its host.
    private val evenHub: G2EvenHubCoordinator = G2EvenHubCoordinator(
        send = { owner, packet, commandLease ->
            check(owner.generation == pairGeneration && businessAuth.isReady(owner.generation))
            val current = businessAuth.readyAttempt(owner.endpoint, owner.generation)
            check(current?.session == owner.session && current.attempt == owner.attempt)
            check(manager.enqueueOwnedCommonCommand(
                BleBusinessConnectionAttempt(owner.endpoint, owner.session, owner.attempt), packet, commandLease))
        },
        schedule = { delay, action ->
            val task = Runnable { action() }
            callbackHandler.postDelayed(task, delay)
            val cancel: () -> Unit = { callbackHandler.removeCallbacks(task) }
            cancel
        },
        onEvent = { event ->
            callbackHandler.post {
                // Revalidate the captured microphone lease after posting; no coordinator monitor
                // is held while host code runs, and a same-page re-enable cannot revive old PCM.
                if (event.owner.generation == pairGeneration && evenHub.isCurrentAudioDelivery(event)) {
                    evenHubListener?.invoke(event)
                }
            }
        },
    )
    // Timers and BLE callbacks enter the same wrapper monitor. BLE queue completions are posted
    // so a callback running under a GATT lock never takes the wrapper lock in reverse order.
    private val ota: G2OtaCoordinator by lazy {
        G2OtaCoordinator(object : G2OtaTransport {
            override fun begin(scope: G2OtaScope): String? {
                val result = manager.beginStandaloneG2OtaTransaction(scope.tx, scope.operation, G2_CONFIG, scope.sn, scope.legs)
                return (result["instanceId"] as? String)?.takeIf { result["status"] == "accepted" }
            }

            override fun bind(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity, park: Boolean): Boolean {
                val action = if (park) BleG2OtaEndpointAction.PARK else BleG2OtaEndpointAction.BIND
                return manager.updateG2OtaEndpoint(scope.tx, scope.operation, instance, leg.uuid, action,
                    leg.sessionGeneration, leg.attemptGeneration)["status"] == "accepted"
            }

            override fun recover(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity): Boolean {
                if (pair?.sn != scope.sn || pairGeneration != scope.generation) return false
                val proof = licenseBundle?.findProof(scope.sn) ?: return false
                if (manager.updateG2OtaEndpoint(scope.tx, scope.operation, instance, leg.uuid,
                        BleG2OtaEndpointAction.RECOVER, leg.sessionGeneration, leg.attemptGeneration)["status"] != "accepted" ||
                    !businessAuth.reopenEndpoint(leg.uuid, scope.generation, leg.sessionGeneration, leg.attemptGeneration)) return false
                // This foreground-only SDK recovers the single active leg through the normal 8403 gate.
                EnterpriseAuth.set(leg.uuid, EnterpriseAuthV1Encoder.encode(proof), scope.generation)
                manager.connect(G2_CONFIG, leg.uuid, leg.name, scope.sn, afterUpgrade = true)
                return true
            }

            override fun maxWrite(leg: BleG2OtaEndpointIdentity) = manager.g2OtaWriteLimit(leg)

            override fun write(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity,
                               packets: List<ByteArray>, completion: (String?) -> Unit) {
                manager.sendOtaPacketBatch(leg.uuid, packets, expectedSessionGeneration = leg.sessionGeneration,
                    expectedAttemptGeneration = leg.attemptGeneration, otaTransactionId = scope.tx,
                    otaGeneration = scope.operation, otaInstanceId = instance) { error ->
                    callbackHandler.post { synchronized(this@EvenDevicesAndroidBle) { completion(error?.code) } }
                }
            }

            override fun finish(scope: G2OtaScope, instance: String, reason: String): Boolean {
                val result = manager.finishG2OtaTransaction(scope.tx, scope.operation, instance, reason,
                    G2_CONFIG, scope.sn, scope.legs)
                if (scope.generation == pairGeneration) {
                    // PARK / finish retire old GATTs. No ordinary command may reuse the logical READY.
                    businessAuth.cancel()
                    evenHub.cancel()
                    enterpriseRetry.cancel()
                    scope.legs.forEach { connectionProgress(it.uuid, "disconnected", it.sessionGeneration, it.attemptGeneration) }
                }
                return result["status"] in setOf("committed", "alreadyCommitted")
            }

            override fun reconnect(scope: G2OtaScope) {
                val saved = pair?.takeIf { it.sn == scope.sn && pairGeneration == scope.generation } ?: error("notReady")
                connectPair(saved.sn, saved.left, saved.right, forVerification = true)
            }

            override fun readVersion(leg: BleG2OtaEndpointIdentity, packet: ByteArray): () -> Unit {
                val token = BleBusinessConnectionAttempt(leg.uuid, leg.sessionGeneration, leg.attemptGeneration)
                val lease = BleCommandLease()
                check(manager.enqueueOwnedCommonCommand(token, packet, lease))
                return { lease.invalidate() }
            }
        }, schedule = { delay, action ->
            val task = Runnable { synchronized(this@EvenDevicesAndroidBle) { action() } }
            callbackHandler.postDelayed(task, delay)
            val cancel: () -> Unit = { callbackHandler.removeCallbacks(task) }
            cancel
        }, now = SystemClock::elapsedRealtime, emit = { event ->
            val listener = otaListener
            callbackHandler.post { listener?.invoke(event) }
        })
    }

    private val businessAuth: G2BusinessAuthCoordinator = G2BusinessAuthCoordinator(
        send = { endpoint, packet, session, attempt ->
            val token = BleBusinessConnectionAttempt(endpoint, session, attempt)
            if (manager.prepareBusinessConnection(token) != BleBusinessConnectionStatus.ACCEPTED) {
                false
            } else {
                // This is the admitted GATT: submission failure must immediately retire it.
                val submitted = manager.sendBusinessAuthCommand(token, packet) { success ->
                    onAuthWriteCompleted(endpoint, session, attempt, success)
                }
                check(submitted) { "8401 AUTH write could not be submitted" }
                true
            }
        },
        commitEndpoint = { endpoint, session, attempt ->
            val committed = manager.commitBusinessConnection(BleBusinessConnectionAttempt(endpoint, session, attempt)) ==
                BleBusinessConnectionStatus.ACCEPTED
            if (committed) connectionProgress(endpoint, "ready", session, attempt)
            committed
        },
        onReady = { sn, generation ->
            // Defer host callbacks until the coordinator releases its monitor. Host code may
            // synchronously connect or reset, which otherwise reverses wrapper/owner lock order.
            callbackHandler.post {
                val readyInstalled = synchronized(this@EvenDevicesAndroidBle) {
                    if (generation != pairGeneration || !businessAuth.isReady(generation)) return@synchronized false
                    val current = pair ?: return@synchronized false
                    val left = businessAuth.readyAttempt(current.left.address, generation) ?: return@synchronized false
                    val right = businessAuth.readyAttempt(current.right.address, generation) ?: return@synchronized false
                    // Re-authentication during a transfer cannot grant ordinary whole-pair READY.
                    if (ota.transferring) return@synchronized false
                    // Menu startup is a connection event and can precede the first CREATE.
                    evenHub.onReady(listOf(
                        G2EvenHubOwner(current.sn, current.left.address, generation, left.session, left.attempt),
                        G2EvenHubOwner(current.sn, current.right.address, generation, right.session, right.attempt)))
                    ota.onReady(sn, generation, listOf(
                        BleG2OtaEndpointIdentity(current.left.address, current.left.name, left.session, left.attempt),
                        BleG2OtaEndpointIdentity(current.right.address, current.right.name, right.session, right.attempt)))
                    true
                }
                if (readyInstalled) {
                    Log.i("EvenG2Auth", "pair READY sn=$sn generation=$generation")
                    g2AuthListener?.invoke(G2AuthEvent(sn, generation, true))
                }
            }
        },
        onFailed = { sn, generation, endpoint ->
            callbackHandler.post {
                val notify = synchronized(this@EvenDevicesAndroidBle) {
                    if (generation != pairGeneration) false else {
                        // Cleanup precedes host callbacks; a reentrant connectG2 must retain
                        // its replacement owner even with the same endpoint addresses.
                        ota.onConnectionFailed()
                        pair?.let { old ->
                            enterpriseRetry.cancel()
                            timeSync.cancel()
                            evenHub.cancel()
                            runCatching { manager.disconnect(old.left.address) }
                            runCatching { manager.disconnect(old.right.address) }
                            EnterpriseAuth.set(old.left.address, null)
                            EnterpriseAuth.set(old.right.address, null)
                        }
                        pair = null
                        true
                    }
                }
                if (notify) {
                    Log.i("EvenG2Auth", "pair failed sn=$sn generation=$generation endpoint=$endpoint")
                    g2AuthListener?.invoke(G2AuthEvent(sn, generation, false, endpoint))
                }
            }
        },
    )

    /** Register events before starting scan/connection so no early result is lost. */
    @Synchronized
    fun initialize(
        context: Context,
        licenses: EnterpriseLicenseBundle,
        onEvent: (NativeBleEvent) -> Unit,
        onG2AuthEvent: (G2AuthEvent) -> Unit = {},
        onG2TimeSyncEvent: (G2TimeSyncEvent) -> Unit = {},
        onG2ConnectionEvent: (G2ConnectionEvent) -> Unit = {},
        onEvenHubEvent: (G2EvenHubEvent) -> Unit = {},
    ) = initialize(context, licenses, onEvent, onG2AuthEvent, onG2TimeSyncEvent, onG2ConnectionEvent, onEvenHubEvent, {})


    /** This overload adds native OTA events while preserving the original Kotlin/JVM initialize ABI. */
    @Synchronized
    fun initialize(
        context: Context,
        licenses: EnterpriseLicenseBundle,
        onEvent: (NativeBleEvent) -> Unit,
        onG2AuthEvent: (G2AuthEvent) -> Unit = {},
        onG2TimeSyncEvent: (G2TimeSyncEvent) -> Unit = {},
        onG2ConnectionEvent: (G2ConnectionEvent) -> Unit = {},
        onEvenHubEvent: (G2EvenHubEvent) -> Unit = {},
        onOtaEvent: (G2OtaEvent) -> Unit,
    ) {
        require(!initialized) { "BLE engine already initialized in this process" }
        g2AuthListener = onG2AuthEvent
        timeSyncListener = onG2TimeSyncEvent
        connectionListener = onG2ConnectionEvent
        otaListener = onOtaEvent
        evenHubListener = onEvenHubEvent
        BleEC.entries.forEach { type -> type.setListener(BleNativeEventSink { value ->
            handleBusinessAuthEvent(type, value)
            // Raw microphone bytes remain private to the native page lease/decoder. Public
            // observers receive only admitted processed audioEvent frames, including native hosts.
            if (type != BleEC.RECEIVE_DATA || (value as? Map<*, *>)?.get("psType") != 2) {
                onEvent(NativeBleEvent(type, value))
            }
        }) }
        try {
            manager.init(context.applicationContext)
            // The standalone SDK advertises and admits only enterprise G2 endpoints.
            manager.initConfigs(listOf(enterpriseG2Config()))
            appContext = context.applicationContext
            licenseBundle = licenses
            initialized = true
        } catch (error: Exception) {
            BleEC.clearListeners()
            throw error
        }
    }

    @Synchronized
    fun updateLicenses(licenses: EnterpriseLicenseBundle) {
        check(initialized)
        licenseBundle = licenses
    }

    /** Raw scan events include candidate identities; they do not imply a connected device. */
    fun scan(): Map<String, Any> {
        check(initialized)
        requireBluetoothPermission(scan = true)
        return manager.startScan()
    }

    fun stopScan() {
        check(initialized)
        requireBluetoothPermission(scan = true)
        manager.stopScan()
    }

    /** Install the same proof on both endpoints before any GATT attempt begins. */
    @Synchronized
    fun connectG2(sn: String, left: G2Endpoint, right: G2Endpoint) {
        connectPair(sn, left, right, forVerification = false)
    }

    /** Only the OTA coordinator can replace the pre-reboot pair while preserving its version-read owner. */
    private fun connectPair(sn: String, left: G2Endpoint, right: G2Endpoint, forVerification: Boolean) {
        check(initialized)
        requireBluetoothPermission(scan = false)
        require(left.address.isNotBlank() && right.address.isNotBlank() &&
            !left.address.equals(right.address, ignoreCase = true))
        val leftEndpoint = left.copy(address = left.address.uppercase(Locale.ROOT))
        val rightEndpoint = right.copy(address = right.address.uppercase(Locale.ROOT))
        val proof = licenseBundle?.findProof(sn)
            ?: throw IllegalArgumentException("No enterprise proof for G2 SN")
        val payload = EnterpriseAuthV1Encoder.encode(proof)
        if (!forVerification) ota.cancelCurrent()
        // Replacing a pair first invalidates its callback owner, then tears down its GATTs.
        businessAuth.cancel()
        timeSync.cancel()
        evenHub.cancel("replaced")
        enterpriseRetry.cancel()
        pair?.let { old ->
            runCatching { manager.disconnect(old.left.address) }
            runCatching { manager.disconnect(old.right.address) }
            EnterpriseAuth.set(old.left.address, null)
            EnterpriseAuth.set(old.right.address, null)
        }
        pairGeneration = businessAuth.beginPair(sn, leftEndpoint.address, rightEndpoint.address)
        enterpriseRetry.begin(pairGeneration, leftEndpoint.address, rightEndpoint.address)
        pair = G2Pair(sn, leftEndpoint, rightEndpoint, payload.copyOf())
        connectionProgress(leftEndpoint.address, "connecting")
        connectionProgress(rightEndpoint.address, "connecting")
        EnterpriseAuth.set(leftEndpoint.address, payload, pairGeneration)
        EnterpriseAuth.set(rightEndpoint.address, payload, pairGeneration)
        try {
            manager.connect(G2_CONFIG, leftEndpoint.address, leftEndpoint.name, sn)
            manager.connect(G2_CONFIG, rightEndpoint.address, rightEndpoint.name, sn)
        } catch (error: Exception) {
            // The first connect can already own a live GATT; fail the pair together.
            runCatching { manager.disconnect(leftEndpoint.address) }
            runCatching { manager.disconnect(rightEndpoint.address) }
            EnterpriseAuth.set(leftEndpoint.address, null)
            EnterpriseAuth.set(rightEndpoint.address, null)
            businessAuth.cancel()
            enterpriseRetry.cancel()
            pair = null
            throw error
        }
        // The second endpoint may never emit connectFinish; bound the whole pair as well.
        val expectedGeneration = pairGeneration
        authTimers.schedule({ businessAuth.onPairTimeout(expectedGeneration) }, 25, TimeUnit.SECONDS)
    }

    /**
     * Send the Dart-equivalent G2 TimeSync command on the READY right leg.
     * `syncBoth` is set in the protocol header, so firmware mirrors it to its peer.
     */
    @Synchronized
    fun syncG2Time(endpointAddress: String, epochSeconds: Long = System.currentTimeMillis() / 1000,
                   offsetMinutes: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000): Long {
        check(initialized)
        requireBluetoothPermission(scan = false)
        if (ota.busy || evenHub.hasPage()) throw G2EvenHubException("busy")
        val current = pair ?: error("No G2 pair")
        check(businessAuth.isReady(pairGeneration)) { "G2 pair is not READY" }
        val rightAddress = requireG2TimeSyncRightEndpoint(endpointAddress, current.right.address)
        val endpoint = current.right.copy(address = rightAddress)
        val owner = businessAuth.readyAttempt(endpoint.address, pairGeneration)
            ?: error("G2 endpoint has no authenticated GATT attempt")
        val operation = timeSync.begin(endpoint.address, pairGeneration, owner.session, owner.attempt,
            epochSeconds, offsetMinutes)
        authTimers.schedule({ timeSync.onTimeout(operation) }, 1, TimeUnit.SECONDS)
        return operation
    }

    /** Open one native-owned text page only after both enterprise and business gates are READY. */
    @Synchronized
    fun openEvenHubTextPage(text: String): Long {
        check(initialized)
        G2EvenHubCodec.validateText(text)
        requireBluetoothPermission(scan = false)
        if (ota.busy) throw G2EvenHubException("busy")
        val current = pair ?: throw G2EvenHubException("notReady")
        if (!businessAuth.isReady(pairGeneration)) throw G2EvenHubException("notReady")
        val attempt = businessAuth.readyAttempt(current.right.address, pairGeneration)
            ?: throw G2EvenHubException("notReady")
        val leftAttempt = businessAuth.readyAttempt(current.left.address, pairGeneration)
            ?: throw G2EvenHubException("notReady")
        val inputOwners = listOf(
            G2EvenHubOwner(current.sn, current.left.address, pairGeneration, leftAttempt.session, leftAttempt.attempt),
            G2EvenHubOwner(current.sn, current.right.address, pairGeneration, attempt.session, attempt.attempt))
        return evenHub.open(G2EvenHubOwner(current.sn, current.right.address, pairGeneration,
            attempt.session, attempt.attempt), text, inputOwners)
    }

    /** The coordinator stops future heartbeats immediately and serializes shutdown after a pending ACK. */
    @Synchronized
    fun closeEvenHub() {
        check(initialized)
        requireBluetoothPermission(scan = false)
        evenHub.close()
    }

    /** Logical command admission and all wire/image/ACK work belong to the native page owner. */
    @Synchronized
    fun executeEvenHubCommand(command: String, parameters: Map<String, Any?>,
                              completion: (Result<Map<String, Any>>) -> Unit): Long {
        check(initialized)
        requireBluetoothPermission(scan = false)
        if (ota.busy) throw G2EvenHubException("busy")
        val current = pair ?: throw G2EvenHubException("notReady")
        if (!businessAuth.isReady(pairGeneration)) throw G2EvenHubException("notReady")
        val attempt = businessAuth.readyAttempt(current.right.address, pairGeneration)
            ?: throw G2EvenHubException("notReady")
        val leftAttempt = businessAuth.readyAttempt(current.left.address, pairGeneration)
            ?: throw G2EvenHubException("notReady")
        val inputOwners = listOf(
            G2EvenHubOwner(current.sn, current.left.address, pairGeneration, leftAttempt.session, leftAttempt.attempt),
            G2EvenHubOwner(current.sn, current.right.address, pairGeneration, attempt.session, attempt.attempt))
        // Missing microphone firmware capability does not block ordinary authorized business.
        if (command == "audioControl" && parameters["enabled"] == true && inputOwners.none {
                manager.g2AudioReady(it.endpoint, it.session, it.attempt) }) {
            throw G2EvenHubException("audioUnavailable")
        }
        return evenHub.execute(G2EvenHubOwner(current.sn, current.right.address, pairGeneration,
            attempt.session, attempt.attempt), command, G2EvenHubCodec.logicalMap(parameters), inputOwners) { outcome ->
            // Never invoke host code while holding the page monitor or a native BLE callback lock.
            callbackHandler.post { completion(outcome) }
        }
    }

    /** Freeze both enterprise-authenticated GATT identities before taking the firmware write gate. */
    @Synchronized
    fun startOta(firmware: ByteArray, targetVersion: String): Long {
        check(initialized) { "notReady" }
        requireBluetoothPermission(scan = false)
        check(!ota.busy && !evenHub.hasOngoingWork() && !timeSync.isPending()) { "busy" }
        val current = pair ?: error("notReady")
        check(businessAuth.isReady(pairGeneration)) { "notReady" }
        val legs = listOf(current.left, current.right).map { endpoint ->
            val token = businessAuth.readyAttempt(endpoint.address, pairGeneration) ?: error("notReady")
            BleG2OtaEndpointIdentity(endpoint.address, endpoint.name, token.session, token.attempt)
        }
        return ota.start(current.sn, pairGeneration, legs, firmware, targetVersion)
    }

    /** A cancel from a previous operation cannot retire the current native transaction. */
    @Synchronized
    fun cancelOta(operationId: Long) { ota.cancel(operationId) }

    @Synchronized
    fun disconnectG2(left: G2Endpoint, right: G2Endpoint) {
        check(initialized)
        ota.cancelCurrent()
        businessAuth.cancel()
        timeSync.cancel()
        evenHub.cancel()
        enterpriseRetry.cancel()
        pair = null
        // connectG2 canonicalizes Android addresses; teardown must use those same keys.
        val leftId = left.address.uppercase(Locale.ROOT)
        val rightId = right.address.uppercase(Locale.ROOT)
        manager.disconnect(leftId)
        manager.disconnect(rightId)
        EnterpriseAuth.set(leftId, null)
        EnterpriseAuth.set(rightId, null)
    }

    /** Cancel BLE ownership and credentials before releasing app callback references. */
    @Synchronized
    fun reset() {
        if (!initialized) return
        ota.cancelCurrent()
        businessAuth.cancel()
        timeSync.cancel()
        evenHub.cancel("reset")
        enterpriseRetry.cancel()
        pair = null
        manager.reset()
        EnterpriseAuth.clear()
        BleEC.clearListeners()
        licenseBundle = null
        appContext = null
        g2AuthListener = null
        timeSyncListener = null
        connectionListener = null
        evenHubListener = null
        otaListener = null
        initialized = false
    }

    /** Host owns runtime permission prompts; reject entry before partial BLE side effects. */
    private fun requireBluetoothPermission(scan: Boolean) {
        val context = appContext ?: error("BLE engine is not initialized")
        if (Build.VERSION.SDK_INT >= 31) {
            val permission = if (scan) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.BLUETOOTH_CONNECT
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Missing runtime permission: $permission")
            }
        } else if (scan && Build.VERSION.SDK_INT >= 23 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Missing runtime permission: ACCESS_FINE_LOCATION")
        }
    }

    /** Translate native event data into exact-attempt AuthMgr input; malformed events are ignored. */
    private fun handleBusinessAuthEvent(type: BleEC, value: Any?) {
        when (type) {
            BleEC.ENTERPRISE_AUTHORIZATION -> handleEnterpriseResult(value)
            BleEC.CONNECT_STATUS -> {
                val state = runCatching { JSONObject(value as String) }.getOrNull() ?: return
                val endpoint = state.optString("uuid")
                val session = state.optLong("sessionGeneration")
                val attempt = state.optLong("attemptGeneration")
                if (state.optString("connectState") == "connectFinish") {
                    val token = attemptOf(session, attempt) ?: return
                    if (!enterpriseRetry.mayUseBusinessChannel(pairGeneration, endpoint, token)) return
                    if (businessAuth.onTransportConnected(endpoint, session, attempt)) {
                        connectionProgress(endpoint, "businessAuthenticating", session, attempt)
                        val generation = businessAuth.generationFor(endpoint, session, attempt) ?: return
                        authTimers.schedule({ businessAuth.onTimeout(endpoint, generation, session, attempt) },
                            3, TimeUnit.SECONDS)
                    }
                } else if (state.optString("connectState") in TERMINAL_STATES) {
                    // Preserve notification order when PARK emits a terminal while finishing leg one.
                    val generation = pairGeneration
                    callbackHandler.post {
                        synchronized(this@EvenDevicesAndroidBle) {
                            if (generation != pairGeneration || ota.onDisconnected(endpoint, session, attempt)) return@synchronized
                            if (businessAuth.generationFor(endpoint, session, attempt) == generation) {
                                evenHub.onDisconnected(endpoint, session, attempt)
                                connectionProgress(endpoint, "disconnected", session, attempt)
                            }
                            val token = attemptOf(session, attempt)
                            if (token != null && enterpriseRetry.onTerminal(generation, endpoint, token) ==
                                EnterpriseRetryAction.RECONNECT) {
                                scheduleEnterpriseRetry(generation, endpoint)
                            } else {
                                businessAuth.onDisconnected(endpoint, session, attempt)
                            }
                        }
                    }
                }
            }
            BleEC.RECEIVE_DATA -> {
                val data = value as? Map<*, *> ?: return
                if (data["isSuccess"] != true || data["psType"] !in setOf(0, 1, 2)) return
                val endpoint = data["uuid"] as? String ?: return
                val session = (data["sessionGeneration"] as? Number)?.toLong() ?: return
                val attempt = (data["attemptGeneration"] as? Number)?.toLong() ?: return
                val bytes = runCatching { Base64.decode(data["data"] as String, Base64.DEFAULT) }
                    .getOrNull() ?: return
                val actual = data["receiveIdentity"] as? Map<*, *>
                val exactPhysicalReceive = actual?.get("uuid") == endpoint &&
                    (actual["sessionGeneration"] as? Number)?.toLong() == session &&
                    (actual["attemptGeneration"] as? Number)?.toLong() == attempt
                if (data["psType"] == 2) {
                    if (!exactPhysicalReceive) return
                    // Capture without taking the page monitor while GATT may hold manager's lock.
                    val admission = evenHub.captureAudioAdmission(endpoint, session, attempt) ?: return
                    // Never feed STREAM bytes into AUTH, OTA or the protobuf assembler.
                    callbackHandler.post {
                        // Recheck actual CCCD and physical GATT after the asynchronous handoff.
                        if (manager.g2AudioReady(endpoint, session, attempt)) {
                            evenHub.onAudioReceiveOwned(admission, bytes)
                        }
                    }
                    return
                }
                if (data["psType"] == 1) {
                    if (!exactPhysicalReceive) return
                    val tx = data["otaTransactionId"] as? String ?: return
                    val op = (data["otaGeneration"] as? Number)?.toLong() ?: return
                    val instance = data["otaInstanceId"] as? String ?: return
                    callbackHandler.post { synchronized(this) { ota.onFrame(endpoint, session, attempt, tx, op, instance, bytes) } }
                    return
                }
                if (exactPhysicalReceive && bytes.getOrNull(6)?.toInt() == 9) callbackHandler.post {
                    synchronized(this) {
                        if (businessAuth.isReady(pairGeneration)) ota.onVersion(endpoint, session, attempt, bytes)
                    }
                }
                // Device-numbered AUTH pushes can only be attributed by the physical 8402 callback.
                if (exactPhysicalReceive) businessAuth.receiveSignal(endpoint, session, attempt, bytes)?.let {
                    traceAuth(endpoint, session, attempt, "8402 $it")
                }
                val generation = pairGeneration
                if (bytes.getOrNull(6)?.toInt() == 0x80.toByte().toInt() && businessAuth.readyAttempt(endpoint, generation)?.let {
                        it.session == session && it.attempt == attempt } == true) {
                    callbackHandler.post {
                        synchronized(this) { ota.onEndpointReady(endpoint, generation, session, attempt) }
                    }
                }
                timeSync.onReceive(endpoint, session, attempt, bytes)
                // BLE can invoke listeners while holding its manager monitor. Defer page work
                // so a heartbeat write never reverses the manager/page lock order.
                callbackHandler.post { evenHub.onReceive(endpoint, session, attempt, bytes) }
            }
            else -> Unit
        }
    }

    /** Only the firmware result event can grant the one retry; all other rejection is terminal. */
    private fun handleEnterpriseResult(value: Any?) {
        val event = value as? Map<*, *> ?: return
        val current = pair ?: return
        if (event["sn"] != current.sn) return
        val endpoint = event["uuid"] as? String ?: return
        if (current.endpoint(endpoint) == null) return
        val token = attemptOf((event["sessionGeneration"] as? Number)?.toLong() ?: 0,
            (event["attemptGeneration"] as? Number)?.toLong() ?: 0) ?: return
        val result = (event["result"] as? Number)?.toInt() ?: return
        val reason = event["reason"] as? String ?: return
        val generation = (event["pairGeneration"] as? Number)?.toLong() ?: return
        if (generation != pairGeneration) return
        traceAuth(endpoint, token.session, token.attempt, "8403 result=$result")
        if (result == 0) connectionProgress(endpoint, "enterpriseAuthorized", token.session, token.attempt)
        if (enterpriseRetry.onResult(generation, endpoint, token, result, reason) == EnterpriseRetryAction.FAIL) {
            businessAuth.failEnterpriseEndpoint(endpoint, generation)
        }
    }

    private fun attemptOf(session: Long, attempt: Long): EnterpriseAttempt? =
        if (session > 0 && attempt > 0) EnterpriseAttempt(session, attempt) else null

    /** The manager dispatches receipts asynchronously after releasing the GATT queue lock. */
    private fun onAuthWriteCompleted(endpoint: String, session: Long, attempt: Long, success: Boolean) {
        if (businessAuth.generationFor(endpoint, session, attempt) != pairGeneration) return
        traceAuth(endpoint, session, attempt, "8401 writeResponse=$success")
        businessAuth.onWriteCompleted(endpoint, session, attempt, success)
    }

    /** Record protocol milestones without including the signed proof or the business payload. */
    private fun traceAuth(endpoint: String, session: Long, attempt: Long, milestone: String) {
        val owner = pair ?: return
        val side = when (endpoint) {
            owner.left.address -> "left"
            owner.right.address -> "right"
            else -> return
        }
        Log.i("EvenG2Auth", "$milestone sn=${owner.sn} side=$side generation=$pairGeneration session=$session attempt=$attempt")
    }

    /** Capture identity before posting. A late intermediate phase cannot downgrade an already READY pair. */
    private fun connectionProgress(endpoint: String, phase: String, session: Long? = null, attempt: Long? = null) {
        val current = pair ?: return
        if (current.endpoint(endpoint) == null) return
        val event = G2ConnectionEvent(current.sn, pairGeneration, endpoint, phase, session, attempt)
        callbackHandler.post {
            if (event.generation != pairGeneration) return@post
            if (phase == "ready") {
                val owner = businessAuth.readyAttempt(endpoint, event.generation) ?: return@post
                if (owner.session != session || owner.attempt != attempt) return@post
                traceAuth(endpoint, owner.session, owner.attempt, "endpoint READY")
            } else if (phase != "disconnected" && businessAuth.isReady(event.generation)) return@post
            connectionListener?.invoke(event)
        }
    }

    /** Native terminal is posted after teardown; the new connect must create a fresh attempt. */
    private fun scheduleEnterpriseRetry(generation: Long, endpointId: String) {
        callbackHandler.post {
            synchronized(this@EvenDevicesAndroidBle) {
                val current = pair?.takeIf { pairGeneration == generation } ?: return@synchronized
                val endpoint = current.endpoint(endpointId) ?: return@synchronized
                val epoch = businessAuth.extendPairTimeout(generation) ?: return@synchronized
                authTimers.schedule({ businessAuth.onPairTimeout(generation, epoch) }, 25, TimeUnit.SECONDS)
                // A recovery or reboot opens a new physical connection. A removed SN cannot use
                // the previous OTA proof, including on the enterprise gate's one permitted retry.
                val proof = if (ota.busy) {
                    val currentProof = licenseBundle?.findProof(current.sn)
                    if (currentProof == null) {
                        businessAuth.failEnterpriseEndpoint(endpointId, generation)
                        return@synchronized
                    }
                    EnterpriseAuthV1Encoder.encode(currentProof)
                } else current.proofPayload
                EnterpriseAuth.set(endpoint.address, proof, generation)
                runCatching { manager.connect(G2_CONFIG, endpoint.address, endpoint.name, current.sn) }
                    .onFailure { businessAuth.failEnterpriseEndpoint(endpoint.address, generation) }
            }
        }
    }

    companion object {
        const val G2_CONFIG = "G2"
        private val TERMINAL_STATES = setOf("disconnectByUser", "disconnectFromSys", "boundFail",
            "serviceFail", "charsFail", "timeout", "bleError", "systemError",
            "securityRecoveryExhausted")
        val instance = EvenDevicesAndroidBle()

        private fun enterpriseG2Config() = BleConfig(
            name = G2_CONFIG,
            scan = BleScan(
                listOf("Even G2"),
                BleSnRule(21, 7, "", listOf("S20", "S21", "S22", "S28", "S29", "P10", "P11")),
                2,
            ),
            privateServices = listOf(
                BlePrivateService("00002760-08C2-11E1-9073-0E8AC72E8450",
                    "00002760-08C2-11E1-9073-0E8AC72E8401", "00002760-08C2-11E1-9073-0E8AC72E8402"),
                BlePrivateService.standaloneG2Ota(),
                BlePrivateService.standaloneG2Audio(),
                BlePrivateService("00002760-08C2-11E1-9073-0E8AC72E7450",
                    "00002760-08C2-11E1-9073-0E8AC72E7401", "00002760-08C2-11E1-9073-0E8AC72E7402", 3),
            ),
            securityGate = BleSecurityGate("00002760-08C2-11E1-9073-0E8AC72E8450",
                "00002760-08C2-11E1-9073-0E8AC72E8403"),
            initiateBinding = true,
            connectTimeout = 20_000.0,
            upgradeSwapTime = 60_000.0,
            mtu = 247,
            autoReconnect = false, // Require native business AUTH owner before enabling recovery.
            androidHighReliabilityMode = true,
        )

    }
}
