package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The one Supabase client for the whole app: its configuration, the PostgREST
 * transport both cloud paths share, and — the half that cannot be a port — auth.
 *
 * Mirrors `src/storage/supabaseClient.js`, which exists so there is a single
 * session and a single socket and so swapping config is one place. The web app's
 * `supabase.auth.*` and `supabase.from(table).*` are both methods on one object for
 * the same reason, and that shape is kept here.
 *
 * ── WHY IT CAN BE NULL ──
 * The keys come from `local.properties` at build time and are absent on a fresh
 * clone. Rather than throw — which would brick the app for anyone doing local UI
 * work without a project — [create] returns null when either value is blank, and
 * every cloud affordance checks that before offering itself. The app then runs on
 * [LocalBeatsProvider] and simply does not mention signing in. THIS DEGRADED MODE
 * IS THE DEFAULT STATE OF A CLONE, not an error path: it is what the build ships
 * with until someone puts keys in `local.properties`.
 *
 * ── WHY AUTH IS NOT A PORT ──
 * `supabase-js` signs in by redirecting the browser: `signInWithOAuth` sets
 * `redirectTo: window.location.origin`, GoTrue bounces back to the app's own URL
 * with the code in the fragment, and `detectSessionInUrl` picks it up. None of
 * that exists on a device — there is no origin, no URL to detect a session in, and
 * no cookie jar. So this implements the authorization-code flow WITH PKCE by hand:
 *
 *   1. [beginOAuth] mints a code verifier and a state, PERSISTS both, and returns
 *      the `/auth/v1/authorize` URL for the caller to open in a Custom Tab.
 *   2. GoTrue redirects to [REDIRECT_URI] — a custom-scheme deep link that brings
 *      the app back to the front with `?code=…&state=…`.
 *   3. [completeOAuth] checks the state, exchanges the code and the verifier at
 *      `/auth/v1/token?grant_type=pkce`, and stores the session.
 *
 * The verifier is persisted rather than held in memory because the round trip
 * LEAVES THE PROCESS: Android is free to kill the app while the browser is in
 * front, and a verifier that died with it is a sign-in that fails on the way back
 * with no explanation. The session is persisted for the same reason, which is what
 * "stay signed in across process death" actually requires here.
 *
 * ── WHAT THE HOST APP MUST DECLARE ──
 * Two things outside this package, both documented on [REDIRECT_URI]: an
 * intent-filter for the `kirtan://auth` scheme+host in `AndroidManifest.xml`, and
 * the same URI in the Supabase project's Redirect URLs allowlist. Without the
 * first the browser has nowhere to hand the code back; without the second GoTrue
 * refuses the flow at step 1.
 */
class SupabaseClient private constructor(
    val config: SupabaseConfig,
    private val http: HttpClient,
    private val sessions: SessionStore,
    private val log: StorageLog,
) {

    private val _session = MutableStateFlow<AuthSession?>(null)

    /**
     * The signed-in user, or null.
     *
     * A [StateFlow] because a sign-in has to swap the whole library from device to
     * cloud while the app is running — the case `src/storage/index.js` says is when
     * a module singleton stops being enough. [LibraryRepository] collects this.
     */
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Read the stored session, refreshing it if it has expired, and publish it.
     *
     * Called once at launch. A session that cannot be restored or refreshed is not
     * an error: the user is signed out, which is a state the app already handles.
     */
    suspend fun restore(): AuthSession? {
        val stored = sessions.read()
        if (stored == null) {
            _session.value = null
            return null
        }
        return try {
            val fresh = if (stored.needsRefresh()) refresh(stored) else stored
            if (fresh !== stored) sessions.write(fresh)
            _session.value = fresh
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[supabase] stored session could not be restored; signed out", e)
            sessions.clearQuietly()
            _session.value = null
            null
        }
    }

    // ── Sign-in ────────────────────────────────────────────────────────────

    /**
     * Start the browser half of an OAuth sign-in and return the URL to open.
     *
     * The caller opens it in a Custom Tab (or `ACTION_VIEW`) and waits for the deep
     * link back. The verifier and state are already on disk when this returns, so
     * [completeOAuth] works even if the process is replaced in between.
     */
    suspend fun beginOAuth(provider: String = "google"): String {
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        sessions.writePending(verifier, state)
        return oauthAuthorizeUrl(
            config = config,
            provider = provider,
            redirectUri = REDIRECT_URI,
            challenge = Pkce.challenge(verifier),
            state = state,
        )
    }

    /**
     * Finish an OAuth sign-in from the deep link the browser came back with.
     *
     * @param callbackUrl the intent's `dataString`, verbatim — `kirtan://auth/callback?code=…&state=…`.
     *   Taking the raw string rather than an `android.net.Uri` keeps this class out
     *   of the Android framework and therefore testable.
     * @throws StorageError when the state does not match (a link that did not come
     *   from our own [beginOAuth]), when the exchange is refused, or when the
     *   resulting session could not be persisted — that last one throws rather than
     *   being swallowed, because a sign-in that is not stored is a sign-in the user
     *   has to repeat on every launch with nothing to explain why.
     */
    suspend fun completeOAuth(callbackUrl: String): AuthSession {
        val callback = parseOAuthCallback(callbackUrl)
            ?: throw StorageError(
                StorageErrorCode.UNKNOWN,
                "That sign-in link didn't carry a code, so the sign-in couldn't be finished. Try again.",
            )
        val pending = sessions.readPending()
        // The pending pair is cleared whatever happens: a code is single-use, and
        // leaving it behind lets a stale link be replayed into a confusing failure.
        sessions.clearPending()
        if (pending == null) {
            throw StorageError(
                StorageErrorCode.UNKNOWN,
                "The sign-in took too long and expired. Try again.",
            )
        }
        if (callback.state != null && callback.state != pending.state) {
            throw StorageError(
                StorageErrorCode.UNKNOWN,
                "That sign-in didn't come from this app, so it was refused.",
            )
        }

        val body = buildJsonObject {
            put("auth_code", callback.code)
            put("code_verifier", pending.verifier)
        }
        val token = auth(
            path = "token",
            query = listOf("grant_type" to "pkce"),
            body = body,
            bearer = null,
            whileDoing = "finish signing in",
        ) ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "The server didn't return a session, so the sign-in couldn't be finished.",
        )
        return storeSession(token)
    }

    /** Email + password sign-in. Throws [StorageError] with a readable sentence. */
    suspend fun signInWithPassword(email: String, password: String): AuthSession {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val token = auth(
            path = "token",
            query = listOf("grant_type" to "password"),
            body = body,
            bearer = null,
            whileDoing = "sign in",
        ) ?: throw signedOutWithoutSession()
        return storeSession(token)
    }

    /**
     * Create an account.
     *
     * @return the session, or NULL when the project requires email confirmation —
     *   GoTrue then returns the user with no tokens, and the only honest thing the
     *   UI can say is "check your inbox". Not an exception: nothing failed.
     */
    suspend fun signUp(email: String, password: String, name: String?): AuthSession? {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            if (!name.isNullOrBlank()) put("data", buildJsonObject { put("name", name) })
        }
        val result = auth("signup", emptyList(), body, null, "create that account") ?: return null
        return if (result.containsKey("access_token")) storeSession(result) else null
    }

    /** Sign out locally and tell the server. A refused logout still signs out here. */
    suspend fun signOut() {
        val current = _session.value ?: sessions.read()
        _session.value = null
        sessions.clearQuietly()
        if (current == null) return
        try {
            auth("logout", emptyList(), null, current.accessToken, "sign out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local session is already gone, which is the part that matters:
            // the server's revocation is best-effort and must not strand the user
            // signed-in-looking on this device.
            log.warn("[supabase] server sign-out failed; the local session was cleared", e)
        }
    }

    /**
     * A valid access token, refreshing the stored one first if it is close to
     * expiry.
     *
     * @throws StorageError with [StorageErrorCode.UNAVAILABLE] when there is no
     *   session at all — the code the UI already reads as "sign in and try again".
     */
    suspend fun accessToken(): String {
        val current = _session.value ?: sessions.read()
            ?: throw StorageError(
                StorageErrorCode.UNAVAILABLE,
                "You're signed out, so that couldn't be saved. Sign in and try again.",
            )
        if (!current.needsRefresh()) return current.accessToken
        val fresh = refresh(current)
        sessions.write(fresh)
        _session.value = fresh
        return fresh.accessToken
    }

    private suspend fun refresh(session: AuthSession): AuthSession {
        val body = buildJsonObject { put("refresh_token", session.refreshToken) }
        val token = auth(
            path = "token",
            query = listOf("grant_type" to "refresh_token"),
            body = body,
            bearer = null,
            whileDoing = "refresh your sign-in",
        ) ?: throw signedOutWithoutSession()
        return sessionOf(token, fallback = session)
    }

    /** Turn a GoTrue token response into a session, persist it, and publish it. */
    private suspend fun storeSession(token: JsonObject): AuthSession {
        val session = sessionOf(token, fallback = null)
        sessions.write(session)
        _session.value = session
        return session
    }

    private fun signedOutWithoutSession() = StorageError(
        StorageErrorCode.UNAVAILABLE,
        "That didn't return a session, so you're still signed out. Try again.",
    )

    // ── PostgREST ──────────────────────────────────────────────────────────
    // The table surface `supabase-js` builds with a fluent chain, expressed as the
    // HTTP it emits: one URL with `column=op.value` filters, a JSON body, and the
    // `Prefer`/`Accept` headers that ask for the written row back.

    /**
     * Read rows.
     *
     * @param one when true, ask PostgREST for a single object
     *   (`Accept: application/vnd.pgrst.object+json`) — the `.single()` of the web
     *   provider, whose "no rows" failure arrives as `PGRST116` and maps to
     *   [StorageErrorCode.NOT_FOUND].
     * @param requireSession when false, send the anon key instead of resolving a
     *   user token. Only the community library may do this: its `published_beats`
     *   select policy is `using (true)`, so browsing works signed-out, and making
     *   a signed-out browse fail with "sign in and try again" would be a lie. Every
     *   private table stays true, where a missing session is the real reason the
     *   read cannot happen.
     */
    suspend fun select(
        table: String,
        columns: String,
        filters: List<Pair<String, String>> = emptyList(),
        order: String? = null,
        limit: Int? = null,
        one: Boolean = false,
        requireSession: Boolean = true,
        whileDoing: String,
    ): JsonElement? = send(
        method = HttpMethod.Get,
        url = restUrl(config, table, listOf("select" to columns) + filters, order, limit),
        body = null,
        accept = if (one) PGRST_OBJECT else JSON,
        prefer = null,
        useSessionToken = requireSession,
        whileDoing = whileDoing,
    )

    /** Insert one row or an array of rows, returning what was written. */
    suspend fun insert(
        table: String,
        body: JsonElement,
        one: Boolean = false,
        whileDoing: String,
    ): JsonElement? = send(
        method = HttpMethod.Post,
        url = restUrl(config, table, emptyList(), null, null),
        body = body.toString(),
        accept = if (one) PGRST_OBJECT else JSON,
        prefer = RETURN_REPRESENTATION,
        useSessionToken = true,
        whileDoing = whileDoing,
    )

    /**
     * Update the rows [filters] select, returning what was written.
     *
     * Only the columns present in [body] are touched, which is what lets
     * [SupabaseBeatsProvider.updateCategory] patch a playlist's order without
     * restating its name.
     */
    suspend fun update(
        table: String,
        body: JsonObject,
        filters: List<Pair<String, String>>,
        one: Boolean = false,
        whileDoing: String,
    ): JsonElement? = send(
        method = HttpMethod.Patch,
        url = restUrl(config, table, filters, null, null),
        body = body.toString(),
        accept = if (one) PGRST_OBJECT else JSON,
        prefer = RETURN_REPRESENTATION,
        useSessionToken = true,
        whileDoing = whileDoing,
    )

    suspend fun delete(
        table: String,
        filters: List<Pair<String, String>>,
        whileDoing: String,
    ): JsonElement? = send(
        method = HttpMethod.Delete,
        url = restUrl(config, table, filters, null, null),
        body = null,
        accept = JSON,
        prefer = null,
        useSessionToken = true,
        whileDoing = whileDoing,
    )

    /**
     * Insert-or-update by primary key — the web provider's
     * `upsert({…}, { onConflict: "id" })`, used for the profile row so a preference
     * never fails because the new-user trigger hasn't run yet.
     */
    suspend fun upsert(
        table: String,
        body: JsonElement,
        onConflict: String,
        whileDoing: String,
    ): JsonElement? = send(
        method = HttpMethod.Post,
        url = restUrl(config, table, listOf("on_conflict" to onConflict), null, null),
        body = body.toString(),
        accept = JSON,
        prefer = "$RETURN_REPRESENTATION, resolution=merge-duplicates",
        useSessionToken = true,
        whileDoing = whileDoing,
    )

    /** Call a `security definer` RPC — the copy counter's `increment_published_copies`. */
    suspend fun rpc(function: String, args: JsonObject, whileDoing: String): JsonElement? = send(
        method = HttpMethod.Post,
        url = restUrl(config, "rpc/$function", emptyList(), null, null),
        body = args.toString(),
        accept = JSON,
        prefer = null,
        useSessionToken = true,
        whileDoing = whileDoing,
    )

    /**
     * One HTTP call, with the web provider's clock-skew retry.
     *
     * RIGHT AFTER SIGN-IN the access token's "issued at" is now; if the database
     * node validating it runs a second behind the node that minted it, it reads the
     * token as issued in the future and rejects the request. It is a tiny
     * disagreement between Supabase's own servers and it clears itself, so one
     * delayed retry hides it — and it fires on the very first read after sign-in
     * more than anywhere, which is exactly when a failure would look like the cloud
     * is broken.
     */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        body: String?,
        accept: String,
        prefer: String?,
        useSessionToken: Boolean,
        whileDoing: String,
    ): JsonElement? {
        repeat(CLOCK_SKEW_ATTEMPTS) { attempt ->
            val bearer = if (useSessionToken) accessToken() else config.anonKey
            val response = try {
                http.request(url) {
                    this.method = method
                    header("apikey", config.anonKey)
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    header(HttpHeaders.Accept, accept)
                    if (prefer != null) header("Prefer", prefer)
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refused connection, a DNS failure, a timeout: nothing reached
                // the server, which is a different fact from "the server said no".
                throw StorageError(
                    StorageErrorCode.NETWORK,
                    "Couldn't reach the server, so $whileDoing didn't go through. Check your connection and try again.",
                    e,
                )
            }

            val text = response.bodyAsText()
            val status = response.status.value
            if (response.status.isSuccess()) {
                // 204 from a DELETE carries no body, and that is a success.
                return if (text.isBlank()) null else parseOrThrow(text, whileDoing)
            }

            val failure = text.parseJsonOrNull()
            if (attempt + 1 < CLOCK_SKEW_ATTEMPTS && isClockSkew(status, failure)) {
                delayBeforeRetry()
                return@repeat
            }
            throw restFailure(status, failure, whileDoing)
        }
        // Unreachable: the loop either returns or throws on its last attempt.
        throw restFailure(0, null, whileDoing)
    }

    /** A GoTrue call: anon-key auth, no session token, no clock-skew retry loop. */
    private suspend fun auth(
        path: String,
        query: List<Pair<String, String>>,
        body: JsonObject?,
        bearer: String?,
        whileDoing: String,
    ): JsonObject? {
        val response = try {
            http.request(authUrl(config, path, query)) {
                method = HttpMethod.Post
                header("apikey", config.anonKey)
                header(HttpHeaders.Authorization, "Bearer ${bearer ?: config.anonKey}")
                header(HttpHeaders.Accept, JSON)
                header(HttpHeaders.ContentType, JSON)
                setBody(TextContent((body ?: buildJsonObject { }).toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StorageError(
                StorageErrorCode.NETWORK,
                "Couldn't reach the server, so that didn't go through. Check your connection and try again.",
                e,
            )
        }

        val text = response.bodyAsText()
        val parsed = text.parseJsonOrNull()
        if (!response.status.isSuccess()) {
            throw authFailure(response.status.value, parsed, whileDoing)
        }
        return parsed as? JsonObject
    }

    private fun parseOrThrow(text: String, whileDoing: String): JsonElement =
        text.parseJsonOrNull() ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "The server sent something this app couldn't read while trying to $whileDoing.",
        )

    /**
     * Build a session from a GoTrue token response.
     *
     * `user_metadata` is read for the display name exactly as `useAuth.js` does —
     * `name`, then `full_name`, then the local part of the email — so the author
     * name on a published beat is the same on both platforms.
     */
    private fun sessionOf(token: JsonObject, fallback: AuthSession?): AuthSession {
        val user = token["user"] as? JsonObject
        val userId = user?.get("id")?.stringContent()
            ?: fallback?.userId
            ?: throw StorageError(
                StorageErrorCode.UNKNOWN,
                "The server didn't say who you are, so that sign-in couldn't be completed.",
            )
        val accessToken = token["access_token"]?.stringContent() ?: fallback?.accessToken
        val refreshToken = token["refresh_token"]?.stringContent() ?: fallback?.refreshToken
        if (accessToken == null || refreshToken == null) throw signedOutWithoutSession()

        val expiresIn = (token["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_IN
        val metadata = user?.get("user_metadata") as? JsonObject
        val email = user?.get("email")?.stringContent() ?: fallback?.email
        val displayName = metadata?.get("name")?.stringContent()
            ?: metadata?.get("full_name")?.stringContent()
            ?: email?.substringBefore("@")
            ?: fallback?.displayName

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
            userId = userId,
            email = email,
            displayName = displayName,
        )
    }

    /** Suspends between the two clock-skew attempts. Split out so a test can see it. */
    private suspend fun delayBeforeRetry() = kotlinx.coroutines.delay(CLOCK_SKEW_RETRY_MS)

    companion object {
        /**
         * Where GoTrue sends the browser back to.
         *
         * The scheme matches the one `AndroidManifest.xml` already declares for
         * inbound share links (`kirtan://beat`), with a distinct host so the two
         * deep links cannot be confused by the activity that receives them. THE
         * MANIFEST CURRENTLY DECLARES ONLY `host="beat"`, so it needs one more
         * data element inside MainActivity's VIEW intent-filter:
         *
         *     <data android:scheme="kirtan" android:host="auth" android:pathPrefix="/callback" />
         *
         * and the Supabase project needs the same URI in
         * Authentication → URL Configuration → Redirect URLs, or GoTrue rejects the
         * flow before the provider is ever reached.
         */
        const val REDIRECT_URI = "kirtan://auth/callback"

        private const val JSON = "application/json"
        private const val PGRST_OBJECT = "application/vnd.pgrst.object+json"
        private const val RETURN_REPRESENTATION = "return=representation"
        private const val DEFAULT_EXPIRES_IN = 3600L
        private const val CLOCK_SKEW_ATTEMPTS = 2
        private const val CLOCK_SKEW_RETRY_MS = 2_000L

        /**
         * Build the client, or NULL when this build has no Supabase project.
         *
         * Null is the answer the whole app is written around: [LibraryRepository]
         * stays on [LocalBeatsProvider], the sign-in sheet is never offered, and the
         * community tab says the library isn't set up in this build.
         */
        fun create(
            config: SupabaseConfig,
            store: DataStore<Preferences>,
            log: StorageLog = AndroidStorageLog,
            http: HttpClient = HttpClient(OkHttp),
        ): SupabaseClient? {
            if (!config.isConfigured) return null
            return SupabaseClient(config, http, SessionStore(store, log), log)
        }

        /** [create] with the keys the build was compiled with. */
        fun fromBuildConfig(
            store: DataStore<Preferences>,
            log: StorageLog = AndroidStorageLog,
            http: HttpClient = HttpClient(OkHttp),
        ): SupabaseClient? = create(SupabaseConfig.fromBuildConfig(), store, log, http)
    }
}

/**
 * Where the cloud is, and whether there is one.
 *
 * Both values come from `BuildConfig`, which `app/build.gradle.kts` fills from
 * `local.properties`. Both default to empty, and EMPTY MEANS NOT OFFERED rather
 * than misconfigured — the same contract the web app has with no `VITE_SUPABASE_*`
 * set. The anon key is public by design; Row-Level Security in
 * `supabase/schema.sql` is what stops one user reading another's beats. Never put
 * a `service_role` key in a client build.
 */
data class SupabaseConfig(val url: String, val anonKey: String) {

    /** True only when both values are present — the gate for every cloud feature. */
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    /** The project URL with no trailing slash, so paths can be appended directly. */
    val baseUrl: String get() = url.trimEnd('/')

    companion object {
        fun fromBuildConfig(): SupabaseConfig = SupabaseConfig(
            url = com.kirtan.companion.BuildConfig.SUPABASE_URL,
            anonKey = com.kirtan.companion.BuildConfig.SUPABASE_ANON_KEY,
        )
    }
}

/**
 * A signed-in user, as persisted.
 *
 * Deliberately a plain value with its own JSON rather than a GoTrue response kept
 * whole: the session has to survive process death, and the fields that matter
 * afterwards are the two tokens, the expiry, and who the user is.
 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    /** Seconds since the epoch. Refreshing early avoids racing the server's clock. */
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String?,
    /** The author name a published beat is credited to. */
    val displayName: String?,
) {
    fun needsRefresh(nowEpochSeconds: Long = nowEpochSeconds()): Boolean =
        nowEpochSeconds >= expiresAtEpochSeconds - REFRESH_MARGIN_SECONDS

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}

internal fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

/**
 * The session and the in-flight PKCE pair, on disk.
 *
 * TWO POSTURES IN ONE CLASS, and the split is deliberate:
 *  - READING never throws. A session blob that will not decode means "signed out",
 *    which is a state the app handles; refusing to launch over it would be worse
 *    than the corruption, and re-signing-in costs seconds.
 *  - WRITING throws [StorageError]. A sign-in whose session is not persisted looks
 *    exactly like a successful sign-in until the next launch, which is the silent
 *    loss rule 3 of the provider contract exists to prevent.
 */
internal class SessionStore(
    private val store: DataStore<Preferences>,
    private val log: StorageLog,
) {

    suspend fun read(): AuthSession? = try {
        SessionJson.decode(store.data.first()[Keys.AUTH_SESSION])
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[supabase] the stored session could not be read; treating it as signed out", e)
        null
    }

    suspend fun write(session: AuthSession) {
        write(Keys.AUTH_SESSION.name) { it[Keys.AUTH_SESSION] = SessionJson.encode(session) }
    }

    suspend fun clear() {
        write(Keys.AUTH_SESSION.name) { it.remove(Keys.AUTH_SESSION) }
    }

    /** [clear], for the paths where failing to forget is not worth surfacing. */
    suspend fun clearQuietly() {
        try {
            clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[supabase] the stored session could not be cleared", e)
        }
    }

    suspend fun readPending(): PendingOAuth? = try {
        val prefs = store.data.first()
        val verifier = prefs[Keys.AUTH_PENDING_VERIFIER] ?: return null
        PendingOAuth(verifier, prefs[Keys.AUTH_PENDING_STATE].orEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[supabase] the pending sign-in could not be read", e)
        null
    }

    suspend fun writePending(verifier: String, state: String) {
        write("the sign-in") { prefs ->
            prefs[Keys.AUTH_PENDING_VERIFIER] = verifier
            prefs[Keys.AUTH_PENDING_STATE] = state
        }
    }

    suspend fun clearPending() {
        try {
            write("the sign-in") { prefs ->
                prefs.remove(Keys.AUTH_PENDING_VERIFIER)
                prefs.remove(Keys.AUTH_PENDING_STATE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A leftover pair is single-use and gets overwritten by the next
            // beginOAuth, so failing to clear it is worth a log line and nothing more.
            log.warn("[supabase] the pending sign-in could not be cleared", e)
        }
    }

    private suspend fun write(
        whileDoing: String,
        apply: (MutablePreferences) -> Unit,
    ) {
        try {
            store.updateData { prefs -> prefs.toMutablePreferences().also(apply) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StorageError(
                StorageErrorCode.UNAVAILABLE,
                "This device wouldn't let the app keep you signed in, so $whileDoing couldn't be completed.",
                e,
            )
        }
    }
}

/** The PKCE pair for a sign-in currently in the browser's hands. */
internal data class PendingOAuth(val verifier: String, val state: String)

/** The session blob's JSON. Never throws on decode; see [SessionStore]. */
internal object SessionJson {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun encode(session: AuthSession): String = buildJsonObject {
        put("accessToken", session.accessToken)
        put("refreshToken", session.refreshToken)
        put("expiresAtEpochSeconds", session.expiresAtEpochSeconds)
        put("userId", session.userId)
        session.email?.let { put("email", it) }
        session.displayName?.let { put("displayName", it) }
    }.toString()

    fun decode(raw: String?): AuthSession? {
        if (raw == null) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }
        return AuthSession(
            accessToken = obj["accessToken"]?.stringContent() ?: return null,
            refreshToken = obj["refreshToken"]?.stringContent() ?: return null,
            expiresAtEpochSeconds = (obj["expiresAtEpochSeconds"] as? JsonPrimitive)?.longOrNull
                ?: return null,
            userId = obj["userId"]?.stringContent() ?: return null,
            email = obj["email"]?.stringContent(),
            displayName = obj["displayName"]?.stringContent(),
        )
    }
}

/**
 * PKCE, by hand.
 *
 * `supabase-js` does this inside its browser flow; there is no library for it here,
 * and it is four lines of RFC 7636: a high-entropy verifier, its SHA-256 as the
 * challenge, both base64url-encoded without padding. The verifier never leaves the
 * device until the token exchange, which is the whole point — an intercepted
 * authorization code is worthless without it.
 */
internal object Pkce {

    private val random = SecureRandom()

    /** 32 random bytes → 43 characters, inside the RFC's 43…128 window. */
    fun verifier(): String = base64Url(bytes(32))

    /** An unguessable anti-CSRF token for the round trip. */
    fun state(): String = base64Url(bytes(16))

    /** `BASE64URL(SHA256(ASCII(verifier)))`, the `S256` challenge method. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun bytes(count: Int) = ByteArray(count).also { random.nextBytes(it) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** The code and state GoTrue appended to the redirect. */
internal data class OAuthCallback(val code: String, val state: String?)

// ── Pure URL and error helpers ─────────────────────────────────────────────
// Everything below is free of HTTP, disk and Android, because it is the part
// worth testing: a wrong filter encoding or a mis-mapped status code is a bug no
// integration test on a laptop can reach, and both are trivially assertable here.

/**
 * The `/auth/v1/authorize` URL for a browser sign-in.
 *
 * `code_challenge_method=S256` is what makes GoTrue answer with `?code=` rather
 * than putting tokens in the fragment: the implicit flow would hand an access
 * token to whatever app is registered for the `kirtan://` scheme, and a code that
 * needs a verifier only this device holds does not.
 */
internal fun oauthAuthorizeUrl(
    config: SupabaseConfig,
    provider: String,
    redirectUri: String,
    challenge: String,
    state: String,
): String = URLBuilder("${config.baseUrl}/auth/v1/authorize").apply {
    parameters.append("provider", provider)
    parameters.append("redirect_to", redirectUri)
    parameters.append("code_challenge", challenge)
    parameters.append("code_challenge_method", "S256")
    parameters.append("state", state)
}.buildString()

/** Pull `code` and `state` out of a `kirtan://auth/callback?…` deep link. */
internal fun parseOAuthCallback(callbackUrl: String): OAuthCallback? = try {
    val url = Url(callbackUrl)
    val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
    OAuthCallback(code, url.parameters["state"])
} catch (e: Exception) {
    null
}

/**
 * A PostgREST URL: `…/rest/v1/<table>?<filters>&order=…&limit=…`.
 *
 * Filters are `column=op.value` pairs — `id=eq.<uuid>`, `name=ilike.%text%` — and
 * Ktor's parameter builder is what percent-encodes them, which matters: a playlist
 * name containing `&` or `#` would otherwise truncate the query and silently
 * select the wrong rows.
 */
internal fun restUrl(
    config: SupabaseConfig,
    table: String,
    filters: List<Pair<String, String>>,
    order: String?,
    limit: Int?,
): String = URLBuilder("${config.baseUrl}/rest/v1/$table").apply {
    filters.forEach { (key, value) -> parameters.append(key, value) }
    if (order != null) parameters.append("order", order)
    if (limit != null) parameters.append("limit", limit.toString())
}.buildString()

internal fun authUrl(config: SupabaseConfig, path: String, query: List<Pair<String, String>>): String =
    URLBuilder("${config.baseUrl}/auth/v1/$path").apply {
        query.forEach { (key, value) -> parameters.append(key, value) }
    }.buildString()

/**
 * A Postgres/PostgREST failure, mapped onto the code the app branches on.
 *
 * The web provider's `mapError`, with the same four rules and the same reasoning:
 * a unique-key violation is a CONFLICT, PostgREST's "no rows for `.single()`" is
 * NOT_FOUND, and anything the server refused for permission reasons is UNAVAILABLE
 * with a sentence that tells the user to sign in — because on a client holding only
 * the anon key, a 401/403 nearly always means the session expired or Row-Level
 * Security decided the row isn't theirs.
 */
internal fun restFailure(status: Int, body: JsonElement?, whileDoing: String): StorageError {
    val code = (body as? JsonObject)?.get("code")?.stringContent()
    val detail = (body as? JsonObject)?.get("message")?.stringContent().orEmpty()
    return when {
        code == "23505" -> StorageError(
            StorageErrorCode.CONFLICT,
            "That already exists.",
        )
        code == "PGRST116" -> StorageError(
            StorageErrorCode.NOT_FOUND,
            "$whileDoing no longer exists.",
        )
        code == "42501" || status == 401 || status == 403 -> StorageError(
            StorageErrorCode.UNAVAILABLE,
            "You're signed out, so that couldn't be saved. Sign in and try again.",
        )
        else -> StorageError(
            StorageErrorCode.UNKNOWN,
            "Something went wrong with $whileDoing. $detail".trim(),
        )
    }
}

/**
 * A GoTrue failure. Bad credentials are the common case and get their own sentence;
 * everything else is reported as unavailable, because from here a 400 from the auth
 * server and a 400 from PostgREST are the same fact — the server said no.
 */
internal fun authFailure(status: Int, body: JsonElement?, whileDoing: String): StorageError {
    val obj = body as? JsonObject
    val description = obj?.get("error_description")?.stringContent()
        ?: obj?.get("msg")?.stringContent()
        ?: obj?.get("message")?.stringContent()
        .orEmpty()
    val message = when {
        status == 400 && "invalid" in description.lowercase() ->
            "That email and password didn't match. Try again."
        status == 401 || status == 403 ->
            "You're signed out, so that couldn't be saved. Sign in and try again."
        description.isNotBlank() -> "Couldn't $whileDoing. $description"
        else -> "Couldn't $whileDoing. The server refused the request."
    }
    return StorageError(
        if (status == 401 || status == 403) StorageErrorCode.UNAVAILABLE else StorageErrorCode.UNKNOWN,
        message.trim(),
    )
}

/**
 * The clock-skew race, recognised from either a GoTrue or a PostgREST body.
 * See [SupabaseClient.send] for why one delayed retry is the right fix.
 */
internal fun isClockSkew(status: Int, body: JsonElement?): Boolean {
    if (status != 401 && status != 403) return false
    val text = ((body as? JsonObject)?.get("message")?.stringContent()
        ?: (body as? JsonObject)?.get("error_description")?.stringContent()
        ?: (body as? JsonObject)?.get("msg")?.stringContent())
        ?.lowercase() ?: return false
    return "issued at future" in text || ("jwt" in text && "future" in text)
}

/**
 * The text of a JSON string primitive, or null for an object or an array.
 *
 * A NUMBER primitive still yields its text: PostgREST returns a `uuid` or a `text`
 * column as a JSON string, but a hand-run query or a future column could return one
 * bare, and refusing an id over that would fail a whole library load.
 */
internal fun JsonElement?.stringContent(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

/** Parse JSON without throwing — a server's error body is not always JSON. */
internal fun String.parseJsonOrNull(): JsonElement? = try {
    Json.parseToJsonElement(this)
} catch (e: Exception) {
    null
}
