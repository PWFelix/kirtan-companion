import Foundation
import XCTest

// Cloud transport tests. Everything here is pure — URLs, PKCE, error mapping, row mapping,
// search filters — the parts a live-server integration test can't cheaply cover, and which
// are exactly where a shape mismatch between the two platforms hides. The network itself is
// verified separately with a live browse against the configured project.

final class CloudTests: XCTestCase {

    private let config = SupabaseConfig(url: "https://example.supabase.co", anonKey: "testkey")

    // MARK: SupabaseConfig

    func testIsConfiguredRequiresBothValues() {
        XCTAssertFalse(SupabaseConfig(url: "", anonKey: "k").isConfigured)
        XCTAssertFalse(SupabaseConfig(url: "https://x.supabase.co", anonKey: "").isConfigured)
        XCTAssertTrue(config.isConfigured)
    }

    func testBaseUrlStripsTrailingSlash() {
        XCTAssertEqual(SupabaseConfig(url: "https://x.supabase.co/", anonKey: "k").baseUrl, "https://x.supabase.co")
    }

    // MARK: PKCE

    func testVerifierIs43CharUnpaddedBase64url() {
        let v = Pkce.verifier()
        XCTAssertEqual(v.count, 43)
        XCTAssertFalse(v.contains("+") || v.contains("/") || v.contains("="))
    }

    func testStateIs22CharUnpaddedBase64url() {
        let s = Pkce.state()
        XCTAssertEqual(s.count, 22)
        XCTAssertFalse(s.contains("+") || s.contains("/") || s.contains("="))
    }

    func testChallengeIsDeterministicSha256() {
        let v = Pkce.verifier()
        XCTAssertEqual(Pkce.challenge(v), Pkce.challenge(v))
        XCTAssertEqual(Pkce.challenge(v).count, 43)  // 32 bytes → 43 base64url
    }

    func testRandomValuesDifferAcrossCalls() {
        XCTAssertNotEqual(Pkce.verifier(), Pkce.verifier())
        XCTAssertNotEqual(Pkce.state(), Pkce.state())
    }

    // MARK: authorize URL + callback

    func testAuthorizeUrlCarriesPkceAndEncodedRedirect() {
        let url = oauthAuthorizeUrl(config: config, provider: "google", redirectUri: supabaseRedirectUri, challenge: "CHALL", state: "ST")
        XCTAssertTrue(url.hasPrefix("https://example.supabase.co/auth/v1/authorize?"), url)
        XCTAssertTrue(url.contains("provider=google"))
        XCTAssertTrue(url.contains("code_challenge=CHALL"))
        XCTAssertTrue(url.contains("code_challenge_method=S256"))
        XCTAssertTrue(url.contains("state=ST"))
        // The web app leaves the scheme unencoded (a template literal, no percent-encoding), and Supabase
        // accepts it — so we match that rather than "tidying" the `:`/`/` away.
        XCTAssertTrue(url.contains("redirect_to=kirtan://auth/callback"), url)
    }

    func testParseCallbackReadsCodeAndState() {
        let callback = parseOAuthCallback("kirtan://auth/callback?code=abc123&state=st")
        XCTAssertEqual(callback?.code, "abc123")
        XCTAssertEqual(callback?.state, "st")
    }

    func testParseCallbackWithoutStateLeavesItNil() {
        let callback = parseOAuthCallback("kirtan://auth/callback?code=abc")
        XCTAssertEqual(callback?.code, "abc")
        XCTAssertNil(callback?.state)
    }

    func testParseCallbackRefusesALinkWithNoCode() {
        XCTAssertNil(parseOAuthCallback("kirtan://auth/callback?error=access_denied"))
    }

    // MARK: rest / auth URLs

    func testRestUrlBuildsFiltersOrderAndLimit() {
        let url = restUrl(config: config, table: "beats", filters: [("user_id", "eq.u1"), ("name", "ilike.%a%")], order: "created_at.asc", limit: 10)
        XCTAssertTrue(url.hasPrefix("https://example.supabase.co/rest/v1/beats?"), url)
        XCTAssertTrue(url.contains("user_id=eq.u1"))
        XCTAssertTrue(url.contains("order=created_at.asc"))
        XCTAssertTrue(url.contains("limit=10"))
    }

    func testRestUrlPercentEncodesHostileFilterValues() {
        // `&` and `#` would otherwise truncate the query and select the wrong rows.
        let url = restUrl(config: config, table: "beats", filters: [("name", "eq.a&b#c")])
        XCTAssertTrue(url.contains("name=eq.a%26b%23c"), url)
    }

    func testAuthUrlCarriesGrantType() {
        let url = authUrl(config: config, path: "token", query: [("grant_type", "refresh_token")])
        XCTAssertEqual(url, "https://example.supabase.co/auth/v1/token?grant_type=refresh_token")
    }

    // MARK: error mapping

    func testUniqueKeyViolationIsConflict() {
        XCTAssertEqual(restFailure(status: 409, body: JValue.obj(("code", .str("23505")), ("message", .str("dup"))), whileDoing: "save it").code, .conflict)
    }

    func testSingleSelectMissIsNotFound() {
        XCTAssertEqual(restFailure(status: 406, body: JValue.obj(("code", .str("PGRST116"))), whileDoing: "your settings").code, .notFound)
    }

    func testPermissionRefusalsAreUnavailable() {
        XCTAssertEqual(restFailure(status: 401, body: nil, whileDoing: "save it").code, .unavailable)
        XCTAssertEqual(restFailure(status: 403, body: nil, whileDoing: "save it").code, .unavailable)
        XCTAssertEqual(restFailure(status: 400, body: JValue.obj(("code", .str("42501"))), whileDoing: "save it").code, .unavailable)
    }

    func testUnrecognisedFailureIsUnknown() {
        XCTAssertEqual(restFailure(status: 500, body: JValue.obj(("message", .str("boom"))), whileDoing: "save it").code, .unknown)
    }

    func testClockSkewRecognisedFromJwtPhrase() {
        XCTAssertTrue(isClockSkew(status: 401, body: JValue.obj(("message", .str("JWT issued in the future")))))
        XCTAssertFalse(isClockSkew(status: 401, body: JValue.obj(("message", .str("invalid credentials")))))
        XCTAssertFalse(isClockSkew(status: 200, body: JValue.obj(("message", .str("JWT issued in the future")))))
    }

    // MARK: row mapping

    private func aBeat(id: String?) -> Beat {
        Beat(
            id: id, name: "Test Beat", note: "a note", bpm: 80, steps: 8,
            beatsPerBar: 4, cellsPerGroup: 2, groups: nil, description: nil,
            lanePatterns: [
                .dayan: [.open, .closed, .open, .open, .closed, .open, .open, .open],
                .bayan: [.open, nil, nil, nil, nil, nil, nil, nil],
            ]
        )
    }

    func testBeatRowCarriesOwnerNameAndBlob() {
        let row = SupabaseRows.beatRow(userId: "u1", draft: aBeat(id: nil))
        XCTAssertEqual(row.element("user_id")?.asString(), "u1")
        XCTAssertEqual(row.element("name")?.asString(), "Test Beat")
        XCTAssertNotNil(row.element("data"))
    }

    func testToBeatOverridesBlobIdWithColumnId() {
        let body = LibraryJson.encodeBody(aBeat(id: "blob-id"))
        let row = JValue.obj(("id", .str("column-id")), ("data", body))
        let beat = SupabaseRows.toBeat(row)
        XCTAssertEqual(beat?.id, "column-id")
        XCTAssertEqual(beat?.name, "Test Beat")
    }

    func testToBeatSkipsARowWithoutId() {
        XCTAssertNil(SupabaseRows.toBeat(JValue.obj(("data", .obj(("name", .str("x")))))))
    }

    func testToCategoryReadsNameAndBeatIds() {
        let row = JValue.obj(("id", .str("c1")), ("name", .str("Morning")), ("beat_ids", JValue.arr([.str("b1"), .str("b2")])))
        let category = SupabaseRows.toCategory(row)
        XCTAssertEqual(category?.id, "c1")
        XCTAssertEqual(category?.name, "Morning")
        XCTAssertEqual(category?.beatIds, ["b1", "b2"])
    }

    func testBeatIdsArrayIsEmptyArrayForNoBeats() {
        XCTAssertEqual(SupabaseRows.beatIdsArray([]), JValue.arr([]))
        XCTAssertEqual(SupabaseRows.beatIdsArray(["a", "b"]), JValue.arr([.str("a"), .str("b")]))
    }

    // MARK: community search filters

    func testBrowseFiltersEmptyQueryIsNoFilters() {
        XCTAssertTrue(browseFilters(query: "   ", scope: .names).isEmpty)
    }

    func testBrowseFiltersNamesOnly() {
        XCTAssertEqual(browseFilters(query: "dadra", scope: .names).map { "\($0.0)=\($0.1)" }, ["name=ilike.%dadra%"])
    }

    func testBrowseFiltersAuthorsOnly() {
        XCTAssertEqual(browseFilters(query: "ram", scope: .authors).map { "\($0.0)=\($0.1)" }, ["author_name=ilike.%ram%"])
    }

    func testBrowseFiltersEverythingIsAnOr() {
        XCTAssertEqual(browseFilters(query: "x", scope: .everything).map { "\($0.0)=\($0.1)" }, ["or=(name.ilike.%x%,author_name.ilike.%x%)"])
    }

    // MARK: timestamps

    func testParseTimestampHandlesZuluWithFraction() {
        let epoch = parseTimestamp("1970-01-01T00:00:00.000Z")
        XCTAssertEqual(epoch, 0)
    }

    func testParseTimestampHandlesOffset() {
        // 01:00:00+01:00 == 00:00:00Z == epoch 0
        XCTAssertEqual(parseTimestamp("1970-01-01T01:00:00+01:00"), 0)
    }

    func testParseTimestampReturnsNilForGarbage() {
        XCTAssertNil(parseTimestamp(nil))
        XCTAssertNil(parseTimestamp(""))
        XCTAssertNil(parseTimestamp("not-a-date"))
    }
}
