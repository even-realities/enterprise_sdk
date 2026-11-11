package com.evenrealities.enterprise.example

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.evenrealities.devices.EnterpriseLicenseBundle
import com.evenrealities.devices.EvenDevicesAndroidBle
import com.evenrealities.devices.G2Endpoint
import com.evenrealities.devices.NativeBleEvent

/** A pure Android host: UI owns selection; native alone owns proof lookup and BLE sessions. */
class MainActivity : Activity() {
    private val sdk = EvenDevicesAndroidBle.instance
    private val main = Handler(Looper.getMainLooper())
    // Scan observations never grant authorization or select a proof.
    private val candidates = linkedMapOf<String, MutableMap<String, G2Endpoint>>()
    private val logLines = ArrayDeque<String>()
    private lateinit var selection: EditText
    private lateinit var discovery: TextView
    private lateinit var connection: TextView
    private lateinit var logs: TextView
    // Activity teardown fences queued callbacks before resetting the native owner.
    private var initialized = false
    private var destroyed = false
    // UI attribution follows the selected pair and the latest native pair generation.
    private var selectedSn: String? = null
    private var selectedLeft: G2Endpoint? = null
    private var selectedRight: G2Endpoint? = null
    private var ready = false
    private var pairGeneration = 0L
    // Count processed PCM without retaining microphone payloads in the UI.
    private var pcmBytes = 0L
    private var pcmEvents = 0

    /** Construct independent scan, connection and log views without another BLE state machine. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        content.addView(label("Enterprise native SDK · G2"))
        content.addView(button("Initialize and scan") { requestScan() })
        content.addView(button("Stop scan") { requireInitialized(); sdk.stopScan() })
        selection = EditText(this).apply {
            hint = "Full 14-byte SN from scan results"
            isSingleLine = true
        }
        content.addView(selection)
        discovery = label("Scan observations: none")
        content.addView(discovery)
        content.addView(button("Connect selected SN") { connectSelected() })
        connection = label("Connection: not initialized")
        content.addView(connection)
        content.addView(button("Display Even Hub text") {
            requireReady()
            sdk.openEvenHubTextPage("Hello enterprise")
        })
        content.addView(button("Enable glasses microphone") { setAudio(true) })
        content.addView(button("Disable glasses microphone") { setAudio(false) })
        content.addView(button("Close Even Hub page") { requireInitialized(); sdk.closeEvenHub() })
        content.addView(button("Sync time (after page closed)") {
            requireReady()
            sdk.syncG2Time(checkNotNull(selectedRight).address)
        })
        content.addView(button("Disconnect") { disconnect() })
        content.addView(label("Session log (bounded; no license or audio payloads)"))
        logs = label("")
        content.addView(logs)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    /** Small text-only views avoid a UI dependency in the native integration sample. */
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 10, 0, 10)
        setTextIsSelectable(true)
    }

    /** Every host action reports safe errors; no proof/signature content is rendered. */
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener {
            runCatching(action).onFailure { appendLog("Request rejected: ${it.javaClass.simpleName}") }
        }
    }

    /** Guard host actions before invoking the process-owned SDK. */
    private fun requireInitialized() = check(initialized) { "Initialize first" }
    /** Whole-pair native READY is the only UI admission signal for business actions. */
    private fun requireReady() = check(initialized && ready) { "Both legs must be READY" }

    /** Permissions belong to the Activity; the native SDK never displays its own prompt. */
    private fun requestScan() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= 23 -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyArray()
        }
        val missing = permissions.filter {
            Build.VERSION.SDK_INT >= 23 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= 23 && missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val bluetooth = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetooth.adapter?.isEnabled != true) {
            appendLog("Enable Bluetooth, then tap Initialize and scan again")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        initializeIfNeeded()
        sdk.scan()
        appendLog("Scanning; observations are separate from connection readiness")
    }

    /** Resume scanning only after every requested permission was granted. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            runCatching { requestScan() }.onFailure { appendLog("Unable to start scan: ${it.javaClass.simpleName}") }
        } else appendLog("Bluetooth scan/connect permission is required")
    }

    /** Load a whole bundle; an empty sample permits scanning but grants no device access. */
    private fun initializeIfNeeded() {
        if (initialized) return
        val actual = assets.list("")?.contains("enterprise-licenses.json") == true
        val filename = if (actual) "enterprise-licenses.json" else "enterprise-licenses.example.json"
        val json = assets.open(filename).bufferedReader().use { it.readText() }
        val bundle = EnterpriseLicenseBundle.fromJsonString(json)
        sdk.initialize(applicationContext, bundle,
            onEvent = { event -> onUi { acceptDiscovery(event) } },
            onG2AuthEvent = { event -> onUi {
                if (event.sn == selectedSn && event.generation >= pairGeneration) {
                    pairGeneration = event.generation
                    ready = event.ready
                    connection.text = "Connection: ${if (ready) "READY (both legs)" else "unavailable"}\nSN=${event.sn}\nGeneration=${event.generation}"
                    appendLog("Pair ready=${event.ready}")
                }
            } },
            onG2ConnectionEvent = { event -> onUi {
                if (event.sn == selectedSn && event.generation >= pairGeneration) {
                    pairGeneration = event.generation
                    if (event.phase != "ready") ready = false
                    appendLog("Endpoint ${event.endpoint}: ${event.phase}; failure=${event.failure ?: "none"}")
                }
            } },
            onG2TimeSyncEvent = { event -> onUi {
                appendLog("Time sync operation=${event.operation}; success=${event.success}")
            } },
            onEvenHubEvent = { event ->
                // PCM is already decoded in native; only bounded counters cross into this UI.
                val count = (event.details["audioPcm"] as? ByteArray)?.size
                onUi {
                    if (event.event == "audioEvent" && count != null) {
                        pcmBytes += count
                        pcmEvents++
                        if (pcmEvents % 50 == 0) appendLog("PCM16 mono 16kHz: $pcmBytes bytes")
                    } else appendLog("EvenHub ${event.phase}; heartbeats=${event.heartbeatCount}; event=${event.event ?: "none"}; failure=${event.failure ?: "none"}")
                }
            },
            onOtaEvent = { event -> onUi {
                appendLog("OTA operation=${event.operation}; phase=${event.phase}; progress=${event.progress}")
            } })
        initialized = true
        connection.text = "Connection: initialized, disconnected"
        appendLog(if (actual) "Loaded native license bundle" else "No issued license loaded: scanning works; connection is denied")
    }

    /** Merge exact-SN observations while retaining the original Android address and name. */
    private fun acceptDiscovery(event: NativeBleEvent) {
        if (event.type.name != "SCAN_RESULT") return
        val raw = event.value as? Map<*, *> ?: return
        if (raw["isPureScanResult"] == true) return
        val sn = raw["sn"] as? String ?: return
        val legs = candidates.getOrPut(sn) { linkedMapOf() }
        for (value in raw["devices"] as? List<*> ?: return) {
            val device = value as? Map<*, *> ?: continue
            if (device["belongConfig"] != EvenDevicesAndroidBle.G2_CONFIG || device["sn"] != sn) continue
            val address = device["uuid"] as? String ?: continue
            val name = device["name"] as? String ?: continue
            val side = when {
                name.contains("_L_", ignoreCase = true) -> "left"
                name.contains("_R_", ignoreCase = true) -> "right"
                else -> continue
            }
            legs[side] = G2Endpoint(address, name)
        }
        discovery.text = "Scan observations:\n" + candidates.entries.joinToString("\n") { (id, pair) ->
            "$id\n  left=${pair["left"]?.address ?: "not seen"}\n  right=${pair["right"]?.address ?: "not seen"}"
        }
    }

    /** A selected complete pair is required; the SDK performs the exact-SN license lookup. */
    private fun connectSelected() {
        requireInitialized()
        val sn = selection.text.toString()
        val legs = candidates[sn] ?: error("Scan selected full SN first")
        val left = checkNotNull(legs["left"])
        val right = checkNotNull(legs["right"])
        check(!left.address.equals(right.address, ignoreCase = true))
        sdk.stopScan()
        // Native validates the proof before replacing its current pair; this UI marks admission pending.
        val previousSn = selectedSn
        val previousLeft = selectedLeft
        val previousRight = selectedRight
        val previousReady = ready
        val previousGeneration = pairGeneration
        ready = false
        selectedSn = sn
        selectedLeft = left
        selectedRight = right
        pairGeneration = 0
        try {
            sdk.connectG2(sn, left, right)
            connection.text = "Connection: requesting $sn"
        } catch (error: IllegalArgumentException) {
            // Invalid proof/locator admission happens before native replaces its prior pair.
            selectedSn = previousSn
            selectedLeft = previousLeft
            selectedRight = previousRight
            ready = previousReady
            pairGeneration = previousGeneration
            throw error
        }
    }

    /** Native waits for device ACK before admitting decoded microphone frames. */
    private fun setAudio(enabled: Boolean) {
        requireReady()
        sdk.executeEvenHubCommand("audioControl", mapOf("enabled" to enabled)) { result -> onUi {
            appendLog(if (result.isSuccess) "Audio enabled=$enabled acknowledged" else "Audio command failed")
        } }
    }

    /** Clear UI admission before native teardown can synchronously report an old session. */
    private fun disconnect() {
        requireInitialized()
        val left = selectedLeft
        val right = selectedRight
        ready = false
        selectedSn = null
        selectedLeft = null
        selectedRight = null
        if (left != null && right != null) sdk.disconnectG2(left, right)
        connection.text = "Connection: disconnected"
    }

    /** Raw scan callbacks can be off-main; no Activity view is touched from native callback threads. */
    private fun onUi(action: () -> Unit) {
        main.post { if (!destroyed) action() }
    }

    /** Retain a bounded diagnostic window rather than unbounded audio/session history. */
    private fun appendLog(message: String) {
        logLines.addLast(message)
        while (logLines.size > 100) logLines.removeFirst()
        logs.text = logLines.joinToString("\n")
    }

    override fun onDestroy() {
        // This minimal host ends its session with the Activity; production apps can retain an owner.
        destroyed = true
        main.removeCallbacksAndMessages(null)
        if (initialized) runCatching { sdk.reset() }
        super.onDestroy()
    }
}
