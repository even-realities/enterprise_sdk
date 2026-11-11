package com.evenrealities.devices

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Stable admission failures never reflect supplied text, image bytes or credentials. */
class G2EvenHubException(val code: String) : IllegalStateException("EvenHub request rejected: $code")

/** All EvenHub wire commands live here; hosts supply validated logical fields, never protobuf. */
object G2EvenHubCodec {
    const val WIDGET_ID = 11417L
    const val CONTAINER_NAME = "native-text"
    enum class Command(val logicalName: String, val request: Int, val requestBranch: Int,
                       val response: Int, val branch: Int, val success: Long) {
        CREATE("createPage", 0, 3, 1, 4, 0), IMAGE("updateImage", 3, 5, 4, 6, 4),
        TEXT("updateText", 5, 9, 6, 10, 8), REBUILD("rebuildPage", 7, 7, 8, 8, 6),
        CLOSE("shutdownPage", 9, 11, 10, 12, 10), HEARTBEAT("heartbeat", 12, 14, 13, 15, 12),
        AUDIO("audioControl", 15, 18, 16, 19, -1), MENU_FAILURE("menuStartupFailed", 18, 21, -1, -1, -1),
        IMU("imuControl", 19, 22, 20, 23, -1), MENU_RESPONSE("menuSubitemResponse", 23, 26, -1, -1, -1),
    }
    enum class Reply { IGNORED, SUCCESS, REJECTED }
    data class Input(val name: String, val terminal: Boolean, val data: Map<String, Any> = emptyMap())
    internal data class Container(val kind: String, val id: Long, val name: String)
    internal data class MenuItem(val id: Long, val subItems: List<Long>)
    internal data class Layout(val widget: Long, val containers: Map<Long, Container>, val menu: Map<Long, MenuItem>)
    internal data class Request(val command: Command, val body: ByteArray, val layout: Layout? = null,
                                val expected: Map<Int, Long> = emptyMap(), val expectedName: String? = null)
    internal data class Response(val status: Reply, val details: Map<String, Any> = emptyMap())
    private data class Field(val wire: Int, val scalar: Long? = null, val bytes: ByteArray? = null,
                             val float: Float? = null)

    /** A compact demo is intentionally stricter than the general native page API. */
    fun validateText(text: String) {
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 64) throw G2EvenHubException("invalidText")
    }
    internal fun demoParameters(text: String): Map<String, Any> {
        validateText(text)
        return mapOf("widgetId" to WIDGET_ID, "textObjects" to listOf(mapOf(
            "xPosition" to 24, "yPosition" to 24, "width" to 528, "height" to 240,
            "paddingLength" to 8, "containerId" to 1, "containerName" to CONTAINER_NAME,
            "isEventCapture" to true, "content" to text)))
    }
    fun create(text: String, session: Long, magic: Int, sync: Int): ByteArray =
        packets(prepare("createPage", demoParameters(text), session).single(), magic, sync).single()
    fun heartbeat(count: Long, magic: Int, sync: Int): ByteArray =
        packets(prepare("heartbeat", emptyMap(), 1, count = count).single(), magic, sync).single()
    fun close(magic: Int, sync: Int): ByteArray =
        packets(prepare("shutdownPage", emptyMap(), 1).single(), magic, sync).single()

    /** Validate the complete logical request before page ownership or transport state changes. */
    internal fun prepare(name: String, parameters: Map<String, Any?>, session: Long,
                         layout: Layout? = null, count: Long = 1, mapSession: Long = 1): List<Request> {
        val command = Command.entries.find { it.logicalName == name } ?: invalid()
        val p = Parameters(parameters)
        val request = when (command) {
            Command.CREATE, Command.REBUILD -> page(command, p, session, layout?.widget ?: WIDGET_ID)
            Command.TEXT -> {
                p.keys("containerId", "containerName", "contentOffset", "contentLength", "content", "textColor")
                val id = p.integer("containerId", 1, UINT32)
                val nameValue = p.string("containerName", 1, 15)
                matchContainer(layout, id, nameValue, "text")
                val text = p.string("content", 0, 1999)
                val offset = p.integer("contentOffset", 0, UINT32, 0)
                val length = p.integer("contentLength", 0, UINT32, text.toByteArray(Charsets.UTF_8).size.toLong())
                Request(command, message {
                    scalar(1, id); bytes(2, nameValue.utf8); scalar(3, offset); scalar(4, length); bytes(5, text.utf8)
                    if (p.has("textColor")) scalar(6, p.integer("textColor", 0, 4))
                })
            }
            Command.IMAGE -> {
                p.keys("containerId", "containerName", "data", "compressMode")
                val id = p.integer("containerId", 1, UINT32)
                val nameValue = p.string("containerName", 1, 15)
                matchContainer(layout, id, nameValue, "image")
                val data = p.byteArray("data")
                if (data.isEmpty() || data.size > 1048576 || mapSession !in 1..UINT32) invalid()
                val compress = p.integer("compressMode", 0, 2, 0)
                return (data.indices step 4096).mapIndexed { index, offset ->
                    val chunk = data.copyOfRange(offset, minOf(data.size, offset + 4096))
                    val metadata = linkedMapOf(1 to id, 3 to mapSession, 4 to data.size.toLong(),
                        5 to compress, 6 to index.toLong(), 7 to chunk.size.toLong())
                    Request(command, message {
                        scalar(1, id); bytes(2, nameValue.utf8)
                        metadata.filterKeys { it != 1 }.forEach { (field, value) -> scalar(field, value) }
                        bytes(8, chunk)
                    }, expected = metadata, expectedName = nameValue)
                }
            }
            Command.CLOSE -> {
                p.keys("exitMode"); val mode = p.integer("exitMode", 0, 1, 0)
                Request(command, message { if (mode != 0L) scalar(1, mode) }, expected = mapOf(1 to mode))
            }
            Command.HEARTBEAT -> {
                p.keys(); if (count !in 1..UINT32) invalid()
                // Production Dart sends HeartBeatPacket() with omitted Cnt. Retain the local
                // metric for the coordinator, but never encode it or require firmware to echo it.
                Request(command, byteArrayOf(), expected = mapOf(1 to count))
            }
            Command.AUDIO -> {
                p.keys("enabled"); val target = if (p.boolean("enabled")) 1L else 0L
                Request(command, message { scalar(1, target) }, expected = mapOf(1 to target))
            }
            Command.IMU -> {
                p.keys("enabled", "reportFrequency"); val target = if (p.boolean("enabled")) 1L else 0L
                Request(command, message { scalar(1, target); scalar(2, p.integer("reportFrequency", 100, 1000, 100)) },
                    expected = mapOf(1 to target))
            }
            Command.MENU_FAILURE -> {
                p.keys("errorCode", "errorString")
                Request(command, message {
                    scalar(1, p.integer("errorCode", 0, UINT32)); bytes(2, p.string("errorString", 0, 199, "").utf8)
                })
            }
            Command.MENU_RESPONSE -> {
                p.keys("parentItemId", "subItemId", "status")
                val parent = p.integer("parentItemId", 1, UINT32)
                val sub = p.integer("subItemId", 1, UINT32)
                if (layout?.menu?.get(parent)?.subItems?.contains(sub) != true) invalid()
                Request(command, message { scalar(1, parent); scalar(2, sub); scalar(3, p.integer("status", 0, UINT32, 0)) })
            }
        }
        return listOf(request)
    }

    private fun page(command: Command, p: Parameters, session: Long, widgetDefault: Long): Request {
        p.keys("widgetId", "textObjects", "listObjects", "imageObjects", "menu")
        if (command == Command.REBUILD && p.has("widgetId")) invalid()
        val widget = if (command == Command.CREATE) p.integer("widgetId", 1, UINT32, WIDGET_ID) else widgetDefault
        if (session !in 1..UINT32) invalid()
        val containers = linkedMapOf<Long, Container>()
        var captures = 0
        fun encodeContainer(value: Map<String, Any?>, kind: String): ByteArray {
            val c = Parameters(value)
            val common = arrayOf("xPosition", "yPosition", "width", "height", "containerId", "containerName", "zOrderIndex")
            val border = arrayOf("borderWidth", "borderColor", "borderRadius", "paddingLength", "isEventCapture")
            c.keys(*(common + if (kind == "image") emptyArray() else border +
                if (kind == "text") arrayOf("content", "textColor") else arrayOf("itemContainer")))
            val id = c.integer("containerId", 1, UINT32)
            val name = c.string("containerName", 1, 15)
            if (containers.put(id, Container(kind, id, name)) != null) invalid()
            val width = c.integer("width", if (kind == "image") 20 else 1, if (kind == "image") 288 else 576)
            val height = c.integer("height", if (kind == "image") 20 else 1, if (kind == "image") 144 else 288)
            val x = c.integer("xPosition", 0, 576, 0); val y = c.integer("yPosition", 0, 288, 0)
            if (x + width > 576 || y + height > 288) invalid()
            return message {
                scalar(1, x); scalar(2, y); scalar(3, width); scalar(4, height)
                if (kind == "image") {
                    scalar(5, id); bytes(6, name.utf8)
                    if (c.has("zOrderIndex")) scalar(7, c.integer("zOrderIndex", 0, UINT32))
                } else {
                    // Zero-valued defaults are omitted, matching the established Dart canonical demo.
                    listOf(5 to c.integer("borderWidth", 0, 5, 0), 6 to c.integer("borderColor", 0, 15, 0),
                        7 to c.integer("borderRadius", 0, 10, 0), 8 to c.integer("paddingLength", 0, 32, 0))
                        .forEach { (field, value) -> if (value != 0L) scalar(field, value) }
                    scalar(9, id); bytes(10, name.utf8)
                    val capture = c.boolean("isEventCapture", false)
                    if (capture) captures++
                    if (kind == "text") {
                        if (capture) scalar(11, 1)
                        bytes(12, c.string("content", 0, 999, "").utf8)
                    } else {
                        val items = Parameters(c.map("itemContainer"))
                        items.keys("itemNames", "itemWidth", "isItemSelectBorderEnabled")
                        val names = items.strings("itemNames", 1, 20, 63)
                        bytes(11, message {
                            scalar(1, names.size.toLong()); scalar(2, items.integer("itemWidth", 0, 576, 0))
                            scalar(3, if (items.boolean("isItemSelectBorderEnabled", false)) 1 else 0)
                            names.forEach { bytes(4, it.utf8) }
                        })
                        if (capture) scalar(12, 1)
                    }
                    if (c.has("zOrderIndex")) scalar(13, c.integer("zOrderIndex", 0, UINT32))
                    if (kind == "text" && c.has("textColor")) scalar(14, c.integer("textColor", 0, 4))
                }
            }
        }
        val lists = p.maps("listObjects", 4).map { encodeContainer(it, "list") }
        val texts = p.maps("textObjects", 8).map { encodeContainer(it, "text") }
        val images = p.maps("imageObjects", 4).map { encodeContainer(it, "image") }
        if (containers.size !in 1..12 || captures > 1) invalid()
        val menu = linkedMapOf<Long, MenuItem>()
        val menuBytes = if (p.has("menu")) {
            val m = Parameters(p.map("menu")); m.keys("items")
            val items = m.maps("items", 10, required = true)
            if (items.isEmpty()) invalid()
            message {
                items.forEach { value ->
                    val i = Parameters(value); i.keys("itemName", "itemId", "position", "checkedIndex", "subItems")
                    val id = i.integer("itemId", 1, UINT32)
                    val subIds = mutableListOf<Long>()
                    val subBytes = i.maps("subItems", 5).map { subValue ->
                        val s = Parameters(subValue); s.keys("itemName", "itemId")
                        val subId = s.integer("itemId", 1, UINT32)
                        if (subId in subIds) invalid(); subIds += subId
                        message { bytes(1, s.string("itemName", 1, 31).utf8); scalar(2, subId) }
                    }
                    if (menu.put(id, MenuItem(id, subIds)) != null) invalid()
                    bytes(1, message {
                        bytes(1, i.string("itemName", 1, 31).utf8); scalar(2, id)
                        scalar(3, i.integer("position", 0, 10, 0)); subBytes.forEach { bytes(4, it) }
                        scalar(5, i.integer("checkedIndex", 0, subIds.size.toLong(), 0))
                    })
                }
            }
        } else null
        return Request(command, message {
            scalar(1, containers.size.toLong()); lists.forEach { bytes(2, it) }; texts.forEach { bytes(3, it) }
            images.forEach { bytes(4, it) }
            if (command == Command.CREATE) scalar(5, widget)
            menuBytes?.let { bytes(if (command == Command.CREATE) 6 else 5, it) }
            if (command == Command.CREATE) scalar(7, session)
        }, Layout(widget, containers, menu))
    }

    internal fun packets(request: Request, magic: Int, sync: Int): List<ByteArray> {
        require(magic in 1..255)
        return G2EvenHubTransport.packets(message {
            scalar(1, request.command.request.toLong()); scalar(2, magic.toLong()); bytes(request.command.requestBranch, request.body)
        }, sync)
    }

    /** An actual, correctly typed response oneof is required before proto3 defaults are meaningful. */
    internal fun response(frame: G2EvenHubTransport.Message, request: Request, magic: Int, sync: Int): Response {
        if (frame.result != 0) return Response(if (frame.sync == sync) Reply.REJECTED else Reply.IGNORED)
        val root = fields(frame.payload) ?: return Response(Reply.IGNORED)
        val command = request.command
        // Enterprise firmware was observed returning heartbeat Cmd=12 with the
        // actual response branch 15/result 12, rather than the standard Cmd=13.
        // Admit only this heartbeat dialect; branch, magic and body validation
        // below still reject request echoes and malformed or unsuccessful ACKs.
        val responseCommand = root.scalar(1)
        val matchesCommand = responseCommand == command.response.toLong() ||
            (command == Command.HEARTBEAT && responseCommand == command.request.toLong())
        if (!matchesCommand || root.scalar(2) != magic.toLong()) return Response(Reply.IGNORED)
        if (root.keys.filter { it in 3..26 } != listOf(request.command.branch) || root.keys.any { it !in 1..26 }) return Response(Reply.REJECTED)
        val body = root[request.command.branch]?.bytes?.let(::fields) ?: return Response(Reply.REJECTED)
        val allowed = when (command) { Command.IMAGE -> (1..8).toSet(); Command.HEARTBEAT -> setOf(1, 2); else -> setOf(1) }
        if (body.keys.any { it !in allowed } || body.any { (key, value) -> value.wire != if (command == Command.IMAGE && key == 2) 2 else 0 }) return Response(Reply.REJECTED)
        val resultField = if (command == Command.IMAGE) 8 else if (command == Command.HEARTBEAT) 2 else 1
        val expected = if (command in listOf(Command.AUDIO, Command.IMU)) request.expected[1] else command.success
        val result = body.scalar(resultField) ?: 0
        if (expected != result) return Response(Reply.REJECTED, mapOf("resultCode" to result))
        // Heartbeat Cnt is optional firmware data, not a documented request/response correlator.
        for ((field, value) in if (command == Command.CLOSE || command == Command.HEARTBEAT) emptyMap() else request.expected) {
            if ((body.scalar(field) ?: 0L) != value) return Response(Reply.REJECTED)
        }
        if (command == Command.IMAGE && body[2]?.bytes?.let { decodeUtf8(it)?.let { name -> name != request.expectedName } ?: true } == true) return Response(Reply.REJECTED)
        return Response(Reply.SUCCESS, mapOf("resultCode" to result, "acknowledged" to true))
    }

    /** Compatibility helpers retain existing minimal vectors while all processing uses the same codec. */
    fun reply(data: ByteArray, command: Command, magic: Int, sync: Int, count: Long): Reply {
        val frame = G2EvenHubTransport.Assembler().accept(data, 0) ?: return Reply.IGNORED
        val expected = if (command == Command.HEARTBEAT) mapOf(1 to count) else emptyMap()
        return response(frame, Request(command, byteArrayOf(), expected = expected), magic, sync).status
    }
    fun input(data: ByteArray, session: Long): Input? = G2EvenHubTransport.Assembler().accept(data, 0)?.let {
        event(it, session, Layout(WIDGET_ID, mapOf(1L to Container("text", 1, CONTAINER_NAME)), emptyMap()))
    }

    /** Firmware can request a plugin or emit timeout diagnostics before any page exists. */
    internal fun unpagedEvent(frame: G2EvenHubTransport.Message): Input? {
        if (frame.result != 0) return null
        val root = fields(frame.payload) ?: return null
        if (root[2]?.let { it.wire != 0 || it.scalar?.let { value -> value !in 0L..255L } != false } == true) return null
        val command = root.scalar(1) ?: return null
        val branch = when (command) { 17L -> 20; 14L -> 17; else -> return null }
        if (root.keys.any { it !in 1..26 } || root.keys.filter { it in 3..26 } != listOf(branch)) return null
        val body = root[branch]?.bytes?.let(::fields) ?: return null
        if (body.values.any { it.wire != 0 } || body.keys.any { it !in if (command == 17L) setOf(1) else setOf(1, 2) }) return null
        if (command == 17L) {
            val widget = body.scalar(1)?.takeIf { it > 0 } ?: return null
            return Input("menuStartup", false, mapOf("widgetId" to widget))
        }
        val event = body.scalar(1) ?: 0
        if (event !in 0..1) return null
        return Input(if (event == 0L) "timeoutPageEnter" else "timeoutPageExit", false,
            mapOf("value" to (body.scalar(2) ?: 0)))
    }

    /** Decode every known OS branch into semantic fields, never exposing protobuf bytes to a host. */
    internal fun event(frame: G2EvenHubTransport.Message, session: Long, layout: Layout): Input? {
        if (frame.result != 0) return null
        val root = fields(frame.payload) ?: return null
        if (root[2]?.let { it.wire != 0 || it.scalar?.let { value -> value !in 0L..255L } != false } == true) return null
        val cmd = root.scalar(1) ?: 0
        val branch = when (cmd) { 2L -> 13; 11L -> 16; 14L -> 17; 17L -> 20; 21L -> 24; 22L -> 25; else -> return null }
        if (root.keys.any { it !in 1..26 } || root.keys.filter { it in 3..26 } != listOf(branch)) return null
        val body = root[branch]?.bytes?.let(::fields) ?: return null
        fun scalarOnly(allowed: Set<Int>) = body.keys.all { it in allowed } && body.values.all { it.wire == 0 }
        fun identity(fields: Map<Int, Field>, kind: String? = null): Container? {
            val id = fields.scalar(1) ?: return null
            val c = layout.containers[id] ?: return null
            if (kind != null && c.kind != kind) return null
            if (fields[2] != null && (fields[2]?.wire != 2 || fields[2]?.bytes?.let(::decodeUtf8) != c.name)) return null
            return c
        }
        when (cmd) {
            2L -> {
                if (body.size != 1) return null
                val eventBranch = body.keys.single()
                val e = body[eventBranch]?.bytes?.let(::fields) ?: return null
                val value: Long
                val values = linkedMapOf<String, Any>()
                when (eventBranch) {
                    1, 2 -> {
                        val kind = if (eventBranch == 1) "list" else "text"
                        val c = identity(e, kind) ?: return null
                        val allowed = if (eventBranch == 1) setOf(1, 2, 3, 4, 5) else setOf(1, 2, 3)
                        if (e.keys.any { it !in allowed } || e.any { (key, field) -> field.wire != if (key == 2 || (eventBranch == 1 && key == 3)) 2 else 0 }) return null
                        value = e.scalar(if (eventBranch == 1) 5 else 3) ?: 0
                        values["containerId"] = c.id; values["containerName"] = c.name
                        values["kind"] = kind
                        if (eventBranch == 1) {
                            val name = e[3]?.bytes?.let(::decodeUtf8) ?: if (e[3] == null) "" else return null
                            if (name.utf8.size > 63) return null
                            values["selectedItemName"] = name; values["selectedItemIndex"] = e.scalar(4) ?: 0
                        }
                    }
                    3 -> {
                        if (e.keys.any { it !in 1..6 } || e.any { (key, field) -> field.wire != if (key == 3) 2 else 0 }) return null
                        value = e.scalar(1) ?: 0
                        if (e.scalar(5)?.let { it != layout.widget } == true || e.scalar(6)?.let { it != session } == true) return null
                        if (value in listOf(6L, 7L) && (e.scalar(5) != layout.widget || e.scalar(6) != session)) return null
                        val source = e.scalar(2) ?: 0
                        if (source !in 0..3) return null
                        values["kind"] = "system"
                        values["source"] = when (source) { 1L -> "rightGlasses"; 2L -> "ring"; 3L -> "leftGlasses"; else -> "unspecified" }
                        val reason = e.scalar(4) ?: 0
                        if (reason !in 0..UINT32) return null
                        values["exitReasonCode"] = reason
                        e.scalar(5)?.let { values["widgetId"] = it }; e.scalar(6)?.let { values["pageSessionId"] = it }
                        if (value == 8L) {
                            val imu = e[3]?.bytes?.let(::fields) ?: return null
                            if (imu.keys.any { it !in 1..3 } || imu.values.any { it.wire != 5 }) return null
                            for ((field, axis) in listOf(1 to "x", 2 to "y", 3 to "z")) {
                                val v = imu[field]?.float ?: 0f
                                if (!v.isFinite()) return null
                                values[axis] = v.toDouble()
                            }
                        }
                    }
                    else -> return null
                }
                val name = EVENT_NAMES.getOrNull(value.toInt())?.takeIf { value in 0..10 } ?: return null
                return Input(name, value in listOf(6L, 7L), values)
            }
            11L -> {
                if (body.keys.any { it !in 1..4 } || body.any { (key, field) -> field.wire != if (key == 2) 2 else 0 }) return null
                val c = identity(body) ?: return null
                return Input("privateEvent", false, mapOf("containerId" to c.id, "containerName" to c.name,
                    "eventId" to (body.scalar(3) ?: 0), "eventData" to (body.scalar(4) ?: 0)))
            }
            14L -> {
                if (!scalarOnly(setOf(1, 2))) return null
                val value = body.scalar(1) ?: 0
                if (value !in 0..1) return null
                return Input(if (value == 0L) "timeoutPageEnter" else "timeoutPageExit", false,
                    mapOf("value" to (body.scalar(2) ?: 0)))
            }
            17L -> {
                if (!scalarOnly(setOf(1)) || body.scalar(1) != layout.widget) return null
                return Input("menuStartup", false, mapOf("widgetId" to layout.widget))
            }
            21L -> {
                if (!scalarOnly(setOf(1))) return null
                val item = layout.menu[body.scalar(1)]?.takeIf { it.subItems.isEmpty() } ?: return null
                return Input("menuItemClick", false, mapOf("itemId" to item.id))
            }
            22L -> {
                if (!scalarOnly(setOf(1, 2, 3))) return null
                val item = layout.menu[body.scalar(1)] ?: return null
                val index = body.scalar(3) ?: return null
                if (index !in 1..item.subItems.size.toLong() || item.subItems[index.toInt() - 1] != body.scalar(2)) return null
                return Input("menuSubitemSwitch", false, mapOf("parentItemId" to item.id,
                    "subItemId" to item.subItems[index.toInt() - 1], "subItemIndex" to index))
            }
        }
        return null
    }

    /** Singular fields are unique; unsupported wire types, overlong varints and malformed UTF8 fail closed. */
    private fun fields(data: ByteArray): Map<Int, Field>? {
        val result = linkedMapOf<Int, Field>(); var offset = 0
        fun varint(): Long? {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset >= data.size) return null
                val byte = data[offset++].toInt() and 255
                if (shift == 63 && byte > 1) return null
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            return null
        }
        while (offset < data.size) {
            val key = varint() ?: return null
            val number = (key ushr 3).toInt()
            if (key < 0 || number <= 0 || key ushr 3 > Int.MAX_VALUE || number in result) return null
            result[number] = when (val wire = (key and 7).toInt()) {
                0 -> {
                    val scalar = varint() ?: return null
                    if (scalar !in 0..UINT32) return null
                    Field(wire, scalar = scalar)
                }
                2 -> {
                    val size = varint() ?: return null
                    if (size < 0 || size > data.size - offset) return null
                    Field(wire, bytes = data.copyOfRange(offset, offset + size.toInt())).also { offset += size.toInt() }
                }
                5 -> {
                    if (data.size - offset < 4) return null
                    Field(wire, float = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float).also { offset += 4 }
                }
                else -> return null
            }
        }
        return result
    }
    private fun Map<Int, Field>.scalar(field: Int) = get(field)?.takeIf { it.wire == 0 }?.scalar
    private fun matchContainer(layout: Layout?, id: Long, name: String, kind: String) {
        val c = layout?.containers?.get(id) ?: invalid()
        if (c.name != name || c.kind != kind) invalid()
    }
    private class Parameters(private val values: Map<String, Any?>) {
        fun keys(vararg allowed: String) { if (values.keys.any { it !in allowed }) invalid() }
        fun has(key: String) = values.containsKey(key)
        fun integer(key: String, min: Long, max: Long, default: Long? = null): Long {
            val value = if (!has(key)) default ?: invalid() else when (val raw = values[key]) {
                is Byte -> raw.toLong(); is Short -> raw.toLong(); is Int -> raw.toLong(); is Long -> raw
                else -> invalid()
            }
            if (value !in min..max) invalid(); return value
        }
        fun string(key: String, min: Int, max: Int, default: String? = null): String {
            val value = if (!has(key)) default ?: invalid() else values[key] as? String ?: invalid()
            val bytes = value.utf8
            if (bytes.size !in min..max || '\u0000' in value || decodeUtf8(bytes) != value) invalid()
            return value
        }
        fun boolean(key: String, default: Boolean? = null): Boolean =
            if (!has(key)) default ?: invalid() else values[key] as? Boolean ?: invalid()
        fun byteArray(key: String): ByteArray = (values[key] as? ByteArray)?.copyOf() ?: invalid()
        fun map(key: String): Map<String, Any?> = logicalMap(values[key])
        fun maps(key: String, max: Int, required: Boolean = false): List<Map<String, Any?>> {
            if (!has(key) && !required) return emptyList()
            val list = values[key] as? List<*> ?: invalid()
            if (list.size > max) invalid()
            return list.map(::logicalMap)
        }
        fun strings(key: String, min: Int, max: Int, bytes: Int): List<String> {
            val list = values[key] as? List<*> ?: invalid()
            if (list.size !in min..max) invalid()
            return list.map { val value = it as? String ?: invalid(); if (value.utf8.size > bytes || '\u0000' in value || decodeUtf8(value.utf8) != value) invalid(); value }
        }
    }
    internal fun logicalMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: invalid()
        if (map.keys.any { it !is String }) invalid()
        return map.entries.associate { (key, item) -> key as String to item }
    }
    private class Writer : ByteArrayOutputStream() {
        fun scalar(field: Int, value: Long) { varint(field.toLong() shl 3); varint(value) }
        fun bytes(field: Int, value: ByteArray) { varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); write(value) }
        private fun varint(value: Long) { var remaining = value
            while (remaining and -128L != 0L) { write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }
            write(remaining.toInt())
        }
    }
    private fun message(block: Writer.() -> Unit) = Writer().apply(block).toByteArray()
    private fun decodeUtf8(data: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString().takeIf { '\u0000' !in it }
    } catch (_: Exception) { null }
    private val String.utf8 get() = toByteArray(Charsets.UTF_8)
    private fun invalid(): Nothing = throw G2EvenHubException("invalidArguments")
    private const val UINT32 = 0xffffffffL
    private val EVENT_NAMES = listOf("click", "scrollUp", "scrollDown", "doubleClick", "foregroundEnter",
        "foregroundExit", "abnormalExit", "exit", "imuData", "longPress", "longPressRelease")
}
