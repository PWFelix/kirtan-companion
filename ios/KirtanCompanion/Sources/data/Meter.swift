// The pure meter + tempo facts, with no UI and no audio behind them.
//
// Ported from src/data/meter.js. WHY THIS FILE EXISTS: the built-in beats predate the
// groups model. They carry steps/beatsPerBar/cellsPerGroup but no groups list, while
// editor-made beats carry groups and treat it as the truth. Anything that needs a
// beat's real meter — the editor when it opens one, the share codec when it packs one —
// has to RECONSTRUCT the missing half, and that reconstruction is subtle enough (see
// cpqFor) that it should exist once, not once per caller.
//
// Two vocabularies, easy to confuse:
//   groups  one entry per NUMBERED beat, its value = cells it spans. 7/8 is [2,2,3].
//   cpq     cells per QUARTER NOTE — the subdivision density. Sets timing:
//           beatsPerBar = steps / cpq, and the transport turns that into the interval.
//
// These coincide for 4/4-in-eighths ([2,2,2,2], cpq 2) and diverge the moment a beat is
// grouped in threes: 6/8 is groups [3,3] with cpq 2.

/// Tempo bounds. They live here rather than in the transport ViewModel so pure data
/// modules can clamp a BPM without dragging in SwiftUI or the audio engine.
let MIN_BPM = 40
let MAX_BPM = 500

/// A fresh transport's tempo before any beat is loaded. `loadBeat` adopts the
/// selected beat's own tempo on first selection, so this is only a placeholder.
let DEFAULT_BPM = 120

func clampBpm(_ bpm: Int) -> Int { min(max(bpm, MIN_BPM), MAX_BPM) }

// Brahmaloka time. The tempo slider normally spans MIN_BPM...NORMAL_MAX_BPM, so the
// tempos people actually chant at get real resolution under a thumb. Holding it at 200
// for BRAHMA_HOLD_SECONDS unlocks the high range (BRAHMA_FLOOR_BPM...MAX_BPM); going
// below 200 locks it again. The floor sits just under 200 so the same slider can drag
// you back out. Same rules as the web's data/meter.js and Android's Meter.kt.
let NORMAL_MAX_BPM = 200
let BRAHMA_FLOOR_BPM = 190
let BRAHMA_HOLD_SECONDS = 0.9

/// The slider's range for the current mode.
func tempoRange(brahmaloka: Bool) -> ClosedRange<Int> {
    brahmaloka ? BRAHMA_FLOOR_BPM...MAX_BPM : MIN_BPM...NORMAL_MAX_BPM
}

/// The mode a BPM puts you in, given the mode you were in: below 200 is always the
/// normal range, above 200 always Brahmaloka (a loaded fast beat must be reachable on
/// the slider), and exactly 200 keeps whatever you had.
func brahmalokaFor(bpm: Int, was: Bool) -> Bool {
    if bpm < NORMAL_MAX_BPM { return false }
    if bpm > NORMAL_MAX_BPM { return true }
    return was
}

/// A tempo restored at launch: every launch starts outside Brahmaloka.
func restoredBpm(_ bpm: Int) -> Int { min(max(bpm, MIN_BPM), NORMAL_MAX_BPM) }

func sumGroups(_ groups: [Int]) -> Int { groups.reduce(0, +) }

/// A beat's groups — stored if it has them, reconstructed if it doesn't.
func groupsFor(_ beat: Beat) -> [Int] {
    if let groups = beat.groups { return groups }
    let cpg = beat.cellsPerGroup > 0
        ? beat.cellsPerGroup
        : max(1, Int((Double(beat.steps) / beat.beatsPerBar).rounded()))
    let beats = max(1, Int((Double(beat.steps) / Double(cpg)).rounded()))
    return Array(repeating: cpg, count: beats)
}

/// A beat's cells-per-quarter. Derived from the TIMING fields, never from
/// cellsPerGroup — for 6/8 the editor saves cellsPerGroup 3 while cpq is 2, so reading
/// cellsPerGroup here would make the beat play half again too fast.
func cpqFor(_ beat: Beat) -> Int {
    let bar = beat.beatsPerBar > 0 ? beat.beatsPerBar : 4.0
    return max(1, Int((Double(beat.steps) / bar).rounded()))
}

extension Comparable {
    /// Clamp into a closed range. Used by the transport on the way in, so the UI
    /// state, the persisted prefs and the engine's DSP agree on what is legal.
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
