package com.evenrealities.devices

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.fzfstudio.ezw_ble.ble.BleCommandLease

/** All-command coverage plus bounded fragmentation and hostile asynchronous image timelines. */
class G2EvenHubCommandsTest {
    private val owner = G2EvenHubOwner("S200LABJ270010", "RIGHT", 1, 10, 20)
    private val containers = mapOf<String, Any?>(
        "textObjects" to listOf(mapOf("containerId" to 1, "containerName" to "text", "width" to 500, "height" to 100, "content" to "Hello")),
        "imageObjects" to listOf(mapOf("containerId" to 2, "containerName" to "image", "width" to 100, "height" to 100)),
        "listObjects" to listOf(mapOf("containerId" to 3, "containerName" to "list", "width" to 500, "height" to 100,
            "itemContainer" to mapOf("itemNames" to listOf("One", "Two"), "isItemSelectBorderEnabled" to true))),
        "menu" to mapOf("items" to listOf(mapOf("itemId" to 1, "itemName" to "Action"),
            mapOf("itemId" to 2, "itemName" to "Choice", "subItems" to listOf(mapOf("itemId" to 21, "itemName" to "A"), mapOf("itemId" to 22, "itemName" to "B"))))))
    private val create get() = G2EvenHubCodec.prepare("createPage", containers, 123).single()
    private val layout get() = create.layout!!

    @Test fun everyLogicalCommandMatchesSharedSchemaAndStrictAcknowledgement() {
        val schema = JSONObject(File("../protocol/evenhub_commands.json").readText()).getJSONArray("commands")
        assertEquals(G2EvenHubCodec.Command.entries.map { it.logicalName }.toSet(),
            (0 until schema.length()).map { schema.getJSONObject(it).getString("name") }.toSet())
        val params = linkedMapOf<String, Map<String, Any?>>(
            "createPage" to containers,
            "updateImage" to mapOf("containerId" to 2, "containerName" to "image", "data" to byteArrayOf(1, 2, 3)),
            "updateText" to mapOf("containerId" to 1, "containerName" to "text", "content" to "更新"),
            "rebuildPage" to containers,
            "shutdownPage" to mapOf("exitMode" to 1), "heartbeat" to emptyMap(),
            "audioControl" to mapOf("enabled" to false), "imuControl" to mapOf("enabled" to false),
            "menuStartupFailed" to mapOf("errorCode" to 9, "errorString" to "Unavailable"),
            "menuSubitemResponse" to mapOf("parentItemId" to 2, "subItemId" to 21))
        for ((name, input) in params) {
            val request = G2EvenHubCodec.prepare(name, input, 123, layout, 7, 8).single()
            val payload = assembleOutgoing(G2EvenHubCodec.packets(request, 42, 7)).payload
            val root = read(payload)
            assertEquals(request.command.request.toLong(), root[1])
            assertEquals(42L, root[2])
            assertArrayEquals(request.body, root[request.command.requestBranch] as ByteArray)
            if (request.command.response < 0) continue
            val ack = ack(request, 42)
            assertEquals("$name valid", G2EvenHubCodec.Reply.SUCCESS,
                G2EvenHubCodec.response(ack, request, 42, 7).status)
            assertEquals("$name stale", G2EvenHubCodec.Reply.IGNORED,
                G2EvenHubCodec.response(ack, request, 43, 7).status)
            val missing = message(scalar(1, request.command.response.toLong()), scalar(2, 42))
            assertEquals("$name missing branch", G2EvenHubCodec.Reply.REJECTED,
                G2EvenHubCodec.response(frame(missing), request, 42, 7).status)
            val wrongWire = message(scalar(1, request.command.response.toLong()), scalar(2, 42), bytes(request.command.branch, bytes(1, byteArrayOf())))
            assertEquals("$name wrong wire", G2EvenHubCodec.Reply.REJECTED,
                G2EvenHubCodec.response(frame(wrongWire), request, 42, 7).status)
        }
        // Empty target-state ACK branches are legitimate proto3 false/disabled, not missing ACKs.
        for (name in listOf("audioControl", "imuControl")) {
            val disabled = G2EvenHubCodec.prepare(name, mapOf("enabled" to false), 123, layout).single()
            assertEquals(G2EvenHubCodec.Reply.SUCCESS, G2EvenHubCodec.response(ack(disabled, 42), disabled, 42, 7).status)
            val enabled = G2EvenHubCodec.prepare(name, mapOf("enabled" to true), 123, layout).single()
            assertEquals(G2EvenHubCodec.Reply.REJECTED, G2EvenHubCodec.response(ack(disabled, 42), enabled, 42, 7).status)
        }
    }

    @Test fun logicalValidationRejectsRawWireMetadataWrongTypesAndUnownedContainers() {
        for (parameters in listOf(containers + ("sessionId" to 123), containers + ("containerTotalNum" to 3),
            containers + ("widgetId" to 1.0), containers + ("widgetId" to true),
            containers + ("widgetId" to 0), containers + ("textObjects" to listOf(mapOf("containerId" to 1,
                "containerName" to "x".repeat(16), "width" to 100, "height" to 100))))) {
            failure("invalidArguments") { G2EvenHubCodec.prepare("createPage", parameters, 123) }
        }
        failure("invalidArguments") { G2EvenHubCodec.prepare("heartbeat", mapOf("count" to 1), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("updateText", mapOf("containerId" to 2, "containerName" to "image", "content" to "x"), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("updateImage", mapOf("containerId" to 2, "containerName" to "wrong", "data" to byteArrayOf(1)), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("updateImage", mapOf("containerId" to 2, "containerName" to "image", "data" to ByteArray(1048577)), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("rebuildPage", containers + ("widgetId" to 2), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("audioControl", mapOf("enabled" to 1), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("menuSubitemResponse", mapOf("parentItemId" to 2, "subItemId" to 99), 123, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("RAW_PROTOBUF", emptyMap(), 123, layout) }
        // No normalization or silent replacement of malformed UTF16 into otherwise-valid UTF8.
        failure("invalidArguments") { G2EvenHubCodec.prepare("updateText", mapOf("containerId" to 1, "containerName" to "text", "content" to "\uD800"), 123, layout) }
    }

    @Test fun transportSplitsWholeCrcAndStrictlyReassemblesBoundedInOrderStreams() {
        val payload = ByteArray(12000) { (it * 7).toByte() }
        val packets = G2EvenHubTransport.packets(payload, 7)
        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.size <= 147 })
        assertArrayEquals(payload, assembleOutgoing(packets).payload)
        val assembler = G2EvenHubTransport.Assembler()
        assertNull(assembler.accept(incoming(packets[1]), 0)) // No first fragment.
        assertNull(assembler.accept(incoming(packets.first()), 0))
        assertNull(assembler.accept(incoming(packets.first()), 1)) // Duplicate first destroys ambiguous stream.
        assertNull(assembler.accept(incoming(packets[1]), 2))
        val timed = G2EvenHubTransport.Assembler()
        assertNull(timed.accept(incoming(packets.first()), 0))
        assertNull(timed.accept(incoming(packets[1]), 3000))
        val badCrc = packets.map { it.copyOf() }.also { it.last()[it.last().lastIndex] = (it.last().last().toInt() xor 1).toByte() }
        assertNull(assembleMaybe(badCrc))
        val malformedLength = incoming(packets.first()).also { it[3] = (it[3].toInt() - 1).toByte() }
        assertNull(G2EvenHubTransport.Assembler().accept(malformedLength, 0))
        try { G2EvenHubTransport.packets(ByteArray(32769), 1); fail("bounded payload required") } catch (_: IllegalArgumentException) { }
        // At most four simultaneous streams: the fifth cannot allocate an unbounded receive buffer.
        val bounded = G2EvenHubTransport.Assembler()
        for (sync in 1..5) assertNull(bounded.accept(incoming(packets.first()).also { it[2] = sync.toByte() }, 0))
        assertNull(bounded.accept(incoming(packets[1]).also { it[2] = 5 }, 1))
    }

    @Test fun imageChunksRequireMatchingMetadataAndAdvanceOnlyAfterEachAck() {
        val host = Host(); host.open()
        var outcome: Result<Map<String, Any>>? = null
        val data = ByteArray(5000) { it.toByte() }
        host.coordinator.execute(owner, "updateImage", image(data)) { outcome = it }
        val first = host.requests.last()
        val body = read(first.body)
        assertEquals(0L, body[6]); assertEquals(4096L, body[7]); assertEquals(5000L, body[4])
        assertNull(outcome)
        host.receiveAck(first)
        val second = host.requests.last()
        assertEquals(1L, read(second.body)[6]); assertEquals(904L, read(second.body)[7])
        host.receiveAck(first) // Old magic cannot ACK the next fragment or restart its session.
        assertNull(outcome)
        assertEquals(3, host.requests.size) // CREATE plus two image chunks.
        host.receiveAck(second)
        assertTrue(outcome!!.isSuccess)
        assertEquals(2, outcome!!.getOrThrow()["imageFragments"])
        assertEquals(0, outcome!!.getOrThrow()["retries"])
    }

    @Test fun imageFailureRetriesWholeImageOnceWithFreshSessionThenFailsClosed() {
        val host = Host(); host.open()
        var outcome: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "updateImage", image(ByteArray(5000))) { outcome = it }
        val first = host.requests.last()
        host.receiveAck(first)
        val second = host.requests.last()
        host.clock.advance(3000) // Second fragment timeout: retry must begin at zero.
        val retry = host.requests.last()
        assertEquals(0L, read(retry.body)[6]); assertNotEquals(read(first.body)[3], read(retry.body)[3])
        assertNull(outcome)
        host.receiveAck(second) // Late previous image ACK cannot advance the retry.
        assertEquals(retry.magic, host.requests.last().magic)
        host.clock.advance(3000)
        assertEquals("timedOut", (outcome!!.exceptionOrNull() as G2EvenHubException).code)
        assertEquals("failed", host.events.last().phase)
    }

    @Test fun longImagesRenewLeaseBetweenAcknowledgedChunksAndResumeSameOperation() {
        val host = Host(); host.open()
        var outcome: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "updateImage", image(ByteArray(10000))) { outcome = it }
        host.clock.advance(2600); host.receiveAck(host.requests.last())
        host.clock.advance(2600); host.receiveAck(host.requests.last())
        val heartbeat = host.requests.last()
        assertEquals(G2EvenHubCodec.Command.HEARTBEAT, heartbeat.request.command)
        assertNull(outcome)
        host.receiveAck(heartbeat)
        assertEquals(G2EvenHubCodec.Command.IMAGE, host.requests.last().request.command)
        assertEquals(2L, read(host.requests.last().body)[6])
        host.receiveAck(host.requests.last())
        assertTrue(outcome!!.isSuccess)
        assertEquals(1L, outcome!!.getOrThrow()["heartbeatCount"])
    }

    @Test fun queuedCloseStopsImageAfterCurrentAckAndCompletesBothFuturesTruthfully() {
        val host = Host(); host.open()
        var imageResult: Result<Map<String, Any>>? = null
        var closeResult: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "updateImage", image(ByteArray(10000))) { imageResult = it }
        val imageRequest = host.requests.last()
        host.coordinator.execute(owner, "shutdownPage", emptyMap()) { closeResult = it }
        assertEquals("closing", host.events.last().phase)
        assertNull(closeResult)
        host.receiveAck(imageRequest)
        assertEquals("cancelled", (imageResult!!.exceptionOrNull() as G2EvenHubException).code)
        assertEquals(G2EvenHubCodec.Command.CLOSE, host.requests.last().request.command)
        host.receiveAck(host.requests.last())
        assertTrue(closeResult!!.isSuccess)
        assertEquals("closed", host.events.last().phase)
        host.clock.advance(20000)
        assertEquals(3, host.requests.size)
    }

    @Test fun oneWayCommandsCompleteAfterNativeSubmissionWithoutWaitingForNonexistentAck() {
        val host = Host(); host.open()
        for ((name, parameters) in listOf("menuStartupFailed" to mapOf("errorCode" to 7),
            "menuSubitemResponse" to mapOf("parentItemId" to 2, "subItemId" to 21, "status" to 0))) {
            var outcome: Result<Map<String, Any>>? = null
            host.coordinator.execute(owner, name, parameters) { outcome = it }
            assertTrue(outcome!!.isSuccess)
            assertEquals(false, outcome!!.getOrThrow()["acknowledged"])
            assertEquals(true, outcome!!.getOrThrow()["submitted"])
        }
        host.clock.advance(5000)
        assertEquals(G2EvenHubCodec.Command.HEARTBEAT, host.requests.last().request.command)
    }

    @Test fun fullEventBranchesDecodeSemanticallyAndRejectUnknownOrUnownedValues() {
        fun event(cmd: Long, branch: Int, body: ByteArray) = G2EvenHubCodec.event(frame(message(scalar(1, cmd), bytes(branch, body))), 123, layout)
        assertEquals("click", event(2, 13, bytes(1, message(scalar(1, 3), bytes(3, "One".toByteArray()), scalar(4, 1))))?.name)
        assertEquals("doubleClick", event(2, 13, bytes(2, message(scalar(1, 1), scalar(3, 3))))?.name)
        assertNull(event(2, 13, bytes(2, message(scalar(1, 3))))) // A list ID is not a text event owner.
        assertEquals("privateEvent", event(11, 16, message(scalar(1, 2), scalar(3, 7), scalar(4, 9)))?.name)
        assertEquals("timeoutPageEnter", event(14, 17, byteArrayOf())?.name)
        assertEquals("timeoutPageExit", event(14, 17, scalar(1, 1))?.name)
        assertNull(event(14, 17, scalar(1, 2)))
        assertEquals("menuStartup", event(17, 20, scalar(1, 11417))?.name)
        assertNull(event(17, 20, scalar(1, 999)))
        assertEquals("menuItemClick", event(21, 24, scalar(1, 1))?.name)
        assertNull(event(21, 24, scalar(1, 2)))
        assertEquals("menuSubitemSwitch", event(22, 25, message(scalar(1, 2), scalar(2, 22), scalar(3, 2)))?.name)
        assertNull(event(22, 25, message(scalar(1, 2), scalar(2, 21), scalar(3, 2))))
        val imu = event(2, 13, bytes(3, message(scalar(1, 8), bytes(3, message(float(1, 1.5f), float(2, -2f))))))!!
        assertEquals("imuData", imu.name); assertEquals(1.5, imu.data["x"]); assertEquals(-2.0, imu.data["y"]); assertEquals(0.0, imu.data["z"])
        assertNull(event(2, 13, bytes(3, message(scalar(1, 8), bytes(3, float(1, Float.NaN))))))
        assertNull(event(2, 13, bytes(3, scalar(1, 99))))
        assertNull(event(2, 13, bytes(2, message(scalar(1, 1), bytes(2, byteArrayOf(0xc3.toByte(), 0x28))))))
        assertNull(event(2, 13, message(bytes(1, scalar(1, 3)), bytes(2, scalar(1, 1)))))
        assertNull(event(2, 13, bytes(2, message(scalar(1, 1), scalar(3, 0), scalar(3, 0)))))
        for (magic in listOf(bytes(2, byteArrayOf()), scalar(2, 256))) {
            assertNull(G2EvenHubCodec.event(frame(message(scalar(1, 2), magic,
                bytes(13, bytes(2, scalar(1, 1))))), 123, layout))
        }
    }

    @Test fun inputBeforeCreateAckAndDuringCloseCannotChangeLeaseOrEmitClicks() {
        val host = Host()
        host.coordinator.execute(owner, "createPage", containers)
        val click = message(scalar(1, 2), bytes(13, bytes(2, scalar(1, 1))))
        host.receivePayload(click)
        assertEquals(listOf("opening"), host.events.map { it.phase })
        host.receiveAck(host.requests.last())
        host.receivePayload(click)
        assertEquals("input", host.events.last().phase)
        host.coordinator.close()
        host.receivePayload(click)
        assertEquals("closing", host.events.last().phase)
    }

    @Test fun closeRetiresOwnerBeforeSynchronousCompletionOpensNextPage() {
        val host = Host(); host.open()
        host.coordinator.execute(owner, "shutdownPage", emptyMap()) { outcome ->
            assertTrue(outcome.isSuccess)
            assertFalse(host.coordinator.hasPage())
            host.coordinator.execute(owner, "createPage", containers)
        }
        host.receiveAck(host.requests.last())
        assertEquals(listOf("closed", "opening"), host.events.takeLast(2).map { it.phase })
        assertEquals(G2EvenHubCodec.Command.CREATE, host.requests.last().request.command)
    }

    @Test fun heartbeatCountReflectsOnlyConfirmedResponsesIncludingFailureAndClosing() {
        val host = Host(); host.open(); host.clock.advance(5000)
        host.coordinator.close()
        assertEquals(0L, host.events.last().heartbeatCount)
        host.clock.advance(3000)
        assertEquals("failed", host.events.last().phase)
        assertEquals(0L, host.events.last().heartbeatCount)
    }

    @Test fun promptShutdownKeepsActualPageOwnedAndRenewedUntilScopedExit() {
        val host = Host(); host.open()
        var outcome: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "shutdownPage", mapOf("exitMode" to 1)) { outcome = it }
        host.receiveAck(host.requests.last())
        assertEquals(true, outcome!!.getOrThrow()["awaitingExit"])
        assertEquals("active", host.events.last().phase)
        assertTrue(host.coordinator.hasPage())
        host.clock.advance(5000)
        assertEquals(G2EvenHubCodec.Command.HEARTBEAT, host.requests.last().request.command)
        host.receiveAck(host.requests.last())
        host.receivePayload(message(scalar(1, 2), bytes(13, bytes(3,
            message(scalar(1, 7), scalar(5, 11417), scalar(6, 123))))))
        assertEquals("closed", host.events.last().phase)
        assertFalse(host.coordinator.hasPage())
    }

    @Test fun bothLegInputsUseFrozenAttemptAndOnlyCommandLegCanAcknowledge() {
        val host = Host()
        val left = owner.copy(endpoint = "LEFT", session = 11, attempt = 21)
        host.coordinator.execute(owner, "createPage", containers, listOf(left, owner))
        val createAck = ack(host.requests.last().request, host.requests.last().magic).payload
        G2EvenHubTransport.packets(createAck, 7).forEach {
            host.coordinator.onReceive(left.endpoint, left.session, left.attempt, incoming(it))
        }
        assertEquals("opening", host.events.last().phase)
        host.receiveAck(host.requests.last())
        val click = message(scalar(1, 2), bytes(13, bytes(2, scalar(1, 1))))
        G2EvenHubTransport.packets(click, 8).forEach {
            host.coordinator.onReceive(left.endpoint, left.session, left.attempt - 1, incoming(it))
        }
        assertEquals("active", host.events.last().phase)
        G2EvenHubTransport.packets(click, 8).forEach {
            host.coordinator.onReceive(left.endpoint, left.session, left.attempt, incoming(it))
        }
        assertEquals("input", host.events.last().phase)
        assertEquals(left, host.events.last().owner)
        assertEquals("text", host.events.last().details["kind"])
        host.coordinator.onDisconnected(left.endpoint, left.session, left.attempt)
        assertEquals("disconnected", host.events.last().failure)
    }

    @Test fun retiredCommandQueueLeasesCannotDrainOldFragmentsIntoReplacementPage() {
        val host = Host(); host.open()
        host.coordinator.execute(owner, "updateImage", image(ByteArray(10000)))
        val oldLease = host.leases.last().second
        val oldTails = host.leases.filter { it.second === oldLease }
        assertTrue(oldTails.size > 1) // Exercise a genuine queued multi-frame command tail.
        assertTrue(oldLease.isActive)
        host.clock.advance(3000)
        assertFalse(oldLease.isActive) // A whole-image retry revokes all old queued fragments.
        val retryLease = host.leases.last().second
        assertTrue(retryLease.isActive)
        host.coordinator.cancel("reset")
        assertFalse(retryLease.isActive)
        host.coordinator.execute(owner, "createPage", containers)
        assertTrue(host.leases.last().second.isActive)
        // Mirrors BleManager's immediate-before-write atomic check, with the same physical attempt.
        assertTrue(oldTails.none { it.second.isActive })
    }

    @Test fun oneWayQueueRejectionFailsInsteadOfReportingSubmitted() {
        val host = Host(); host.open(); host.rejectSend = true
        var outcome: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1)) { outcome = it }
        assertEquals("writeFailed", (outcome!!.exceptionOrNull() as G2EvenHubException).code)
        assertEquals("failed", host.events.last().phase)
    }

    @Test fun firmwareCStringsRejectEmbeddedNullInAllLogicalStringInputs() {
        failure("invalidArguments") { G2EvenHubCodec.prepare("createPage", mapOf("textObjects" to listOf(
            mapOf("containerId" to 1, "containerName" to "na\u0000me", "width" to 100, "height" to 100))), 1) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("updateText", mapOf("containerId" to 1,
            "containerName" to "text", "content" to "te\u0000xt"), 1, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("menuStartupFailed", mapOf("errorCode" to 1,
            "errorString" to "err\u0000or"), 1, layout) }
        failure("invalidArguments") { G2EvenHubCodec.prepare("createPage", mapOf("listObjects" to listOf(
            mapOf("containerId" to 1, "containerName" to "list", "width" to 100, "height" to 100,
                "itemContainer" to mapOf("itemNames" to listOf("it\u0000em"))))), 1) }
    }

    @Test fun cancelledHeartbeatCallbackCannotStealARearmedPageLease() {
        val host = Host(); host.open()
        val cancelledTick = host.clock.activeTasks().single()
        host.coordinator.execute(owner, "updateText", mapOf("containerId" to 1,
            "containerName" to "text", "content" to "Updated"))
        host.receiveAck(host.requests.last())
        val count = host.requests.size
        cancelledTick.action() // Already dispatched before cancellation, enters monitor after rearm.
        assertEquals(count, host.requests.size)
        host.clock.advance(5000)
        assertEquals(count + 1, host.requests.size)
        assertEquals(G2EvenHubCodec.Command.HEARTBEAT, host.requests.last().request.command)
    }

    @Test fun processSessionAllocatorSurvivesCoordinatorRecreationWithoutZeroOrReuse() {
        val ids = mutableSetOf<Long>()
        repeat(100) {
            val coordinator = G2EvenHubCoordinator({ _, _, _ -> }, { _, _ -> {} }, { event ->
                if (event.phase == "opening") {
                    val session = event.details["pageSessionId"] as Long
                    assertTrue(session in 1..0xffffffffL)
                    assertTrue(ids.add(session))
                }
            })
            coordinator.open(owner, "Hello")
            coordinator.cancel("reset")
        }
        assertEquals(100, ids.size)
    }

    @Test fun startupBeforeCreateRequiresReadyAttemptAndFailureReplyCreatesNoPageOrHeartbeat() {
        val host = Host()
        val startup = message(scalar(1, 17), bytes(20, scalar(1, 54321)))
        host.receivePayload(startup)
        assertTrue(host.events.isEmpty())
        failure("notReady") { host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1)) }
        host.coordinator.onReady(listOf(owner))
        G2EvenHubTransport.packets(startup, 7).forEach {
            host.coordinator.onReceive(owner.endpoint, owner.session, owner.attempt - 1, incoming(it))
        }
        assertTrue(host.events.isEmpty())
        host.receivePayload(startup)
        val event = host.events.single()
        assertEquals("menuStartup", event.event)
        assertEquals(54321L, event.details["widgetId"])
        assertFalse(event.details.containsKey("pageSessionId"))
        assertEquals(owner, event.owner)
        var result: Result<Map<String, Any>>? = null
        host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1, "errorString" to "Unavailable")) { result = it }
        assertTrue(result!!.isSuccess)
        assertEquals(false, result!!.getOrThrow()["acknowledged"])
        assertEquals(true, result!!.getOrThrow()["submitted"])
        assertFalse(result!!.getOrThrow().containsKey("pageSessionId"))
        assertFalse(host.coordinator.hasPage())
        assertTrue(host.clock.activeTasks().isEmpty())
        val replyOperation = result!!.getOrThrow()["operation"] as Long
        assertTrue(replyOperation > event.operation)
        host.coordinator.execute(owner, "createPage", containers)
        assertTrue(host.events.last().operation > replyOperation)
        assertEquals("opening", host.events.last().phase)
    }

    @Test fun unpagedStartupAndTimeoutDiagnosticsRejectMalformedOrPageOwnedEvents() {
        val host = Host(); host.coordinator.onReady(listOf(owner))
        for (payload in listOf(
            message(scalar(1, 17), bytes(20, byteArrayOf())),
            message(scalar(1, 17), bytes(20, scalar(1, 0))),
            message(scalar(1, 17), bytes(20, bytes(1, byteArrayOf(1)))),
            message(scalar(1, 17), bytes(20, message(scalar(1, 10), scalar(1, 10)))),
            message(scalar(1, 17), bytes(20, scalar(1, 10)), bytes(17, byteArrayOf())),
            message(scalar(1, 17), bytes(2, byteArrayOf()), bytes(20, scalar(1, 10))),
            message(scalar(1, 14), bytes(17, scalar(1, 99))),
            message(scalar(1, 2), bytes(13, bytes(2, scalar(1, 1)))),
            message(scalar(1, 21), bytes(24, scalar(1, 1))),
        )) host.receivePayload(payload)
        assertTrue(host.events.isEmpty())
        host.receivePayload(message(scalar(1, 14), bytes(17, byteArrayOf())))
        host.receivePayload(message(scalar(1, 14), bytes(17, message(scalar(1, 1), scalar(2, 7)))))
        assertEquals(listOf("timeoutPageEnter", "timeoutPageExit"), host.events.map { it.event })
        assertEquals(7L, host.events.last().details["value"])
        assertTrue(host.events[1].operation > host.events[0].operation)
        assertTrue(host.clock.activeTasks().isEmpty())
        assertFalse(host.coordinator.hasPage())
    }

    @Test fun disconnectOrResetRevokesUnpagedWriteTailAndCompletesPendingOnlyOnce() {
        val host = Host(); host.coordinator.onReady(listOf(owner))
        var completions = 0
        var outcome: Result<Map<String, Any>>? = null
        host.onSend = {
            host.coordinator.onDisconnected(owner.endpoint, owner.session, owner.attempt)
        }
        host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1,
            "errorString" to "x".repeat(199))) { completions++; outcome = it }
        assertEquals(1, completions)
        assertEquals("disconnected", (outcome!!.exceptionOrNull() as G2EvenHubException).code)
        assertEquals(1, host.leases.size) // Only first fragment submitted; the disconnected tail stopped.
        assertTrue(host.leases.none { it.second.isActive })
        failure("notReady") { host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1)) }
        host.onSend = null; host.coordinator.onReady(listOf(owner))
        host.coordinator.execute(owner, "menuStartupFailed", mapOf("errorCode" to 1))
        assertTrue(host.leases.last().second.isActive)
        host.coordinator.cancel("reset")
        assertTrue(host.leases.none { it.second.isActive })
        assertTrue(host.clock.activeTasks().isEmpty())
    }

    private data class Sent(val request: G2EvenHubCodec.Request, val magic: Int, val sync: Int) { val body get() = request.body }
    private inner class Host {
        val clock = Clock(); val events = mutableListOf<G2EvenHubEvent>(); val requests = mutableListOf<Sent>()
        val leases = mutableListOf<Pair<ByteArray, BleCommandLease>>()
        var rejectSend = false
        var onSend: (() -> Unit)? = null
        private val assembler = G2EvenHubTransport.Assembler()
        val coordinator = G2EvenHubCoordinator({ _, packet, lease ->
            if (rejectSend) error("Native queue admission rejected")
            leases += packet to lease
            onSend?.invoke()
            assembler.accept(incoming(packet), clock.now)?.let { message ->
                val root = read(message.payload)
                val command = G2EvenHubCodec.Command.entries.single { it.request.toLong() == root[1] }
                val body = root[command.requestBranch] as ByteArray
                val values = read(body)
                val expected = when (command) {
                    G2EvenHubCodec.Command.IMAGE -> listOf(1, 3, 4, 5, 6, 7).associateWith { values[it] as Long }
                    G2EvenHubCodec.Command.HEARTBEAT, G2EvenHubCodec.Command.AUDIO, G2EvenHubCodec.Command.IMU -> mapOf(1 to (values[1] as? Long ?: 0))
                    else -> emptyMap()
                }
                requests += Sent(G2EvenHubCodec.Request(command, body, expected = expected,
                    expectedName = if (command == G2EvenHubCodec.Command.IMAGE) String(values[2] as ByteArray) else null),
                    (root[2] as Long).toInt(), message.sync)
            }
        }, clock::schedule, events::add, { clock.now }, { 123 })
        fun open() { coordinator.execute(owner, "createPage", containers); receiveAck(requests.last()) }
        fun receiveAck(sent: Sent) {
            val value = ack(sent.request, sent.magic, sent.sync)
            receivePayload(value.payload)
        }
        fun receivePayload(payload: ByteArray) {
            G2EvenHubTransport.packets(payload, 7).forEach { coordinator.onReceive(owner.endpoint, owner.session, owner.attempt, incoming(it)) }
        }
    }
    private class Clock {
        data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private val tasks = mutableListOf<Task>()
        fun activeTasks() = tasks.filter { !it.cancelled }
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action); tasks += task; return { task.cancelled = true }
        }
        fun advance(delay: Long) {
            val target = now + delay
            while (true) {
                val task = tasks.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                now = task.due; task.cancelled = true; task.action()
            }
            now = target
        }
    }
    private fun image(data: ByteArray) = mapOf("containerId" to 2, "containerName" to "image", "data" to data)
    private fun ack(request: G2EvenHubCodec.Request, magic: Int, sync: Int = 7): G2EvenHubTransport.Message {
        val command = request.command
        val body = when (command) {
            G2EvenHubCodec.Command.IMAGE -> message(*request.expected.entries.map { scalar(it.key, it.value) }.toTypedArray(), scalar(8, 4))
            G2EvenHubCodec.Command.HEARTBEAT -> message(scalar(1, request.expected[1]!!), scalar(2, 12))
            G2EvenHubCodec.Command.AUDIO, G2EvenHubCodec.Command.IMU -> if (request.expected[1] == 0L) byteArrayOf() else scalar(1, 1)
            else -> if (command.success == 0L) byteArrayOf() else scalar(1, command.success)
        }
        return G2EvenHubTransport.Message(sync, 0, message(scalar(1, command.response.toLong()), scalar(2, magic.toLong()), bytes(command.branch, body)))
    }
    private fun frame(payload: ByteArray) = G2EvenHubTransport.Message(7, 0, payload)
    private fun incoming(packet: ByteArray) = packet.copyOf().also { it[1] = 0x12; it[7] = 0 }
    private fun assembleMaybe(packets: List<ByteArray>): G2EvenHubTransport.Message? {
        val a = G2EvenHubTransport.Assembler(); var result: G2EvenHubTransport.Message? = null
        packets.forEach { result = a.accept(incoming(it), 0) }; return result
    }
    private fun assembleOutgoing(packets: List<ByteArray>) = assembleMaybe(packets)!!
    private fun failure(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: G2EvenHubException) { assertEquals(code, e.code) }
    }
    private fun message(vararg fields: ByteArray): ByteArray = fields.fold(byteArrayOf()) { result, field -> result + field }
    private fun scalar(field: Int, value: Long) = varint(field.toLong() shl 3) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun float(field: Int, value: Float) = varint((field.toLong() shl 3) or 5) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    private fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream(); var remaining = value
        while (remaining and -128L != 0L) { output.write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }; output.write(remaining.toInt()); return output.toByteArray()
    }
    private fun read(data: ByteArray): Map<Int, Any> {
        val values = linkedMapOf<Int, Any>(); var offset = 0
        fun next(): Long { var value = 0L; var shift = 0
            while (true) { val byte = data[offset++].toInt() and 255; value = value or ((byte and 127).toLong() shl shift); if (byte and 128 == 0) return value; shift += 7 }
        }
        while (offset < data.size) {
            val key = next(); val field = (key ushr 3).toInt()
            values[field] = if (key and 7 == 0L) next() else { val size = next().toInt(); data.copyOfRange(offset, offset + size).also { offset += size } }
        }; return values
    }
}
