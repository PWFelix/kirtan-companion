package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cloud plumbing that can be checked without a network.
 *
 * There is no HTTP mocking in this build (`ktor-client-mock` is not a dependency and
 * the version catalog is frozen), so the client is split instead: everything that
 * DECIDES something — the PKCE pair, the URLs, the mapping from a Postgres error to
 * a [StorageErrorCode], the session blob — is a pure function, and the Ktor call that
 * carries it is a few lines with no logic in it. These tests cover the deciding half,
 * which is also the half where a mistake is invisible until a user's library fails to
 * load on a phone.
 *
 * What is NOT covered here, and is called out in the class header of
 * [SupabaseClient] rather than assumed to work: an actual round trip against a real
 * Supabase project.
 */
class SupabaseClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopStores() {
        scopes.forEach { it.cancel() }
    }

    private fun newStore(): DataStore<Preferences> {
        val file = File(folder.root, "cloud-${scopes.size}.preferences_pb")
        scopes += CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scopes.last(),
            produceFile = { file },
        )
    }

    private val config = SupabaseConfig("https://project.supabase.co", "anon-key")

    // ── PKCE ───────────────────────────────────────────────────────────────

    @Test
    fun `the challenge is the sha256 of the verifier`() {
        // The test vector from RFC 7636 Appendix B. If this passes, the challenge is
        // the one GoTrue expects and a real sign-in can complete.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `a verifier is high-entropy, url-safe and inside the rfc's length window`() {
        val verifier = Pkce.verifier()

        assertEquals(43, verifier.length)
        assertTrue("must be url-safe base64: $verifier", verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals("two sign-ins must not share a verifier", verifier, Pkce.verifier())
        assertNotEquals(Pkce.state(), Pkce.state())
    }

    // ── URLs ───────────────────────────────────────────────────────────────

    @Test
    fun `the authorize url asks for a code, not a token in the fragment`() {
        val url = Url(
            oauthAuthorizeUrl(
                config = config,
                provider = "google",
                redirectUri = "kirtan://auth/callback",
                challenge = "challenge-value",
                state = "state-value",
            ),
        )

        assertEquals("https", url.protocol.name)
        assertEquals("project.supabase.co", url.host)
        assertEquals("/auth/v1/authorize", url.encodedPath)
        assertEquals("google", url.parameters["provider"])
        assertEquals("kirtan://auth/callback", url.parameters["redirect_to"])
        assertEquals("challenge-value", url.parameters["code_challenge"])
        // S256 is what makes GoTrue answer with ?code=. The implicit flow would hand
        // an access token to whatever app owns the kirtan:// scheme.
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("state-value", url.parameters["state"])
    }

    @Test
    fun `a trailing slash on the project url does not double up in the path`() {
        val withSlash = SupabaseConfig("https://project.supabase.co/", "k")

        assertEquals(
            "https://project.supabase.co/auth/v1/authorize",
            oauthAuthorizeUrl(withSlash, "google", "kirtan://auth/callback", "c", "s")
                .substringBefore("?"),
        )
    }

    @Test
    fun `a deep link yields its code and state`() {
        val callback = parseOAuthCallback("kirtan://auth/callback?code=abc123&state=xyz")

        assertEquals("abc123", callback?.code)
        assertEquals("xyz", callback?.state)
    }

    @Test
    fun `a deep link without a code is not a sign-in`() {
        assertNull(parseOAuthCallback("kirtan://auth/callback"))
        assertNull(parseOAuthCallback("kirtan://auth/callback?state=xyz"))
        assertNull(parseOAuthCallback("kirtan://auth/callback?code="))
        assertNull(parseOAuthCallback("not a url at all"))
        // The other kirtan:// deep link this app declares carries a share code in its
        // FRAGMENT, not a query `code`, so the two can't be mistaken for each other.
        assertNull(parseOAuthCallback("kirtan://beat?b=eyJ2IjoxfQ"))
        // A code is still readable when the state was dropped: the state check in
        // completeOAuth is what authenticates a callback, not its shape, and refusing
        // a valid code here would cost the user a retry for nothing.
        assertEquals("abc", parseOAuthCallback("kirtan://auth/callback?code=abc")?.code)
        assertNull(parseOAuthCallback("kirtan://auth/callback?code=abc")?.state)
    }

    @Test
    fun `a filter value that looks like a query cannot truncate the url`() {
        // A playlist name containing & or # would otherwise end the query early and
        // silently select the wrong rows — or every row.
        val url = Url(
            restUrl(
                config,
                "published_beats",
                listOf("select" to "id, name", "name" to "ilike.%a&b#c=d%"),
                order = "created_at.desc",
                limit = 40,
            ),
        )

        assertEquals("/rest/v1/published_beats", url.encodedPath)
        assertEquals("id, name", url.parameters["select"])
        assertEquals("ilike.%a&b#c=d%", url.parameters["name"])
        assertEquals("created_at.desc", url.parameters["order"])
        assertEquals("40", url.parameters["limit"])
    }

    @Test
    fun `an rpc url points at the function, not a table`() {
        assertEquals(
            "https://project.supabase.co/rest/v1/rpc/increment_published_copies",
            restUrl(config, "rpc/increment_published_copies", emptyList(), null, null),
        )
    }

    @Test
    fun `an auth url carries its query`() {
        assertEquals(
            "https://project.supabase.co/auth/v1/token?grant_type=pkce",
            authUrl(config, "token", listOf("grant_type" to "pkce")),
        )
    }

    // ── Error mapping ──────────────────────────────────────────────────────

    @Test
    fun `a unique-key violation is a conflict`() {
        val error = restFailure(409, errorBody("23505", "duplicate key value"), "that beat")

        assertEquals(StorageErrorCode.CONFLICT, error.code)
        assertEquals("That already exists.", error.message)
    }

    @Test
    fun `no rows for a single-object read is notFound`() {
        val error = restFailure(406, errorBody("PGRST116", "no rows"), "that beat")

        assertEquals(StorageErrorCode.NOT_FOUND, error.code)
        assertEquals("that beat no longer exists.", error.message)
    }

    @Test
    fun `a permission refusal reads as signed out`() {
        // On a client holding only the anon key, a 401/403 nearly always means the
        // session expired or Row-Level Security decided the row isn't theirs — and
        // "sign in and try again" is the only thing the user can act on.
        for (status in listOf(401, 403)) {
            val error = restFailure(status, errorBody("42501", "permission denied"), "that beat")
            assertEquals(StorageErrorCode.UNAVAILABLE, error.code)
            assertTrue(error.message!!.contains("signed out"))
        }
        assertEquals(
            StorageErrorCode.UNAVAILABLE,
            restFailure(400, errorBody("42501", "permission denied"), "that beat").code,
        )
    }

    @Test
    fun `anything else is unknown but still carries the server's reason`() {
        val error = restFailure(500, errorBody("XX000", "could not serialize access"), "your beats")

        assertEquals(StorageErrorCode.UNKNOWN, error.code)
        assertTrue(error.message, error.message!!.contains("could not serialize access"))
        assertTrue(error.message, error.message!!.contains("your beats"))
    }

    @Test
    fun `a failure with no readable body still gets a sentence`() {
        val error = restFailure(502, null, "your beats")

        assertEquals(StorageErrorCode.UNKNOWN, error.code)
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `bad credentials get their own sentence`() {
        val body = buildJsonObject { put("error_description", "Invalid login credentials") }

        val error = authFailure(400, body, "sign in")

        assertEquals(StorageErrorCode.UNKNOWN, error.code)
        assertEquals("That email and password didn't match. Try again.", error.message)
    }

    @Test
    fun `an expired session on an auth call reads as signed out`() {
        assertEquals(
            StorageErrorCode.UNAVAILABLE,
            authFailure(401, buildJsonObject { put("msg", "invalid refresh token") }, "sign in").code,
        )
    }

    @Test
    fun `an unexplained auth refusal still says what it was doing`() {
        val error = authFailure(422, buildJsonObject { put("msg", "email rate limit exceeded") }, "create that account")

        assertEquals("Couldn't create that account. email rate limit exceeded", error.message)
        assertEquals(StorageErrorCode.UNKNOWN, error.code)
    }

    @Test
    fun `clock skew is recognised only where it can happen`() {
        // Right after sign-in the token's "issued at" is now; a database node a
        // second behind reads it as issued in the future and refuses. One delayed
        // retry hides it, so recognising it matters — but only on a 401/403, or a
        // genuine permission failure would be retried into a two-second stall.
        assertTrue(isClockSkew(401, messageBody("JWT issued at future")))
        assertTrue(isClockSkew(403, messageBody("the jwt is from the future")))
        assertFalse(isClockSkew(400, messageBody("JWT issued at future")))
        assertFalse(isClockSkew(401, messageBody("invalid token")))
        assertFalse(isClockSkew(401, null))
        assertFalse(isClockSkew(401, messageBody("jwt expired")))
    }

    private fun errorBody(code: String, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    private fun messageBody(message: String): JsonObject = buildJsonObject {
        put("message", message)
    }

    // ── The degraded mode ──────────────────────────────────────────────────

    @Test
    fun `a build with no project configured offers no cloud`() {
        assertFalse(SupabaseConfig("", "").isConfigured)
        assertFalse(SupabaseConfig("https://project.supabase.co", "").isConfigured)
        assertFalse(SupabaseConfig("", "anon-key").isConfigured)
        assertFalse(SupabaseConfig("   ", "   ").isConfigured)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `an unconfigured build has no client and therefore no provider`() {
        val store = newStore()

        // This is the state a fresh clone is in: both BuildConfig fields default to
        // empty, and empty means "cloud features are not offered" rather than
        // "misconfigured". The app then runs on LocalBeatsProvider and never mentions
        // signing in.
        assertNull(SupabaseClient.create(SupabaseConfig("", ""), store, SilentStorageLog))
        assertNull(SupabaseBeatsProvider.create(null, session()))
    }

    @Test
    fun `a configured build with nobody signed in still has no provider`() {
        val client = SupabaseClient.create(config, newStore(), SilentStorageLog)

        assertNotNull(client)
        assertNull("no session, no cloud library", SupabaseBeatsProvider.create(client, null))
        assertNotNull(SupabaseBeatsProvider.create(client, session()))
    }

    private fun session() = AuthSession(
        accessToken = "access",
        refreshToken = "refresh",
        expiresAtEpochSeconds = nowEpochSeconds() + 3600,
        userId = "user-1",
        email = "a@b.c",
        displayName = "A Devotee",
    )

    // ── The persisted session ──────────────────────────────────────────────

    @Test
    fun `a session survives being written and read back`() {
        assertEquals(session(), SessionJson.decode(SessionJson.encode(session())))
    }

    @Test
    fun `a session with no email or display name still round-trips`() {
        val anonymous = session().copy(email = null, displayName = null)

        assertEquals(anonymous, SessionJson.decode(SessionJson.encode(anonymous)))
    }

    @Test
    fun `a damaged session reads as signed out rather than throwing`() {
        // Losing a session costs a re-sign-in; refusing to launch over one does not.
        assertNull(SessionJson.decode(null))
        assertNull(SessionJson.decode(""))
        assertNull(SessionJson.decode("{{{"))
        assertNull(SessionJson.decode("[]"))
        assertNull(SessionJson.decode("""{"accessToken":"a"}"""))
        assertNull(SessionJson.decode("""{"accessToken":"a","refreshToken":"r","expiresAtEpochSeconds":"soon","userId":"u"}"""))
    }

    @Test
    fun `a session is refreshed before it expires, not after`() {
        val now = 1_000_000L

        assertFalse(session().copy(expiresAtEpochSeconds = now + 3600).needsRefresh(now))
        // Inside the margin: refreshing here is what stops a request racing the
        // server's clock and failing with a 401 the user cannot do anything about.
        assertTrue(session().copy(expiresAtEpochSeconds = now + 30).needsRefresh(now))
        assertTrue(session().copy(expiresAtEpochSeconds = now).needsRefresh(now))
        assertTrue(session().copy(expiresAtEpochSeconds = now - 1).needsRefresh(now))
    }

    // ── Timestamps ─────────────────────────────────────────────────────────

    @Test
    fun `a postgres timestamp with an offset parses like the zulu form`() {
        // PostgREST sends `+00:00`, which Instant.parse refuses outright.
        assertEquals(
            parseTimestamp("2026-09-16T02:11:04Z"),
            parseTimestamp("2026-09-16T02:11:04+00:00"),
        )
        assertTrue(parseTimestamp("2026-09-16T02:11:04Z")!! > parseTimestamp("2026-09-15T02:11:04Z")!!)
        assertNull(parseTimestamp("not a timestamp"))
        assertNull(parseTimestamp(null))
        assertNull(parseTimestamp(""))
    }

    @Test
    fun `a json string primitive reads as text and anything else does not`() {
        assertEquals("abc", JsonPrimitive("abc").stringContent())
        assertEquals("42", JsonPrimitive(42).stringContent())
        assertNull(buildJsonObject { }.stringContent())
        assertNull((null as JsonElement?).stringContent())
    }
}
