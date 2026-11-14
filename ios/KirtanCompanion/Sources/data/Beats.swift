import Foundation

// Pure beat data. No logic, no dependencies.
//
// Transcribed from src/data/beats.js, which is itself transcribed from Sita-pati das,
// "The Art and Science of Harinam Sankirtan Yajna".
//
// - THIS IS THE FALLBACK SET, NOT THE CANONICAL ONE -
// The built-ins the app shows come from the server shipped_beats table when it can be
// read, and from this list when it cannot - see ShippedBeats. All three compiled files
// (beats.js, Beats.kt, Beats.swift) are generated from the same source and must agree
// at release time: an offline user plays THIS list, and a shared #b= link carries no
// id, so each client resolves built-in ids against the set it happens to be holding.
// Divergent ids or patterns mean the same link plays a different beat on each platform.
//
// Pattern notation is the wire format's own: "O" open, "X" closed, "-" rest.
//
// - GENERATED: DO NOT EDIT THE ARRAY BY HAND -
// Run scripts/generateBuiltinBeats.mjs (or generateIosBeats.mjs) to regenerate.

private func pattern(_ notation: String) -> [Stroke?] {
    notation.unicodeScalars.map { Stroke.fromCode(String($0)) }
}

/// One built-in beat, with the invariant every beat must hold checked up front:
/// steps must equal each lane's pattern length. A stale length once made the web strip
/// wrap into extra rows and the sequencer play only half the pattern; failing loudly at
/// load beats that bug silently. The old steps == beatsPerBar * cellsPerGroup check is
/// gone - it is not a general truth; matan is 16 groups of 3 at eighth subdivision,
/// 48 cells over 24 quarters. The invariant that holds is steps == sum(groups).
private func builtIn(
    id: String, group: String, name: String, note: String,
    bpm: Int, groups: [Int], cpq: Int,
    dayan: String, bayan: String, kartal: String? = nil, description: String? = nil,
) -> Beat {
    // Derived exactly as ShareCodec.decodeBeat derives them, so a built-in round-trips
    // through the share format to an identical beat, which a test asserts.
    let steps = sumGroups(groups)
    precondition(steps > 0, "beat \(id): groups sum to zero cells")
    precondition(cpq >= 1, "beat \(id): cells-per-quarter must be at least 1")

    let uniform = groups.allSatisfy { $0 == groups[0] }
    let cellsPerGroup = uniform ? groups[0] : cpq
    let beatsPerBar = Double(steps) / Double(cpq)

    var lanes: [LaneId: [Stroke?]] = [
        .dayan: pattern(dayan),
        .bayan: pattern(bayan),
    ]
    // A beat without a kartal line simply has no cymbal row - the key is absent, which
    // is what activeLanes() and the strip's lane filter read.
    if let kartal { lanes[.kartal] = pattern(kartal) }

    for (lane, cells) in lanes {
        precondition(cells.count == steps,
            "beat \(id): lane \(lane.wireId) has \(cells.count) cells, expected \(steps)")
    }

    return Beat(
        id: id, name: name, note: note, bpm: bpm, steps: steps,
        beatsPerBar: beatsPerBar, cellsPerGroup: cellsPerGroup, groups: groups,
        description: description, lanePatterns: lanes, group: group,
    )
}

enum Beats {

    // (generated array below)
    // (BEGIN GENERATED - do not edit inside this block.)
    static let beats: [Beat] = [
        builtIn(
            id: "double_time_2", group: "Sixteenths", name: "Double Time 2", note: "4 beats",
            bpm: 140, groups: [4, 4, 4, 4], cpq: 4,
            dayan: "X-OOX-OOX-OOX-OO",
            bayan: "O--X-OO-O--X-OO-",
            kartal: "O-XXO-XXO-XXO-XX",
        ),
        builtIn(
            id: "daspahir_taal", group: "Sixteenths", name: "daspahir taal", note: "8 beats",
            bpm: 90, groups: [4, 4, 4, 4, 4, 4, 4, 4], cpq: 4,
            dayan: "----X-O-X-XXXXO-----O-O---O---O-",
            bayan: "O---------------X--X-OO-O-O-O-O-",
        ),
        builtIn(
            id: "tehai", group: "Sixteenths", name: "tehai", note: "4 beats",
            bpm: 90, groups: [4, 4, 4, 4], cpq: 4,
            dayan: "-O-OO--O-OO--O-O",
            bayan: "X-X-O-X-X-O-X-X-",
        ),
        builtIn(
            id: "pick_up", group: "Sixteenths", name: "pick up", note: "4 beats",
            bpm: 90, groups: [4, 4, 4, 4], cpq: 4,
            dayan: "O-OO-O-OO-OO-O-O",
            bayan: "-X-X--X--X-X--X-",
        ),
        builtIn(
            id: "bhajani_taal", group: "Sixteenths", name: "bhajani taal", note: "4 beats",
            bpm: 90, groups: [4, 4, 4, 4], cpq: 4,
            dayan: "--O---O---O---O-",
            bayan: "OO-O-O--XX-X-X-O",
        ),
        builtIn(
            id: "matan", group: "Straight", name: "matan", note: "16 beats",
            bpm: 157, groups: [3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3], cpq: 2,
            dayan: "OOOOOOOOOOOOOOOOOOOOOOO-OOOOOOOOOOOOOOOOOOOOOOO-",
            bayan: "OOOOO-OOOOO-OOOOO-OO-O--XXXXX-XXXXX-XXXXX-XX-X--",
        ),
        builtIn(
            id: "lofa_taal_two_beat_damodarastakam", group: "Straight", name: "lofa taal two beat damodarastakam", note: "8 beats",
            bpm: 90, groups: [3, 3, 3, 3, 3, 3, 3, 3], cpq: 2,
            dayan: "O-XXXXO-XXXXO-XXXXO---OO",
            bayan: "O-O-O-O-O-O---------XX--",
        ),
        builtIn(
            id: "iskcon_smasher", group: "Sixteenths", name: "Iskcon smasher", note: "4 beats",
            bpm: 90, groups: [4, 4, 4, 4], cpq: 4,
            dayan: "O--O--O-O--O--O-",
            bayan: "O---O-O---O-O-O-",
        ),
        builtIn(
            id: "keherva_medium_speed", group: "Straight", name: "keherva medium speed", note: "4 beats",
            bpm: 90, groups: [2, 2, 2, 2], cpq: 2,
            dayan: "O-XOO-XO",
            bayan: "OO-X-OO-",
        ),
    ]
    // (END GENERATED)
}
