import XCTest

/// Guards the String Catalog. The test bundle doesn't carry the app's resources (it is a
/// standalone module), so this reads Localizable.xcstrings straight off disk, next to this
/// file. The catalog is keyed by the English text; what can silently go wrong is a Spanish
/// value that drops, adds or retypes a placeholder — String(format:) then shows garbage or
/// crashes — so each translation must carry the same placeholders as its English.
final class LocalisationTests: XCTestCase {
    private func catalog() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Resources/Localizable.xcstrings")
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["sourceLanguage"] as? String, "en")
        return try XCTUnwrap(root["strings"] as? [String: Any])
    }

    /// Placeholders as a sorted bag, position markers (%1$@) normalised to their type (%@).
    private func placeholders(_ s: String) -> [String] {
        let re = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?(@|lld|d|lf)")
        return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).map {
            "%" + (s as NSString).substring(with: $0.range(at: 1))
        }.sorted()
    }

    /// Every value a localisation holds: the one string, or each plural form.
    private func values(_ loc: [String: Any]) -> [String] {
        if let unit = loc["stringUnit"] as? [String: Any], let v = unit["value"] as? String { return [v] }
        let plural = (loc["variations"] as? [String: Any])?["plural"] as? [String: Any] ?? [:]
        return plural.values.compactMap { (($0 as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String }
    }

    func testEveryKeyHasSpanish() throws {
        for (key, entry) in try catalog() {
            let locs = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            XCTAssertNotNil(locs["es"], "no Spanish for “\(key)”")
        }
    }

    func testSpanishKeepsThePlaceholders() throws {
        for (key, entry) in try catalog() {
            let locs = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            // Plurals and semantic keys carry their English in an `en` localisation.
            let english = (locs["en"] as? [String: Any]).map(values)?.first ?? key
            for value in values(locs["es"] as? [String: Any] ?? [:]) {
                XCTAssertEqual(placeholders(value), placeholders(english), "“\(key)” → “\(value)”")
            }
        }
    }
}
