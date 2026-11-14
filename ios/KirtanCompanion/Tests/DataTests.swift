import Foundation
import XCTest
// No `import KirtanCompanion`: this target compiles Sources/data directly into the test
// module (see project.pbxproj), so the data types are in-module and need no import.

// The data layer's contracts, most of them about the share codec — the app's first
// untrusted input. A decode that round-trips and a decode that REJECTS are both tested,
// because a silently half-imported beat is the bug the whole file exists to prevent.

final class DataTests: XCTestCase {

    // A beat that exercises every derivation: uniform groups, a kartal line, a description.
    private func sample() -> Beat {
        let groups = [4, 4, 4, 4]
        let steps = sumGroups(groups)
        return Beat(
            id: "my_test_beat",
            name: "Test Beat",
            note: "4 beats",
            bpm: 120,
            steps: steps,
            beatsPerBar: Double(steps) / 4.0,
            cellsPerGroup: 4,
            groups: groups,
            description: "A made-up beat \u{201C}quoted\u{201D} for tests.",
            lanePatterns: [
                .dayan: "X-OOX-OOX-OOX-OO".unicodeScalars.map { Stroke.fromCode(String($0)) },
                .bayan: "O--X-OO-O--X-OO-".unicodeScalars.map { Stroke.fromCode(String($0)) },
                .kartal: "O-XXO-XXO-XXO-XX".unicodeScalars.map { Stroke.fromCode(String($0)) },
            ],
            group: "Everyday",
        )
    }

    // ── ShareCodec: round-trip ─────────────────────────────────────────────

    func testBeatRoundTrips() {
        let original = sample()
        let code = ShareCodec.encodeBeat(original)
        // The code is base64url only.
        let urlSafe = code.unicodeScalars.allSatisfy { s in
            let v = s.value
            return s == "-" || s == "_" ||
                (0x41...0x5A).contains(v) || (0x61...0x7A).contains(v) || (0x30...0x39).contains(v)
        }
        XCTAssertTrue(urlSafe, "share code should be base64url: \(code)")

        guard case .some(.beat(let beat)) = ShareCodec.decodeShare(code) else {
            return XCTFail("expected a beat payload")
        }
        XCTAssertEqual(beat.name, original.name)
        XCTAssertEqual(beat.bpm, original.bpm)
        XCTAssertEqual(beat.steps, original.steps)
        XCTAssertEqual(beat.groups, original.groups)
        XCTAssertEqual(beat.lanePatterns[.dayan], original.lanePatterns[.dayan])
        XCTAssertEqual(beat.lanePatterns[.bayan], original.lanePatterns[.bayan])
        XCTAssertEqual(beat.lanePatterns[.kartal], original.lanePatterns[.kartal])
        // The id is minted, never trusted from the payload.
        XCTAssertNil(beat.id)
    }

    func testCategoryRoundTrips() {
        let a = sample()
        var b = sample()
        b.name = "Second"
        let code = ShareCodec.encodeCategory(name: "My Set", beats: [a, b])
        guard case .some(.category(let name, let beats)) = ShareCodec.decodeShare(code) else {
            return XCTFail("expected a category payload")
        }
        XCTAssertEqual(name, "My Set")
        XCTAssertEqual(beats.count, 2)
        XCTAssertEqual(beats[1].name, "Second")
    }

    // ── ShareCodec: the trust boundary ─────────────────────────────────────

    func testRejectsOversizedCode() {
        let code = String(repeating: "A", count: ShareCodec.MAX_CODE_LEN + 1)
        XCTAssertNil(ShareCodec.decodeShare(code))
    }

    func testRejectsNonBase64() {
        XCTAssertNil(ShareCodec.decodeShare("not a code\nwith spaces"))
        XCTAssertNil(ShareCodec.decodeShare(""))
    }

    func testRejectsGarbagePayload() {
        // Valid base64url, but the decoded bytes are not JSON.
        let code = ShareCodec.toBase64Url("%%% not json %%%")
        XCTAssertNil(ShareCodec.decodeShare(code))
    }

    func testRejectsWrongVersion() {
        let payload = JValue.obj(("v", .int(99)), ("t", .string("b")), ("n", .string("x")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OOX-OO")),
                            JPair(key: "bayan", value: .string("O--X-OO-O--X-OO-"))])))
        XCTAssertNil(ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())))
    }

    func testRejectsPatternWrongLength() {
        // dayan claims 16 cells via groups, but the pattern string is only 12.
        let payload = JValue.obj(("v", .int(1)), ("t", .string("b")), ("n", .string("x")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OO"))])))
        XCTAssertNil(ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())))
    }

    func testRejectsHugeSteps() {
        let payload = JValue.obj(("v", .int(1)), ("t", .string("b")), ("n", .string("x")),
            ("g", .arr([.int(ShareCodec.MAX_STEPS + 1)])), ("q", .int(4)),
            ("p", .object([])))
        XCTAssertNil(ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())))
    }

    func testStripsInvisibleFromName() {
        let payload = JValue.obj(("v", .int(1)), ("t", .string("b")),
            ("n", .string("\u{202E}Hello\u{200B}World\u{7F}")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OOX-OO"))])))
        guard case .some(.beat(let beat)) = ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())) else {
            return XCTFail("expected a beat")
        }
        // The invisible Cc/Cf scalars are gone; letters survive.
        XCTAssertEqual(beat.name, "HelloWorld")
    }

    func testNameCapsAtLimit() {
        let long = String(repeating: "a", count: ShareCodec.MAX_NAME_LEN + 20)
        let payload = JValue.obj(("v", .int(1)), ("t", .string("b")), ("n", .string(long)),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OOX-OO"))])))
        guard case .some(.beat(let beat)) = ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())) else {
            return XCTFail("expected a beat")
        }
        XCTAssertEqual(beat.name.count, ShareCodec.MAX_NAME_LEN)
    }

    func testDropsDescriptionAndGroup() {
        let payload = JValue.obj(("v", .int(1)), ("t", .string("b")), ("n", .string("x")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OOX-OO"))])),
            ("desc", .string("malicious prose")), ("grp", .string("Evil")))
        guard case .some(.beat(let beat)) = ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())) else {
            return XCTFail("expected a beat")
        }
        XCTAssertNil(beat.description)
        XCTAssertNil(beat.group)
    }

    func testOneBadBeatRejectsWholeCategory() {
        let good = JValue.obj(("n", .string("ok")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("X-OOX-OOX-OOX-OO"))])))
        // Second beat has a pattern the wrong length.
        let bad = JValue.obj(("n", .string("bad")),
            ("g", .arr([.int(4), .int(4), .int(4), .int(4)])), ("q", .int(4)),
            ("p", .object([JPair(key: "dayan", value: .string("XXXX"))])))
        let payload = JValue.obj(("v", .int(1)), ("t", .string("c")), ("n", .string("set")),
            ("b", .arr([good, bad])))
        XCTAssertNil(ShareCodec.decodeShare(ShareCodec.toBase64Url(payload.write())))
    }

    // ── codeFrom / codeFromInput ───────────────────────────────────────────

    func testCodeFromForms() {
        XCTAssertEqual(ShareIntake.codeFrom("kirtan://beat?c=ABCDEFGHIJ"), "ABCDEFGHIJ")
        XCTAssertEqual(ShareIntake.codeFrom("https://x.com/#b=XYZQRSTUVW"), "XYZQRSTUVW")
        XCTAssertEqual(ShareIntake.codeFrom("ABCDEFGHIJ"), "ABCDEFGHIJ")
        // Too short to be a code → not a share link.
        XCTAssertNil(ShareIntake.codeFrom("ABC123"))
        // Contains a space → not base64url → not a share link.
        XCTAssertNil(ShareIntake.codeFrom("hello world"))
        XCTAssertNil(ShareIntake.codeFrom("   "))
    }

    func testCodeFromInputFallsBackToWholeText() {
        XCTAssertEqual(ShareCodec.codeFromInput("plain text"), "plain text")
        XCTAssertEqual(ShareCodec.codeFromInput("url#b=CODE"), "CODE")
    }

    // ── ShippedBeats ───────────────────────────────────────────────────────

    func testShippedBeatsNeverEmpty() {
        let before = ShippedBeats.shared.effective
        XCTAssertTrue(before.count > 0)
        ShippedBeats.shared.install([])
        // install refuses an empty set; the previous set stands.
        XCTAssertEqual(ShippedBeats.shared.effective, before)
    }

    func testShippedBeatsInstallsAndQueries() {
        let custom = Beat(id: "server_x", name: "X", note: "", bpm: 90, steps: 16,
            beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4], description: nil,
            lanePatterns: [.dayan: Array(repeating: nil, count: 16)], group: "New")
        let saved = ShippedBeats.shared.effective
        ShippedBeats.shared.install([custom])
        XCTAssertTrue(ShippedBeats.shared.isShippedId("server_x"))
        // Restore so other tests see the compiled set.
        ShippedBeats.shared.install(saved)
    }

    // ── Meter ──────────────────────────────────────────────────────────────

    func testClampBpm() {
        XCTAssertEqual(clampBpm(10), MIN_BPM)
        XCTAssertEqual(clampBpm(999), MAX_BPM)
        XCTAssertEqual(clampBpm(120), 120)
    }

    func testGroupsForReconstructsMissingGroups() {
        // A beat with no stored groups: 16 steps, 4 quarters, cpq 4 → [4,4,4,4].
        let b = Beat(id: "a", name: "a", note: "", bpm: 90, steps: 16, beatsPerBar: 4,
            cellsPerGroup: 4, groups: nil, description: nil, lanePatterns: [:])
        XCTAssertEqual(groupsFor(b), [4, 4, 4, 4])
        XCTAssertEqual(cpqFor(b), 4)
    }

    func testCpqNotFromCellsPerGroup() {
        // 6/8: groups [3,3], cellsPerGroup 3, but cpq 2 (six steps over three quarters).
        let b = Beat(id: "a", name: "a", note: "", bpm: 90, steps: 6, beatsPerBar: 3,
            cellsPerGroup: 3, groups: [3, 3], description: nil, lanePatterns: [:])
        XCTAssertEqual(cpqFor(b), 2)
    }

    // ── StepLabels ─────────────────────────────────────────────────────────

    func testLabelsFromGroups() {
        XCTAssertEqual(labelsFromGroups([4, 4, 4, 4]), ["1", "\u{B7}", "\u{B7}", "\u{B7}",
            "2", "\u{B7}", "\u{B7}", "\u{B7}", "3", "\u{B7}", "\u{B7}", "\u{B7}", "4", "\u{B7}", "\u{B7}", "\u{B7}"])
        XCTAssertEqual(labelsFromGroups([2, 2, 3]), ["1", "\u{B7}", "2", "\u{B7}", "3", "\u{B7}", "\u{B7}"])
    }

    // ── Strokes ────────────────────────────────────────────────────────────

    func testStrokeVisualFallbacksToRest() {
        XCTAssertEqual(strokeVisual(nil).shape, .rest)
        XCTAssertEqual(strokeVisual(.open).label, "open")
        XCTAssertEqual(strokeVisual(.closed).label, "closed")
    }

    // ── Tuning ─────────────────────────────────────────────────────────────

    func testCentsToRate() {
        XCTAssertEqual(centsToRate(0), 1.0, accuracy: 0.0001)
        XCTAssertEqual(centsToRate(100), pow(2.0, 100.0 / 1200.0), accuracy: 0.0001)
        XCTAssertEqual(clampCents(9999), TUNE_MAX_CENTS)
        XCTAssertEqual(clampCents(-9999), TUNE_MIN_CENTS)
    }

    // ── Bols ───────────────────────────────────────────────────────────────

    func testBols() {
        XCTAssertEqual(Bols.forStroke(laneId: "dayan", code: "O"), "Ta")
        XCTAssertEqual(Bols.forStroke(laneId: "bayan", code: "X"), "Khe")
        XCTAssertEqual(Bols.forStroke(laneId: "dayan", code: nil), nil)
        XCTAssertEqual(Bols.combo(dayanCode: "O", bayanCode: "O"), "Da")
        XCTAssertEqual(Bols.combo(dayanCode: "O", bayanCode: nil), nil)
    }

    // ── BeatSourceExport ───────────────────────────────────────────────────

    func testPatternNotation() {
        let b = sample()
        XCTAssertEqual(BeatSourceExport.patternNotation(b, .dayan), "X-OOX-OOX-OOX-OO")
        XCTAssertEqual(BeatSourceExport.patternNotation(b, .kartal), "O-XXO-XXO-XXO-XX")
    }

    func testExportedKotlinRoundTripsThroughJson() {
        let b = sample()
        let json = BeatSourceExport.toJson(b)
        // Re-read the id and group we just wrote.
        XCTAssertEqual(json.stringField("id"), "my_test_beat")
        XCTAssertEqual(json.stringField("group"), "Everyday")
    }

    // ── Built-ins: the fallback set is self-consistent ─────────────────────

    func testBuiltInBeatsAreConsistent() {
        for beat in Beats.beats {
            let steps = sumGroups(beat.groups ?? [])
            XCTAssertEqual(beat.steps, steps, "\(beat.id ?? "?"): steps/sum(groups)")
            for lane in LaneId.ordered {
                if let cells = beat.pattern(lane) {
                    XCTAssertEqual(cells.count, beat.steps, "\(beat.id ?? "?")/\(lane.wireId) length")
                }
            }
        }
        XCTAssertEqual(Beats.beats.count, 9)
    }
}
