import Foundation
@_implementationOnly import EvenLC3

/// A microphone lease owns predictive LC3 state; recreating it prevents audio
/// history from crossing page, endpoint, or enable/disable boundaries.
final class G2AudioDecoder {
    private let processor = G2AudioPcmProcessor()
    private let handle: UnsafeMutableRawPointer
    init?() {
        guard let handle = even_lc3_create() else { return nil }
        self.handle = handle
    }
    deinit { even_lc3_destroy(handle) }

    /// Tagged firmware packets carry exactly five frames. Untagged STREAM
    /// accepts complete 40-byte frames within the 512-byte GATT value limit.
    /// Validate before mutating predictive state; slices may have nonzero indices.
    static func encodedCount(_ count: Int) -> Int? {
        if count == 204 || count == 205 { return 200 }
        return count > 0 && count <= 480 && count.isMultiple(of: 40) ? count : nil
    }
    func decode(_ packet: Data) -> G2AudioPcmProcessor.Frame? {
        guard let count = Self.encodedCount(packet.count) else { return nil }
        let bytes = Array(packet)
        let encoded = Array(bytes.prefix(count))
        var output = [UInt8](repeating: 0, count: count / 40 * 320)
        for index in 0..<(count / 40) {
            let status = encoded.withUnsafeBufferPointer { source in
                output.withUnsafeMutableBufferPointer { target in
                    even_lc3_decode(handle, source.baseAddress! + index * 40, 40,
                                    target.baseAddress! + index * 320)
                }
            }
            guard status >= 0 else { return nil }
        }
        let energy = packet.count == 204 || packet.count == 205 ? Int(UInt16(bytes[200]) | UInt16(bytes[201]) << 8) : nil
        let direction = packet.count == 204 || packet.count == 205
            ? Int(Int16(bitPattern: UInt16(bytes[202]) | UInt16(bytes[203]) << 8)) : nil
        return processor.processFrame(Data(output), energyDiff: energy, direction: direction)
    }
}
