import Foundation
import XCTest

// Engine tests for the PURE DSP — the deterministic parts a host can reason about.
// The engine sources are compiled into this test module (see project.pbxproj), so the
// types need no import. We deliberately do NOT start an AVAudioEngine: no live output in
// a unit test, only the signal-path math that KirtanEngine runs on the audio thread.

final class EngineTests: XCTestCase {
    // 48 kHz — the engine's fixed render rate (AudioDeviceConfig lives in playback/, which
    // the test target doesn't compile; the value is a project constant, safe to restate).
    let sr = 48_000

    // MARK: GainRamp

    func testGainRampRunsToTargetThenSettles() {
        let g = GainRamp(sampleRate: Double(sr), seconds: 0.05, initial: 1)
        g.setTarget(0.3)
        g.retarget()
        var buf = [Float](repeating: 1.0, count: sr) // a whole second
        g.applyTo(&buf, count: sr)
        XCTAssertEqual(Double(g.gain), 0.3, accuracy: 1e-4, "settles exactly on the target")
        XCTAssertEqual(Double(buf.last ?? -1), 0.3, accuracy: 1e-4, "late frames at the settled value")
        XCTAssertEqual(Double(buf.first ?? 0), 1.0, accuracy: 1e-3, "starts at the old value")
    }

    func testGainRampIsAWireWhenUnchanged() {
        let g = GainRamp(sampleRate: Double(sr), initial: 1)
        var buf = [Float](repeating: 0.5, count: 128)
        g.applyTo(&buf, count: buf.count)
        XCTAssertEqual(Double(buf.first ?? 0), 0.5, accuracy: 1e-6)
    }

    func testGainRampMuteSilencesTheBlock() {
        let g = GainRamp(sampleRate: Double(sr), initial: 1)
        g.setTarget(0); g.retarget()
        var buf = [Float](repeating: 0.9, count: sr)
        g.applyTo(&buf, count: sr)
        XCTAssertEqual(Double(buf.last ?? 1), 0, accuracy: 1e-6)
    }

    // MARK: BiquadFilter

    func testBiquadZeroDbIsAWire() {
        // A peaking band at 0 dB collapses to H(z) = 1 (feedforward = feedback), so it
        // must reproduce any input exactly — the contract a saved preset relies on.
        let f = BiquadFilter()
        f.configure(EQ_BANDS[2], sampleRate: Double(sr)) // 1 kHz peaking
        f.setGain(0)
        var buf = (0..<2048).map { Float($0 % 9) * 0.1 - 0.4 }
        let inCopy = buf
        f.process(&buf, offset: 0, count: buf.count)
        for i in 0..<buf.count {
            XCTAssertEqual(buf[i], inCopy[i], accuracy: 1e-4)
        }
    }

    func testBiquadPeakingStaysBounded() {
        let f = BiquadFilter()
        f.configure(EQ_BANDS[2], sampleRate: Double(sr))
        f.setGain(12)
        var peak = 0.0
        var buf = [Float](repeating: 0, count: sr)
        for i in 0..<sr {
            buf[i] = Float(sin(2 * .pi * 1000 * Double(i) / Double(sr)))
        }
        f.process(&buf, offset: 0, count: buf.count)
        for x in buf { peak = max(peak, Double(abs(x))) }
        // A +12 dB peaking filter at its centre frequency applies exactly 12 dB
        // (10^(12/20) = 3.98x) to a resonant sine - so the peak is that gain, not a runaway.
        XCTAssertEqual(peak, pow(10.0, 12.0 / 20.0), accuracy: 0.2)
    }

    // MARK: SoftClip

    func testSoftClipBoundsAndKneeContinuity() {
        for x in [-3.0, -1.2, -1.0, -0.8, -0.5, 0.0, 0.5, 0.8, 0.99, 1.0, 1.5, 3.0] {
            let y = softClip(Float(x))
            XCTAssertLessThanOrEqual(abs(y), 1.0 + 1e-6, "bounded at \(x)")
        }
        XCTAssertEqual(Double(softClip(0.5)), 0.5, accuracy: 1e-6, "below the knee is unity")
        XCTAssertEqual(Double(softClip(0)), 0, accuracy: 1e-9)
        // C¹ at the knee: slope ~1 on both sides.
        let lo = Double(softClip(0.7999)), hi = Double(softClip(0.8001))
        XCTAssertLessThan(abs((hi - lo) / 0.0002 - 1.0), 0.05)
    }

    // MARK: MusicalClock

    func testMusicalClockFramesPerTick() {
        let c = MusicalClock(sampleRate: sr)
        c.setBpm(120, atFrame: 0)
        c.setMeter(steps: 8, beatsPerBar: 4)
        // 60 * 48000 / (192 * 120) = 125 frames per tick.
        XCTAssertEqual(c.snapshot().framesPerTick, 125.0, accuracy: 1e-6)
    }

    func testMusicalClockStepDerivesFromPhase() {
        let c = MusicalClock(sampleRate: sr)
        c.setBpm(120, atFrame: 0)
        c.setMeter(steps: 8, beatsPerBar: 4)
        c.start(atFrame: 0)
        // one step = 96 ticks * 125 = 12000 frames
        XCTAssertEqual(c.stepAt(0), 0)
        XCTAssertEqual(c.stepAt(11_999), 0)
        XCTAssertEqual(c.stepAt(12_000), 1)
        XCTAssertEqual(c.stepAt(23_999), 1)
        XCTAssertEqual(c.stepAt(24_000), 2)
    }

    func testMusicalClockWrapsAtBarTop() {
        let c = MusicalClock(sampleRate: sr)
        c.setBpm(120, atFrame: 0)
        c.setMeter(steps: 8, beatsPerBar: 4)
        c.start(atFrame: 0)
        // one bar = 768 ticks * 125 = 96000 frames
        XCTAssertEqual(c.stepAt(95_999), 7)
        XCTAssertEqual(c.stepAt(96_000), 0, "the bar restarts the step counter")
        XCTAssertEqual(c.phaseAt(96_000), 0.0, accuracy: 1e-6)
    }

    func testMusicalClockStopZerosPhase() {
        let c = MusicalClock(sampleRate: sr)
        c.setBpm(120, atFrame: 0)
        c.start(atFrame: 0)
        c.stop()
        XCTAssertEqual(c.phaseAt(100_000), 0, "a stopped clock reads bar phase 0")
    }

    func testMusicalClockBpmChangeReAnchors() {
        let c = MusicalClock(sampleRate: sr)
        c.setBpm(120, atFrame: 0)
        c.setMeter(steps: 8, beatsPerBar: 4)
        c.start(atFrame: 0)
        // part-way into the bar, double the tempo. The position must not jump.
        let before = c.phaseAt(12_000)
        c.setBpm(240, atFrame: 12_000)
        let after = c.phaseAt(12_000)
        XCTAssertEqual(before, after, accuracy: 1e-9, "re-anchoring keeps the current position")
        c.stop()
    }

    // MARK: PcmSample

    func testPcmSampleDurationsAndSilence() {
        let s = PcmSample(data: [Float](repeating: 0.1, count: 480), sampleRate: sr)
        XCTAssertEqual(s.frameCount, 480)
        XCTAssertEqual(s.durationSeconds, 0.01, accuracy: 1e-6)
        let sil = PcmSample.silence(sr)
        XCTAssertEqual(sil.frameCount, 0)
    }
}
