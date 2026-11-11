import Foundation

/// Ported from the latest BLE invalidParameters policy. CoreBluetooth Code 1
/// means this endpoint needs a delayed new attempt, not a hot reconnect loop.
struct G2ReconnectBackoff {
    private(set) var failureCount = 0

    static func isInvalidParameters(_ error: Error?) -> Bool {
        let value = error as NSError?
        return value?.domain == "CBErrorDomain" && value?.code == 1
    }

    mutating func registerInvalidParameters() -> TimeInterval {
        failureCount = min(failureCount + 1, 6)
        return [1, 2, 4, 8, 16, 30][failureCount - 1]
    }

    mutating func businessConnected() { failureCount = 0 }
}
