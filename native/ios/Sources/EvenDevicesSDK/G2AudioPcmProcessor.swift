import Foundation

/// Session-owned PCM16-LE AGC matching the App's default Dart enhancement stage.
/// Frame processing also ports SSR fixed-mode logic; roles are algorithmic, not identity.
/// The audio owner must serialize calls and reset gain at every owner boundary.
final class G2AudioPcmProcessor {
    private var smoothGain = 1.0

    /// Invalid sample alignment is rejected without consuming gain history.
    func process(_ pcm: Data) -> Data? {
        guard pcm.count.isMultiple(of: 2) else { return nil }
        let input = [UInt8](pcm)
        var output = [UInt8](repeating: 0, count: input.count)
        func sample(_ index: Int) -> Float {
            Float(Int16(bitPattern: UInt16(input[index * 2]) | UInt16(input[index * 2 + 1]) << 8)) / 32768
        }
        var start = 0
        while start < input.count / 2 {
            let end = min(start + 160, input.count / 2)
            var peak = 0.0
            for index in start..<end { peak = max(peak, abs(Double(sample(index)))) }
            let gain = peak > 0 ? min(1 / peak, 10) : 1
            smoothGain = 0.95 * smoothGain + 0.05 * gain
            if peak > 0 { smoothGain = min(smoothGain, 1 / peak) }
            for index in start..<end {
                // Preserve Dart's Float32 intermediate before PCM16 rounding.
                let enhanced = Double(Float(Double(sample(index)) * smoothGain))
                let value = Int16(max(-32768, min(32767, (max(-1, min(1, enhanced)) * 32767).rounded())))
                let bits = UInt16(bitPattern: value)
                output[index * 2] = UInt8(truncatingIfNeeded: bits)
                output[index * 2 + 1] = UInt8(truncatingIfNeeded: bits >> 8)
            }
            start = end
        }
        return Data(output)
    }

    private var previousPcm: Data?
    private var selfStates = [0, 0, 0]
    private var otherStates = [0, 0, 0]
    private var otherMean = Float(bitPattern: 0x4170d2f1)
    private var lastMode = 0

    struct Frame {
        let audioPcm: Data
        let direction: Int?
        let speakerRole: String
    }

    /// Match the App's frame-mode smoother, including its one-frame PCM delay
    /// and current-input direction tag. Ported from ssrsmoother 963982a fixed-mode
    /// three-frame flags; thresholds match the App f7402a0b binary oracle (2026-11-10),
    /// not source HEAD. Missing SSR evidence cannot imply identity.
    func processFrame(_ pcm: Data, energyDiff: Int?, direction: Int?) -> Frame? {
        if let energyDiff = energyDiff, !(0...65535).contains(energyDiff) { return nil }
        guard let enhanced = process(pcm) else { return nil }
        guard let energyDiff = energyDiff, !enhanced.isEmpty else {
            return Frame(audioPcm: enhanced, direction: direction, speakerRole: "unknown")
        }
        let db = 10 * log10(max(Float(energyDiff), 1e-6))
        if energyDiff > 42 { selfStates = [1, 1, 1] }
        let residual = abs(db - otherMean)
        let scaled = residual / 20
        let alpha = Float(0.8) / (1 + scaled * scaled)
        otherMean = (1 - alpha) * db + alpha * otherMean
        otherStates[1] = otherMean < Float(bitPattern: 0x4170d2f1) ? 1 : 0
        if selfStates[0] == 1 { lastMode = 1 } else if otherStates[0] == 1 { lastMode = 0 }
        selfStates = [selfStates[1], selfStates[2], 0]
        otherStates = [otherStates[1], otherStates[2], 0]
        let delayed = previousPcm ?? Data(repeating: 0, count: enhanced.count)
        previousPcm = enhanced
        // Match AudioQualityProxy.normalizeSmootherPcm(mode: 0).
        let normalized = lastMode == 0 ? Data(delayed.map { ~$0 }) : delayed
        return Frame(audioPcm: normalized, direction: direction, speakerRole: lastMode == 1 ? "self" : "other")
    }

    /// New owners start with neutral gain instead of inheriting another session's history.
    func reset() {
        smoothGain = 1
        previousPcm = nil
        selfStates = [0, 0, 0]
        otherStates = [0, 0, 0]
        otherMean = Float(bitPattern: 0x4170d2f1)
        lastMode = 0
    }
}
