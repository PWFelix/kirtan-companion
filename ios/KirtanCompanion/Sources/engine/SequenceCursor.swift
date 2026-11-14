import Foundation

/// One entry of a playing sequence: a beat, held for `bars` bars.
struct SequenceStep {
    let beat: Beat
    let bars: Int
}

/// Where a playing sequence is, counted in DOWNBEATS.
///
/// Pure and audio-thread-owned: `KirtanEngine.render` calls `onDownbeat()` at
/// every bar line it plays, and the answer says whether that 1 still belongs to
/// the current entry, starts the next one, or is past the end. Kept out of the
/// render loop so the counting can be tested without a clock or a device.
///
/// The first downbeat after `reset()` is bar 1 of entry 0 and is not an advance.
final class SequenceCursor {

    enum Outcome: Equatable {
        /// Same entry, next bar.
        case stay
        /// This 1 starts entry `index` — switch the beat here.
        case switchTo(Int)
        /// The last entry is done and loop is off: stop on this 1, unplayed.
        case end
    }

    private let bars: [Int]
    var loop: Bool
    private(set) var index = 0
    /// 0-based bar within the current entry.
    private(set) var bar = 0
    private var started = false

    init(bars: [Int], loop: Bool) {
        self.bars = bars.map { max(1, $0) }
        self.loop = loop
    }

    func reset() {
        index = 0; bar = 0; started = false
    }

    func onDownbeat() -> Outcome {
        guard !bars.isEmpty else { return .end }
        if !started {
            started = true
            return .stay
        }
        bar += 1
        if bar < bars[index] { return .stay }
        bar = 0
        let next = index + 1
        if next < bars.count { index = next; return .switchTo(next) }
        if loop { index = 0; return .switchTo(0) }
        return .end
    }

    /// Carry the position into an edited sequence (bars changed, entries added or
    /// removed mid-play): same entry and bar where they still exist, else the top.
    func adopt(_ from: SequenceCursor) {
        started = from.started
        if from.index < bars.count {
            index = from.index
            bar = min(from.bar, bars[index] - 1)
        }
    }
}
