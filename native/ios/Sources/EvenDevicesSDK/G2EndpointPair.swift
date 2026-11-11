import Foundation

/// Exact host-owned binding identities. They locate peripherals, but never grant
/// authorization: both temples still verify the proof for the requested SN.
public struct G2EndpointPair: Equatable {
    public let left: UUID
    public let right: UUID

    public init?(left: UUID, right: UUID) {
        guard left != right else { return nil }
        self.left = left
        self.right = right
    }

    /// A system-connected temple may stop advertising. Explicit identities can
    /// complete that pair, but cannot contradict an observed SN or temple side.
    static func select(serialNumber: String, advertised: [String: [G2Side: UUID]],
                       preferred: G2EndpointPair?) -> G2EndpointPair? {
        if let preferred {
            for (serial, endpoints) in advertised {
                for (side, id) in endpoints {
                    if id == preferred.left && (serial != serialNumber || side != .left) { return nil }
                    if id == preferred.right && (serial != serialNumber || side != .right) { return nil }
                }
            }
            return preferred
        }
        guard let pair = advertised[serialNumber],
              let left = pair[.left], let right = pair[.right] else { return nil }
        return G2EndpointPair(left: left, right: right)
    }
}
