package com.evenrealities.devices

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.log10
import kotlin.math.roundToInt

/** Session-owned PCM16-LE AGC, matching the App's default Dart enhancement stage.
 * Frame processing also ports the SSR fixed-mode logic; classifications are algorithmic, not identity.
 * Call only from the audio owner's serialized lane; reset at every owner boundary.
 */
internal class G2AudioPcmProcessor {
    private var smoothGain = 1.0

    /** Reject malformed PCM rather than shifting sample alignment into later frames. */
    fun process(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0) { "PCM16 requires an even byte length" }
        val output = ByteArray(pcm.size)
        val samples = pcm.size / 2
        var start = 0
        while (start < samples) {
            val end = min(start + 160, samples)
            var peak = 0.0
            for (i in start until end) peak = maxOf(peak, abs(sample(pcm, i).toDouble()))
            val gain = if (peak > 0) min(1.0 / peak, 10.0) else 1.0
            smoothGain = .95 * smoothGain + .05 * gain
            if (peak > 0) smoothGain = min(smoothGain, 1.0 / peak)
            for (i in start until end) {
                // Dart stores the multiplied samples in Float32 before clipping/rounding.
                val enhanced = (sample(pcm, i) * smoothGain).toFloat().toDouble().coerceIn(-1.0, 1.0)
                val value = (enhanced * 32767.0).let {
                    if (it < 0) -(-it).roundToInt() else it.roundToInt()
                }.coerceIn(-32768, 32767)
                output[i * 2] = value.toByte()
                output[i * 2 + 1] = (value shr 8).toByte()
            }
            start = end
        }
        return output
    }

    private var previousPcm: ByteArray? = null
    private var selfStates = IntArray(3)
    private var otherStates = IntArray(3)
    private var otherMean = Float.fromBits(0x4170d2f1)
    private var lastMode = 0

    /** The App smoother delays PCM by one frame while retaining the input direction tag.
     * Fixed-mode flags from ssrsmoother 963982a; thresholds match the pinned
     * App flutter_ezw_audio f7402a0b binary oracle (2026-11-10), not source HEAD.
     * Unknown energy leaves enhanced PCM undelayed and cannot assert a speaker role.
     */
    data class Frame(val audioPcm: ByteArray, val direction: Int?, val speakerRole: String)
    fun processFrame(pcm: ByteArray, energyDiff: Int?, direction: Int?): Frame {
        require(energyDiff == null || energyDiff in 0..65535) { "energyDiff must be uint16" }
        val enhanced = process(pcm)
        if (energyDiff == null || enhanced.isEmpty()) return Frame(enhanced, direction, "unknown")
        val db = (10f * log10(maxOf(energyDiff.toFloat(), 1e-6f)))
        if (energyDiff > 42) selfStates.fill(1)
        val residual = kotlin.math.abs(db - otherMean)
        val scaled = residual / 20f
        val alpha = .8f / (1f + scaled * scaled)
        otherMean = (1f - alpha) * db + alpha * otherMean
        otherStates[1] = if (otherMean < Float.fromBits(0x4170d2f1)) 1 else 0
        val self = selfStates[0]
        val other = otherStates[0]
        if (self == 1) lastMode = 1 else if (other == 1) lastMode = 0
        selfStates = intArrayOf(selfStates[1], selfStates[2], 0)
        otherStates = intArrayOf(otherStates[1], otherStates[2], 0)
        val delayed = previousPcm ?: ByteArray(enhanced.size)
        previousPcm = enhanced
        // App normalizes mode=other by complementing each serialized PCM byte.
        val normalized = if (lastMode == 0) ByteArray(delayed.size) { delayed[it].toInt().inv().toByte() } else delayed
        return Frame(normalized, direction, if (lastMode == 1) "self" else "other")
    }

    /** Discard gain history so a new microphone owner cannot inherit old signal state. */
    fun reset() {
        smoothGain = 1.0
        previousPcm = null
        selfStates = IntArray(3)
        otherStates = IntArray(3)
        otherMean = Float.fromBits(0x4170d2f1)
        lastMode = 0
    }

    private fun sample(pcm: ByteArray, index: Int): Float =
        ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
}
