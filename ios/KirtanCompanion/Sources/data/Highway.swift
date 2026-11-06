import Foundation

/// The geometry of the Learn "highway": which strokes are on screen, and where.
///
/// A port of Android's `Highway.kt`. Pure (no SwiftUI, no audio) so it can be unit
/// tested and the Canvas that draws it only paints. Everything is in STEPS relative to
/// the playhead: `rel = 0` is sounding now and sits on the hit line; `rel = 3` sounds
/// three cells from now, three step-heights above it.
///
/// Position comes from the engine's bar phase (`getPhase() × steps`), which is already
/// backed off by output latency — a continuous, drift-free playhead. Four columns, one
/// per hand and stroke, laid out as the drum sits before a player: bayan (left hand)
/// on the left, dayan on the right, open strokes on the outside. Kartal is left off —
/// it is the congregation's part, not the mridanga player's.
enum HighwayColumn: Int, CaseIterable {
    case bayanOpen, bayanClosed, dayanClosed, dayanOpen

    var lane: LaneId {
        switch self {
        case .bayanOpen, .bayanClosed: .bayan
        case .dayanOpen, .dayanClosed: .dayan
        }
    }

    var stroke: Stroke {
        switch self {
        case .bayanOpen, .dayanOpen: .open
        case .bayanClosed, .dayanClosed: .closed
        }
    }

    /// The spoken syllable for this stroke — the column's heading.
    var bol: String { Bols.forStroke(laneId: lane.wireId, code: stroke.rawValue) ?? "" }

    static func of(_ lane: LaneId, _ stroke: Stroke) -> HighwayColumn? {
        allCases.first { $0.lane == lane && $0.stroke == stroke }
    }
}

/// One falling note. `step` is its index in the beat, `rel` its distance in steps.
struct HighwayNote: Equatable {
    let column: HighwayColumn
    let step: Int
    let rel: Double
}

/// A horizontal guide at a beat (group) boundary; `sam` marks the bar's first.
struct HighwayLine: Equatable {
    let rel: Double
    let sam: Bool
}

struct HighwayFrame {
    let notes: [HighwayNote]
    let lines: [HighwayLine]
    /// The step under the hit line.
    let currentStep: Int
}

enum Highway {
    /// How much time the screen shows ahead of the hit line.
    static let lookaheadSeconds = 1.8
    /// How far past the hit line a note keeps drawing (fading) before it goes.
    static let trailSteps = 1.0

    /// Steps that fit in the lookahead at this tempo — time-based, so notes fall at
    /// a readable speed whatever the beat. Never fewer than four.
    static func lookaheadSteps(bpm: Int, cellsPerQuarter: Int) -> Double {
        max(4.0, lookaheadSeconds * Double(bpm) * Double(cellsPerQuarter) / 60.0)
    }

    /// Notes and beat lines visible with the playhead at `position` steps. The beat
    /// loops, so the window wraps past the end of the pattern into the next bar.
    static func frame(_ beat: Beat, position: Double, lookahead: Double) -> HighwayFrame {
        let steps = beat.steps
        guard steps > 0 else { return HighwayFrame(notes: [], lines: [], currentStep: 0) }
        let now = Int(position.rounded(.down))
        let first = Int((position - trailSteps).rounded(.down))
        let last = Int((position + lookahead).rounded(.down)) + 1
        let boundaries = groupBoundaries(beat)

        var notes: [HighwayNote] = []
        var lines: [HighwayLine] = []
        for abs in first...last {
            let step = floorMod(abs, steps)
            let rel = Double(abs) - position
            for lane in [LaneId.bayan, .dayan] {
                guard let pattern = beat.pattern(lane), step < pattern.count,
                      let stroke = pattern[step],
                      let column = HighwayColumn.of(lane, stroke) else { continue }
                notes.append(HighwayNote(column: column, step: step, rel: rel))
            }
            if boundaries.contains(step) { lines.append(HighwayLine(rel: rel, sam: step == 0)) }
        }
        return HighwayFrame(notes: notes, lines: lines, currentStep: floorMod(now, steps))
    }

    /// The bol spoken at `step`: a two-hand combination if both heads strike.
    static func bolAt(_ beat: Beat, step: Int) -> String? {
        func code(_ lane: LaneId) -> String? {
            guard let p = beat.pattern(lane), step < p.count else { return nil }
            return p[step]?.rawValue
        }
        let d = code(.dayan), b = code(.bayan)
        return Bols.combo(dayanCode: d, bayanCode: b)
            ?? Bols.forStroke(laneId: LaneId.dayan.wireId, code: d)
            ?? Bols.forStroke(laneId: LaneId.bayan.wireId, code: b)
    }

    /// The step index at which each group (beat) of the bar begins.
    static func groupBoundaries(_ beat: Beat) -> Set<Int> {
        var out = Set<Int>()
        var at = 0
        for g in groupsFor(beat) {
            if at < beat.steps { out.insert(at) }
            at += g
        }
        if out.isEmpty { out.insert(0) }
        return out
    }

    private static func floorMod(_ a: Int, _ n: Int) -> Int { ((a % n) + n) % n }
}
