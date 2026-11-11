import Foundation
import CoreFoundation

/// Logical host arguments stop here: all EvenHub protobuf, IDs, correlation and
/// firmware bounds belong to the native SDK, never the Flutter adapter.
enum G2EvenHubCommands {
    struct Container { let name: String; let kind: String }
    struct PageDefinition {
        let body: [UInt8]
        let widget: UInt32
        let containers: [UInt32: Container]
        let menu: [UInt32: [UInt32]]
    }
    struct Request {
        let name: String
        let command: UInt64
        let branch: UInt64
        let body: [UInt8]
        let response: UInt64?
        let responseBranch: UInt64?
        let expected: [UInt64: UInt64]
        var expectedName: String? = nil
        var definition: PageDefinition? = nil
        func payload(magic: UInt8) -> Data {
            Data(scalar(1, command, explicit: true) + scalar(2, UInt64(magic)) + bytes(branch, body))
        }
        /// Use the same decision path for admission and diagnostics. Reasons
        /// contain only check/field names, never protobuf or user content.
        func accepts(_ data: Data, magic: UInt8) -> Bool { rejectionReason(data, magic: magic) == nil }

        func rejectionReason(_ data: Data, magic: UInt8) -> String? {
            guard let response, let responseBranch else { return "responseNotExpected" }
            guard let root = fields(Array(data)) else { return "rootMalformed" }
            // Observed enterprise firmware echoes request Cmd=12 while sending
            // the real heartbeat response branch 15/result 12. Admit only this
            // exact heartbeat dialect; the guards below still require the ACK
            // branch, magic, types and success result. Keep Cmd unchanged for
            // diagnostics so this external protocol deviation stays visible.
            let heartbeatCommandEcho = name == "heartbeat" && command == 12 && branch == 14 && body.isEmpty &&
                response == 13 && responseBranch == 15 && expected == [2: 12] && expectedName == nil && root[1]?.number == 12
            guard root[1]?.number == response || heartbeatCommandEcho else { return "rootCommandMismatch" }
            guard root[2]?.number == UInt64(magic) else { return "rootMagicMismatch" }
            guard oneof(root) == responseBranch else { return "rootOneofMismatch" }
            guard let body = root[responseBranch]?.data else { return "responseBranchWireMismatch" }
            guard let values = fields(body) else { return "responseBranchMalformed" }
            // Production Dart sends an empty HeartBeatPacket. Firmware Cnt
            // is optional telemetry, not a guaranteed request echo or identity.
            let heartbeat = name == "heartbeat"
            let allowed = Set(expected.keys).union(expectedName == nil ? [] : [2]).union(heartbeat ? [1] : [])
            guard Set(values.keys).isSubset(of: allowed) else { return "responseUnknownFields" }
            if heartbeat, let counter = values[1] {
                guard let value = counter.number, value <= UInt64(UInt32.max) else { return "heartbeatCounterInvalid" }
            }
            for (field, value) in expected.sorted(by: { $0.key < $1.key }) {
                guard values[field] == nil || values[field]?.number != nil else { return "responseFieldWireMismatch.\(field)" }
                guard (values[field]?.number ?? 0) == value else { return "responseFieldValueMismatch.\(field)" }
            }
            if let name = expectedName, let field = values[2] {
                guard let raw = field.data, String(bytes: raw, encoding: .utf8) == name else { return "imageNameMismatch" }
            }
            return nil
        }
    }

    /// Only primitive platform integers are accepted. In particular NSNumber
    /// booleans and floating point values cannot silently become IDs or sizes.
    struct Arguments {
        let values: [String: Any]
        init(_ values: [String: Any], _ allowed: [String]) throws {
            guard Set(values.keys).isSubset(of: Set(allowed)) else { throw G2EvenHubError.invalidArguments }
            self.values = values
        }
        func uint(_ key: String, _ fallback: UInt64? = nil, min: UInt64 = 0, max: UInt64 = UInt64(UInt32.max)) throws -> UInt64 {
            guard let raw = values[key] else {
                guard let fallback, fallback >= min, fallback <= max else { throw G2EvenHubError.invalidArguments }
                return fallback
            }
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(),
                  !["f", "d"].contains(String(cString: n.objCType)),
                  n.int64Value >= 0, n.uint64Value >= min, n.uint64Value <= max else { throw G2EvenHubError.invalidArguments }
            return n.uint64Value
        }
        func bool(_ key: String, _ fallback: Bool? = nil) throws -> Bool {
            guard let raw = values[key] else {
                guard let fallback else { throw G2EvenHubError.invalidArguments }; return fallback
            }
            guard let n = raw as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw G2EvenHubError.invalidArguments }
            return n.boolValue
        }
        func string(_ key: String, _ fallback: String? = nil, min: Int = 0, max: Int) throws -> String {
            guard let value = values[key] ?? fallback as Any? , let text = value as? String,
                  text.utf8.count >= min, text.utf8.count <= max,
                  !text.utf8.contains(0) else { throw G2EvenHubError.invalidArguments }
            return text
        }
        func maps(_ key: String, max: Int) throws -> [[String: Any]] {
            guard let value = values[key] else { return [] }
            guard let array = value as? [[String: Any]], array.count <= max else { throw G2EvenHubError.invalidArguments }
            return array
        }
    }

    static func page(_ parameters: [String: Any], create: Bool, session: UInt32, widget: UInt32 = 11417) throws -> PageDefinition {
        let a = try Arguments(parameters, ["listObjects", "textObjects", "imageObjects", "menu"] + (create ? ["widgetId"] : []))
        let lists = try a.maps("listObjects", max: 4), texts = try a.maps("textObjects", max: 8), images = try a.maps("imageObjects", max: 4)
        let total = lists.count + texts.count + images.count
        guard (1...12).contains(total) else { throw G2EvenHubError.invalidArguments }
        var body = scalar(1, UInt64(total)), identities: [UInt32: Container] = [:], captures = 0
        for (kind, items, branch) in [("list", lists, UInt64(2)), ("text", texts, 3), ("image", images, 4)] {
            for item in items {
                let common = ["xPosition", "yPosition", "width", "height", "containerId", "containerName", "zOrderIndex"]
                let border = ["borderWidth", "borderColor", "borderRadius", "paddingLength", "isEventCapture"]
                let c = try Arguments(item, common + (kind == "image" ? [] : border + (kind == "text" ? ["content", "textColor"] : ["itemContainer"])))
                let id = UInt32(try c.uint("containerId", min: 1)), name = try c.string("containerName", min: 1, max: 15)
                guard identities[id] == nil else { throw G2EvenHubError.invalidArguments }
                identities[id] = Container(name: name, kind: kind)
                let x = try c.uint("xPosition", 0, max: 576), y = try c.uint("yPosition", 0, max: 288)
                let width = try c.uint("width", min: kind == "image" ? 20 : 1, max: kind == "image" ? 288 : 576)
                let height = try c.uint("height", min: kind == "image" ? 20 : 1, max: kind == "image" ? 144 : 288)
                guard x + width <= 576, y + height <= 288 else { throw G2EvenHubError.invalidArguments }
                var b = scalar(1, x) + scalar(2, y) + scalar(3, width) + scalar(4, height)
                if kind == "image" {
                    b += scalar(5, UInt64(id)) + bytes(6, Array(name.utf8))
                    if c.values["zOrderIndex"] != nil { b += scalar(7, try c.uint("zOrderIndex"), explicit: true) }
                } else {
                    b += scalar(5, try c.uint("borderWidth", 0, max: 5)) + scalar(6, try c.uint("borderColor", 0, max: 15))
                    b += scalar(7, try c.uint("borderRadius", 0, max: 10)) + scalar(8, try c.uint("paddingLength", 0, max: 32))
                    b += scalar(9, UInt64(id)) + bytes(10, Array(name.utf8))
                    let capture = try c.bool("isEventCapture", false)
                    if capture { captures += 1 }
                    if kind == "text" {
                        b += scalar(11, capture ? 1 : 0) + bytes(12, Array(try c.string("content", "", max: 999).utf8), omitEmpty: true)
                        if c.values["textColor"] != nil { b += scalar(14, try c.uint("textColor", max: 4), explicit: true) }
                    } else {
                        guard let object = c.values["itemContainer"] as? [String: Any] else { throw G2EvenHubError.invalidArguments }
                        let i = try Arguments(object, ["itemNames", "itemWidth", "isItemSelectBorderEnabled"])
                        guard let names = i.values["itemNames"] as? [String], (1...20).contains(names.count),
                              names.allSatisfy({ $0.utf8.count <= 63 && !$0.utf8.contains(0) }) else { throw G2EvenHubError.invalidArguments }
                        var items = scalar(1, UInt64(names.count)) + scalar(2, try i.uint("itemWidth", 0, max: 576))
                        items += scalar(3, try i.bool("isItemSelectBorderEnabled", false) ? 1 : 0)
                        for name in names { items += bytes(4, Array(name.utf8)) }
                        b += bytes(11, items) + scalar(12, capture ? 1 : 0)
                    }
                    if c.values["zOrderIndex"] != nil { b += scalar(13, try c.uint("zOrderIndex"), explicit: true) }
                }
                body += bytes(branch, b)
            }
        }
        guard captures <= 1 else { throw G2EvenHubError.invalidArguments }
        let pageWidget = create ? UInt32(try a.uint("widgetId", UInt64(widget), min: 1)) : widget
        if create { body += scalar(5, UInt64(pageWidget)) }
        var menuIDs: [UInt32: [UInt32]] = [:]
        if let value = a.values["menu"] {
            guard let object = value as? [String: Any] else { throw G2EvenHubError.invalidArguments }
            let m = try Arguments(object, ["items"]), entries = try m.maps("items", max: 10)
            guard !entries.isEmpty else { throw G2EvenHubError.invalidArguments }
            var menu: [UInt8] = []
            for entry in entries {
                let i = try Arguments(entry, ["itemName", "itemId", "position", "checkedIndex", "subItems"])
                let id = UInt32(try i.uint("itemId", min: 1)), name = try i.string("itemName", min: 1, max: 31)
                guard menuIDs[id] == nil else { throw G2EvenHubError.invalidArguments }
                let subitems = try i.maps("subItems", max: 5)
                var item = bytes(1, Array(name.utf8)) + scalar(2, UInt64(id))
                item += scalar(3, try i.uint("position", 0, max: 10))
                var ids: [UInt32] = []
                for sub in subitems {
                    let s = try Arguments(sub, ["itemName", "itemId"]), sid = UInt32(try s.uint("itemId", min: 1))
                    guard !ids.contains(sid) else { throw G2EvenHubError.invalidArguments }
                    ids.append(sid)
                    item += bytes(4, bytes(1, Array(try s.string("itemName", min: 1, max: 31).utf8)) + scalar(2, UInt64(sid)))
                }
                item += scalar(5, try i.uint("checkedIndex", 0, max: UInt64(ids.count)))
                menuIDs[id] = ids
                menu += bytes(1, item)
            }
            body += bytes(create ? 6 : 5, menu)
        }
        if create { body += scalar(7, UInt64(session), explicit: true) }
        guard body.count <= 32750 else { throw G2EvenHubError.invalidArguments }
        return PageDefinition(body: body, widget: pageWidget, containers: identities, menu: menuIDs)
    }

    /// Commands share one native page lease. Image updates are materialized as
    /// bounded firmware chunks, then each chunk uses the normal BLE fragmenter.
    static func requests(_ name: String, parameters p: [String: Any], page: PageDefinition?, session: UInt32, count: UInt32, mapSession: UInt32) throws -> [Request] {
        func request(_ cmd: UInt64, _ branch: UInt64, _ body: [UInt8], _ response: UInt64?, _ rb: UInt64?, _ expected: [UInt64: UInt64]) -> Request {
            Request(name: name, command: cmd, branch: branch, body: body, response: response, responseBranch: rb, expected: expected)
        }
        if name == "createPage" || name == "rebuildPage" {
            let definition = try self.page(p, create: name == "createPage", session: session, widget: page?.widget ?? 11417)
            var r = request(name == "createPage" ? 0 : 7, name == "createPage" ? 3 : 7, definition.body,
                name == "createPage" ? 1 : 8, name == "createPage" ? 4 : 8, [1: name == "createPage" ? 0 : 6])
            r.definition = definition; return [r]
        }
        // Firmware can request a plugin launch before CREATE. Its failure
        // reply is the sole permitted unpaged command and owns no page lease.
        if name == "menuStartupFailed" {
            let a = try Arguments(p, ["errorCode", "errorString"])
            return [request(18, 21, scalar(1, try a.uint("errorCode")) + bytes(2, Array(try a.string("errorString", "", max: 199).utf8), omitEmpty: true), nil, nil, [:])]
        }
        guard let page else { throw G2EvenHubError.notReady }
        switch name {
        case "updateText", "updateImage":
            let keys = name == "updateText" ? ["containerId", "containerName", "contentOffset", "contentLength", "content", "textColor"] : ["containerId", "containerName", "data", "compressMode"]
            let a = try Arguments(p, keys), id = UInt32(try a.uint("containerId", min: 1)), containerName = try a.string("containerName", min: 1, max: 15)
            guard let container = page.containers[id], container.name == containerName,
                  container.kind == (name == "updateText" ? "text" : "image") else { throw G2EvenHubError.invalidArguments }
            let identity = scalar(1, UInt64(id)) + bytes(2, Array(containerName.utf8))
            if name == "updateText" {
                let content = try a.string("content", max: 1999)
                var body = identity + scalar(3, try a.uint("contentOffset", 0)) + scalar(4, try a.uint("contentLength", UInt64(content.utf8.count))) + bytes(5, Array(content.utf8), omitEmpty: true)
                if p["textColor"] != nil { body += scalar(6, try a.uint("textColor", max: 4), explicit: true) }
                return [request(5, 9, body, 6, 10, [1: 8])]
            }
            guard let data = p["data"] as? Data, !data.isEmpty, data.count <= 1048576 else { throw G2EvenHubError.invalidArguments }
            let mode = try a.uint("compressMode", 0, max: 2)
            return stride(from: 0, to: data.count, by: 4096).enumerated().map { index, offset in
                let chunk = Array(data[offset..<min(data.count, offset + 4096)])
                let metadata: [UInt64: UInt64] = [1: UInt64(id), 3: UInt64(mapSession), 4: UInt64(data.count), 5: mode, 6: UInt64(index), 7: UInt64(chunk.count), 8: 4]
                var body = identity + scalar(3, UInt64(mapSession)) + scalar(4, UInt64(data.count)) + scalar(5, mode)
                body += scalar(6, UInt64(index)) + scalar(7, UInt64(chunk.count)) + bytes(8, chunk)
                var r = request(3, 5, body, 4, 6, metadata); r.expectedName = containerName; return r
            }
        case "shutdownPage":
            let a = try Arguments(p, ["exitMode"])
            return [request(9, 11, scalar(1, try a.uint("exitMode", 0, max: 1)), 10, 12, [1: 10])]
        case "heartbeat":
            _ = try Arguments(p, [])
            // Keep attempted/confirmed counts local; the established firmware
            // contract renews with an explicitly present, empty nested packet.
            return [request(12, 14, [], 13, 15, [2: 12])]
        case "audioControl", "imuControl":
            let imu = name == "imuControl", a = try Arguments(p, imu ? ["enabled", "reportFrequency"] : ["enabled"])
            let enabled: UInt64 = try a.bool("enabled") ? 1 : 0
            var body = scalar(1, enabled)
            if imu { body += scalar(2, try a.uint("reportFrequency", 100, min: 100, max: 1000)) }
            return [request(imu ? 19 : 15, imu ? 22 : 18, body, imu ? 20 : 16, imu ? 23 : 19, [1: enabled])]
        case "menuSubitemResponse":
            let a = try Arguments(p, ["parentItemId", "subItemId", "status"]), parent = UInt32(try a.uint("parentItemId", min: 1)), sub = UInt32(try a.uint("subItemId", min: 1))
            guard page.menu[parent]?.contains(sub) == true else { throw G2EvenHubError.invalidArguments }
            return [request(23, 26, scalar(1, UInt64(parent)) + scalar(2, UInt64(sub)) + scalar(3, try a.uint("status", 0)), nil, nil, [:])]
        default: throw G2EvenHubError.invalidArguments
        }
    }

    struct Field { let number: UInt64?; let data: [UInt8]?; let float: Float? }
    static func oneof(_ fields: [UInt64: Field]) -> UInt64? {
        let keys = fields.keys.filter { $0 >= 3 }; return keys.count == 1 ? keys.first : nil
    }
    /// Strict singular decoder supports fixed32 for IMU. Repeated fields are
    /// generated outbound only; unsolicited duplicate singular values reject.
    static func fields(_ b: [UInt8]) -> [UInt64: Field]? {
        var o = 0, result: [UInt64: Field] = [:]
        while o < b.count {
            guard let tag = read(b, &o), tag >> 3 != 0, result[tag >> 3] == nil else { return nil }
            switch tag & 7 {
            case 0:
                guard let n = read(b, &o) else { return nil }; result[tag >> 3] = Field(number: n, data: nil, float: nil)
            case 2:
                guard let n = read(b, &o), n <= UInt64(b.count - o) else { return nil }
                result[tag >> 3] = Field(number: nil, data: Array(b[o..<o + Int(n)]), float: nil); o += Int(n)
            case 5:
                guard b.count - o >= 4 else { return nil }
                let bits = UInt32(b[o]) | UInt32(b[o+1]) << 8 | UInt32(b[o+2]) << 16 | UInt32(b[o+3]) << 24
                result[tag >> 3] = Field(number: nil, data: nil, float: Float(bitPattern: bits)); o += 4
            default: return nil
            }
        }
        return result
    }
    /// Bounded diagnostics expose tags and wire types only, including duplicate
    /// singular tags that the strict parser correctly rejects. No values or
    /// length-delimited contents are included in this metadata string.
    static func fieldMetadata(_ data: [UInt8]) -> String {
        var offset = 0, tags: [String] = []
        while offset < data.count, tags.count < 32 {
            guard let tag = read(data, &offset), tag >> 3 != 0 else { return (tags + ["malformedTag"]).joined(separator: ",") }
            let wire = tag & 7
            let kind: String
            switch wire { case 0: kind = "varint"; case 1: kind = "fixed64"; case 2: kind = "bytes"; case 5: kind = "fixed32"; default: kind = "unsupported\(wire)" }
            tags.append("\(tag >> 3):\(kind)")
            switch wire {
            case 0:
                guard read(data, &offset) != nil else { return (tags + ["truncated"]).joined(separator: ",") }
            case 1, 5:
                let length = wire == 1 ? 8 : 4
                guard data.count - offset >= length else { return (tags + ["truncated"]).joined(separator: ",") }
                offset += length
            case 2:
                guard let length = read(data, &offset), length <= UInt64(data.count - offset) else { return (tags + ["truncated"]).joined(separator: ",") }
                offset += Int(length)
            default: return tags.joined(separator: ",")
            }
        }
        if offset < data.count { tags.append("moreFields") }
        return tags.isEmpty ? "empty" : tags.joined(separator: ",")
    }

    static func scalar(_ key: UInt64, _ value: UInt64, explicit: Bool = false) -> [UInt8] {
        value == 0 && !explicit ? [] : varint(key << 3) + varint(value)
    }
    static func bytes(_ key: UInt64, _ value: [UInt8], omitEmpty: Bool = false) -> [UInt8] {
        value.isEmpty && omitEmpty ? [] : varint(key << 3 | 2) + varint(UInt64(value.count)) + value
    }
    static func varint(_ value: UInt64) -> [UInt8] {
        var x = value, output: [UInt8] = []
        repeat { var byte = UInt8(x & 127); x >>= 7; if x != 0 { byte |= 128 }; output.append(byte) } while x != 0
        return output
    }
    static func read(_ b: [UInt8], _ o: inout Int) -> UInt64? {
        var n: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard o < b.count else { return nil }; let byte = b[o]; o += 1
            if shift == 63 && byte > 1 { return nil }; n |= UInt64(byte & 127) << shift
            if byte & 128 == 0 { return n }
        }
        return nil
    }
}
