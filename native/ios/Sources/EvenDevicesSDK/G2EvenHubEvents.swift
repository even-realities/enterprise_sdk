import Foundation

/// All OS-to-host EvenHub branches are decoded natively into semantic values.
/// A real oneof, current container/menu identity and strict wire types precede
/// proto3 defaults, preventing malformed messages from becoming click/success.
enum G2EvenHubEvents {
    struct Input { let name: String; let terminal: Bool; let details: [String: Any] }
    typealias Fields = [UInt64: G2EvenHubCommands.Field]
    /// Firmware menu launches and timeout-page diagnostics can precede CREATE
    /// or follow close. They name a READY connection, never a synthetic page.
    static func unpagedConnectionEvent(_ data: Data) -> Input? {
        guard let root = G2EvenHubCommands.fields(Array(data)), let command = root[1]?.number,
              let branch = G2EvenHubCommands.oneof(root), let body = root[branch]?.data,
              root[2] == nil || (root[2]?.number ?? 256) <= 255,
              let fields = G2EvenHubCommands.fields(body) else { return nil }
        if command == 17, branch == 20 {
            guard Set(fields.keys).isSubset(of: [1]), let widget = number(fields, 1), widget > 0 else { return nil }
            return Input(name: "menuStartup", terminal: false, details: ["widgetId": widget])
        }
        if command == 14, branch == 17 {
            guard Set(fields.keys).isSubset(of: [1, 2]), let command = number(fields, 1), command <= 1,
                  let value = number(fields, 2) else { return nil }
            return Input(name: command == 0 ? "timeoutPageEnter" : "timeoutPageExit", terminal: false, details: ["value": value])
        }
        return nil
    }
    static func decode(_ data: Data, definition: G2EvenHubCommands.PageDefinition, session: UInt32) -> Input? {
        guard let root = G2EvenHubCommands.fields(Array(data)),
              let command = root[1]?.number, let branch = G2EvenHubCommands.oneof(root),
              let body = root[branch]?.data, let fields = G2EvenHubCommands.fields(body),
              root[2] == nil || (root[2]?.number ?? 256) <= 255 else { return nil }
        func input(_ name: String, _ values: [String: Any] = [:], terminal: Bool = false) -> Input {
            Input(name: name, terminal: terminal, details: values)
        }
        switch (command, branch) {
        case (2, 13):
            guard fields.count == 1, let eventBranch = fields.keys.first,
                  let bytes = fields[eventBranch]?.data, let f = G2EvenHubCommands.fields(bytes) else { return nil }
            if eventBranch == 1 || eventBranch == 2 {
                guard Set(f.keys).isSubset(of: eventBranch == 1 ? [1, 2, 3, 4, 5] : [1, 2, 3]),
                      let id = number(f, 1), id != 0, let container = definition.containers[UInt32(id)],
                      container.kind == (eventBranch == 1 ? "list" : "text"),
                      let name = string(f, 2, max: 15), f[2] == nil || name == container.name,
                      let type = number(f, eventBranch == 1 ? 5 : 3), let event = eventName(type), type != 8 else { return nil }
                var details: [String: Any] = ["containerId": id, "containerName": container.name, "kind": container.kind]
                if eventBranch == 1 {
                    guard let selectedName = string(f, 3, max: 63), let selectedIndex = number(f, 4) else { return nil }
                    details["selectedItemName"] = selectedName; details["selectedItemIndex"] = selectedIndex
                }
                return input(event, details)
            }
            guard eventBranch == 3, Set(f.keys).isSubset(of: [1, 2, 3, 4, 5, 6]), let type = number(f, 1), let name = eventName(type),
                  let source = number(f, 2), source <= 3, let reason = number(f, 4),
                  f[5] == nil || number(f, 5) == UInt64(definition.widget),
                  f[6] == nil || number(f, 6) == UInt64(session) else { return nil }
            let terminal = type == 6 || type == 7
            guard !terminal || (number(f, 5) == UInt64(definition.widget) && number(f, 6) == UInt64(session)) else { return nil }
            var details: [String: Any] = ["kind": "system", "source": ["unspecified", "rightGlasses", "ring", "leftGlasses"][Int(source)], "exitReasonCode": reason]
            if type == 8 {
                guard let bytes = f[3]?.data, let axes = G2EvenHubCommands.fields(bytes),
                      Set(axes.keys).isSubset(of: [1, 2, 3]) else { return nil }
                var imu: [String: Double] = [:]
                for (index, name) in [(UInt64(1), "x"), (2, "y"), (3, "z")] {
                    guard axes[index] == nil || axes[index]?.float != nil else { return nil }
                    let value = axes[index]?.float ?? 0
                    guard value.isFinite else { return nil }; imu[name] = Double(value)
                }
                for (name, value) in imu { details[name] = value }
            } else if f[3] != nil { return nil }
            if f[5] != nil { details["widgetId"] = definition.widget }
            if f[6] != nil { details["pageSessionId"] = session }
            return input(name, details, terminal: terminal)
        case (11, 16):
            guard Set(fields.keys).isSubset(of: [1, 2, 3, 4]), let id = number(fields, 1), id != 0, let container = definition.containers[UInt32(id)],
                  let name = string(fields, 2, max: 15), fields[2] == nil || name == container.name,
                  let eventID = number(fields, 3), let value = number(fields, 4) else { return nil }
            return input("privateEvent", ["containerId": id, "containerName": container.name, "eventId": eventID, "eventData": value])
        case (14, 17):
            guard Set(fields.keys).isSubset(of: [1, 2]), let command = number(fields, 1), command <= 1, let value = number(fields, 2) else { return nil }
            return input(command == 0 ? "timeoutPageEnter" : "timeoutPageExit", ["value": value])
        case (17, 20):
            guard Set(fields.keys).isSubset(of: [1]), number(fields, 1) == UInt64(definition.widget) else { return nil }
            return input("menuStartup", ["widgetId": definition.widget])
        case (21, 24):
            guard Set(fields.keys).isSubset(of: [1]), let id = number(fields, 1), let subitems = definition.menu[UInt32(id)], subitems.isEmpty else { return nil }
            return input("menuItemClick", ["itemId": id])
        case (22, 25):
            guard Set(fields.keys).isSubset(of: [1, 2, 3]), let parent = number(fields, 1), let sub = number(fields, 2), let index = number(fields, 3),
                  let children = definition.menu[UInt32(parent)], index >= 1, index <= UInt64(children.count),
                  children[Int(index) - 1] == UInt32(sub) else { return nil }
            return input("menuSubitemSwitch", ["parentItemId": parent, "subItemId": sub, "subItemIndex": index])
        default: return nil
        }
    }
    private static func number(_ f: Fields, _ key: UInt64) -> UInt64? {
        guard let field = f[key] else { return 0 }
        guard let n = field.number, n <= UInt64(UInt32.max) else { return nil }; return n
    }
    private static func string(_ f: Fields, _ key: UInt64, max: Int) -> String? {
        guard let field = f[key] else { return "" }
        guard let b = field.data, b.count <= max, !b.contains(0) else { return nil }
        return String(bytes: b, encoding: .utf8)
    }
    private static func eventName(_ n: UInt64) -> String? {
        [0: "click", 1: "scrollUp", 2: "scrollDown", 3: "doubleClick", 4: "foregroundEnter",
         5: "foregroundExit", 6: "abnormalExit", 7: "exit", 8: "imuData", 9: "longPress", 10: "longPressRelease"][n]
    }
}
