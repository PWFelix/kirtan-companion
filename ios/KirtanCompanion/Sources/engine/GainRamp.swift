import Foundation

// A linear gain ramp, advanced once per rendered frame.
//
// Exists because the web engine expresses every volume change as
// `gain.rampTo(target, 0.05)` — Web Audio's linearRampToValueAtTime. Setting a
// gain abruptly produces a step in the waveform, which on a drum is a click; and
// smoothing with a one-pole filter would reach the target exponentially, so a
// slider move would land at a different time on iOS than on the web. The master
// fader and every end channel use this, so the two can't drift apart.
//
// The target is published from the UI thread; advance runs only on the audio
// thread. A small lock snapshots target+step at block boundaries (the DSP loop
// itself is lock-free) so a slider can never tear a value mid-block.

final class GainRamp {

    private let lock = NSLock()
    private var sampleRate: Double
    private var seconds: Double

    private var value: Float
    private var target: Float
    private var step: Float

    init(sampleRate: Double, seconds: Double = 0.05, initial: Float = 1) {
        self.sampleRate = sampleRate
        self.seconds = seconds
        self.value = initial
        self.target = initial
        self.step = 0
    }

    /// The gain currently in force, mid-ramp values included.
    var gain: Float {
        lock.lock(); defer { lock.unlock() }
        return value
    }

    /// Point the ramp at a new gain. Pairs with [retarget]; the two are usually
    /// called back to back from the owner's setter.
    func setTarget(_ value: Float) {
        lock.lock(); defer { lock.unlock() }
        target = value
    }

    /// Recompute the per-frame increment from the current value toward the target.
    /// Called from the owner's setter — never from the audio thread's inner loop.
    func retarget() {
        lock.lock(); defer { lock.unlock() }
        let frames = (seconds * sampleRate)
        step = frames > 0 ? (target - value) / Float(frames) : 0
    }

    /// Multiply `count` frames of `buffer` by the ramping gain, advancing it.
    /// Snaps to the target on overshoot so the ramp can never oscillate around
    /// it, and fills the rest of the block at the settled value.
    func applyTo(_ buffer: inout [Float], count: Int) {
        lock.lock()
        let g0 = value, s = step, t = target
        lock.unlock()

        if s == 0 {
            // Settled. Still a loop: a muted channel sits at exactly 0 and must
            // silence the block.
            if g0 != 1 {
                for i in 0..<count { buffer[i] *= g0 }
            }
            return
        }

        var g = g0
        for i in 0..<count {
            buffer[i] *= g
            g += s
            if (s > 0 && g >= t) || (s < 0 && g <= t) {
                g = t
                step = 0
                for j in (i + 1)..<count { buffer[j] *= t }
                lock.lock(); value = t; lock.unlock()
                return
            }
        }
        lock.lock(); value = g; lock.unlock()
    }
}
