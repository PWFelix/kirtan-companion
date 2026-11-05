import Foundation
import XCTest

// The Feel humanizer and the two engine changes that carry it (a per-voice gain,
// and a start delay longer than one block). Mirrors the Android HumanizerTest.

final class HumanizerTests: XCTestCase {
    let sr = 48_000

    private func beat() -> Beat {
        Beat(
            id: "test", name: "Test Beat", note: "", bpm: 90, steps: 8,
            beatsPerBar: 4, cellsPerGroup: 2, groups: nil, description: nil,
            lanePatterns: [
                .dayan: [.closed, nil, .open, .open, .closed, nil, .open, .open],
                .bayan: [.open, nil, nil, .closed, nil, .open, .open, nil],
            ]
        )
    }

    private func humanizer(_ feel: Feel) -> Humanizer {
        let h = Humanizer(sampleRate: sr)
        h.setBeat(beat())
        h.feel = feel
        h.reset()
        return h
    }

    private func run(_ h: Humanizer, steps: Int = 64) -> ([Int], [Float]) {
        var delays: [Int] = [], gains: [Float] = []
        for i in 0..<steps {
            h.advance()
            for lane in LaneId.ordered {
                delays.append(h.delayFrames(lane, step: i % 8))
                gains.append(h.gain(step: i % 8))
            }
        }
        return (delays, gains)
    }

    func testOffChangesNothing() {
        let h = humanizer(.off)
        let (d, g) = run(h)
        XCTAssertTrue(d.allSatisfy { $0 == 0 })
        XCTAssertTrue(g.allSatisfy { $0 == 1 })
        XCTAssertEqual(h.padFrames(), 0)
    }

    func testDelaysStayInsideTheWindow() {
        let max = Int((Humanizer.PAD_MS + Humanizer.MAX_LATE_MS) * Double(sr) / 1000) + 1
        for feel in Feel.allCases {
            let (d, _) = run(humanizer(feel), steps: 2_000)
            XCTAssertTrue(d.allSatisfy { (0...max).contains($0) }, "\(feel)")
        }
    }

    func testGainStaysWithinAFewDb() {
        let (_, g) = run(humanizer(.loose), steps: 2_000)
        XCTAssertTrue(g.allSatisfy { $0 > 0.25 && $0 < 1.9 })
    }

    func testBayanLandsAfterDayan() {
        let h = humanizer(.human)
        var dayan = 0, bayan = 0
        for i in 0..<2_000 {
            h.advance()
            dayan += h.delayFrames(.dayan, step: i % 8)
            bayan += h.delayFrames(.bayan, step: i % 8)
        }
        let lagMs = Double(bayan - dayan) / 2_000 / Double(sr) * 1000
        XCTAssertTrue((7.0...14.0).contains(lagMs), "lag was \(lagMs) ms")
    }

    func testDownbeatIsLoudestOnAverage() {
        let h = humanizer(.loose)
        var sums = [Double](repeating: 0, count: 8)
        for i in 0..<4_000 { sums[i % 8] += Double(h.gain(step: i % 8)) }
        XCTAssertTrue(sums.dropFirst().allSatisfy { sums[0] > $0 })
    }

    func testSameBeatSameFeelEveryStart() {
        let h = humanizer(.human)
        let first = run(h)
        h.reset()
        let second = run(h)
        XCTAssertEqual(first.0, second.0)
        XCTAssertEqual(first.1, second.1)
    }

    func testVoiceCanWaitLongerThanABlock() {
        let sample = PcmSample(data: [Float](repeating: 1, count: 1_000), sampleRate: sr)
        var voice = Voice(sample, lane: .dayan, startOffset: 300, gain: 0.5)
        var out = [Float](repeating: 0, count: 256)
        voice.render(into: &out, count: 256, rate: 1)
        XCTAssertTrue(out.allSatisfy { $0 == 0 }, "silent through the first block")
        voice.render(into: &out, count: 256, rate: 1)
        XCTAssertEqual(out[43], 0)
        XCTAssertEqual(out[44], 0.5)
    }

    func testFeelRoundTripsThroughPrefs() {
        var s = EqPrefsSnapshot.default
        s.feel = .human
        XCTAssertEqual(EqPrefsCodec.decode(EqPrefsCodec.encode(s)).feel, .human)
        XCTAssertEqual(EqPrefsCodec.decode(#"{"feel":"wobbly"}"#).feel, .off)
        XCTAssertEqual(EqPrefsCodec.decode(#"{"feel":3}"#).feel, .off)
    }
}
