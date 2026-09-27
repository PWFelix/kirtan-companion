import Foundation

// The one Supabase client for the whole app: its configuration, the PostgREST transport both
// cloud paths share, and — the half that cannot be a port — auth.
//
// WHY IT CAN BE NULL. The keys are compiled in at build time and are absent on a fresh clone.
// Rather than throw — which would brick the app for anyone doing local UI work without a
// project — `create` returns nil when either value is blank, and every cloud affordance
// checks that before offering itself. THE DEGRADED MODE IS THE DEFAULT STATE OF A CLONE, not
// an error path.
//
// WHY AUTH IS NOT A PORT. `supabase-js` signs in by redirecting the browser back to the app's
// own origin. None of that exists on a device, so this implements the authorization-code flow
// WITH PKCE by hand:
//   1. `beginOAuth` mints a code verifier and a state, PERSISTS both, and returns the
//      `/auth/v1/authorize` URL for the caller to open in an in-app browser.
//   2. GoTrue redirects to `kirtan://auth/callback` — a custom-scheme deep link that brings
//      the app back to the front with `?code=…&state=…`.
//   3. `completeOAuth` checks the state, exchanges the code and the verifier at
//      `/auth/v1/token?grant_type=pkce`, and stores the session.
// The verifier and the session are persisted rather than held in memory, because the round
// trip LEAVES THE PROCESS and the session must survive it.

final class SupabaseClient {

    let config: SupabaseConfig
    private let http: URLSession
    private let store: KirtanStore
    private let log: StorageLog

    /// The signed-in user, or null. Published after restore / sign-in / sign-out.
    var session: AuthSession?

    private static let json = "application/json"
    private static let pgrstObject = "application/vnd.pgrst.object+json"
    private static let returnRepresentation = "return=representation"
    private static let defaultExpiresIn = 3600
    private static let clockSkewAttempts = 2
    private static let clockSkewRetrySeconds: UInt64 = 2

    private init(config: SupabaseConfig, http: URLSession, store: KirtanStore, log: StorageLog) {
        self.config = config
        self.http = http
        self.store = store
        self.log = log
    }

    // ── Construction ───────────────────────────────────────────────────────

    /// Build the client, or NULL when this build has no Supabase project.
    static func create(config: SupabaseConfig, store: KirtanStore, log: StorageLog = OSStorageLog(), http: URLSession = URLSession(configuration: .default)) -> SupabaseClient? {
        guard config.isConfigured else { return nil }
        return SupabaseClient(config: config, http: http, store: store, log: log)
    }

    /// `create` with the keys the build was compiled in.
    static func fromBundle(store: KirtanStore, log: StorageLog = OSStorageLog(), http: URLSession = URLSession(configuration: .default)) -> SupabaseClient? {
        create(config: SupabaseConfig.fromBundle(), store: store, log: log, http: http)
    }

    // ── Session persistence (the SessionStore, inlined) ────────────────────
    // READING never throws: a blob that will not decode means "signed out", a state the app
    // handles. WRITING throws StorageError: a sign-in whose session is not persisted looks
    // exactly like a successful one until the next launch.

    private func readStoredSession() async -> AuthSession? {
        let raw = await store.read { $0.string(KirtanStore.Keys.authSession) }
        return SessionJson.decode(raw)
    }

    private func persistSession(_ session: AuthSession) async throws {
        do {
            try await store.perform { $0.setString(KirtanStore.Keys.authSession, SessionJson.encode(session)) }
        } catch {
            throw StorageError(.unavailable, "This device wouldn't let the app keep you signed in, so that couldn't be completed.", error)
        }
    }

    private func clearStoredSession() async {
        try? await store.perform { $0.setString(KirtanStore.Keys.authSession, nil) }
    }

    private func readPending() async -> (verifier: String, state: String)? {
        await store.read { ctx -> (String, String)? in
            guard let verifier = ctx.string(KirtanStore.Keys.authPendingVerifier) else { return nil }
            return (verifier, ctx.string(KirtanStore.Keys.authPendingState) ?? "")
        }
    }

    private func writePending(verifier: String, state: String) async {
        try? await store.perform { ctx in
            ctx.setString(KirtanStore.Keys.authPendingVerifier, verifier)
            ctx.setString(KirtanStore.Keys.authPendingState, state)
        }
    }

    private func clearPending() async {
        try? await store.perform { ctx in
            ctx.setString(KirtanStore.Keys.authPendingVerifier, nil)
            ctx.setString(KirtanStore.Keys.authPendingState, nil)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /// Read the stored session, refreshing it if it is near expiry, and publish it. Called
    /// once at launch. A session that cannot be restored or refreshed is not an error: the
    /// user is signed out, which is a state the app already handles.
    @discardableResult
    func restore() async -> AuthSession? {
        let stored = await readStoredSession()
        guard let stored else {
            session = nil
            return nil
        }
        do {
            let fresh: AuthSession
            if stored.needsRefresh() {
                fresh = try await refresh(stored)
            } else {
                fresh = stored
            }
            if fresh != stored { try await persistSession(fresh) }
            session = fresh
            return fresh
        } catch {
            log.warn("[supabase] stored session could not be restored; signed out", error)
            await clearStoredSession()
            session = nil
            return nil
        }
    }

    // ── Sign-in ────────────────────────────────────────────────────────────

    /// Start the browser half of an OAuth sign-in and return the URL to open.
    func beginOAuth(provider: String = "google") async -> String {
        let verifier = Pkce.verifier()
        let state = Pkce.state()
        await writePending(verifier: verifier, state: state)
        return oauthAuthorizeUrl(config: config, provider: provider, redirectUri: supabaseRedirectUri, challenge: Pkce.challenge(verifier), state: state)
    }

    /// Finish an OAuth sign-in from the deep link the browser came back with.
    func completeOAuth(callbackUrl: String) async throws -> AuthSession {
        guard let callback = parseOAuthCallback(callbackUrl) else {
            throw StorageError(.unknown, "That sign-in link didn't carry a code, so the sign-in couldn't be finished. Try again.")
        }
        let pending = await readPending()
        // The pending pair is cleared whatever happens: a code is single-use, and leaving it
        // behind lets a stale link be replayed into a confusing failure.
        await clearPending()
        guard let pending else {
            throw StorageError(.unknown, "The sign-in took too long and expired. Try again.")
        }
        if let state = callback.state, state != pending.state {
            throw StorageError(.unknown, "That sign-in didn't come from this app, so it was refused.")
        }
        let body = JValue.object([JPair(key: "auth_code", value: .str(callback.code)), JPair(key: "code_verifier", value: .str(pending.verifier))])
        guard let token = try await auth(path: "token", query: [("grant_type", "pkce")], body: body, bearer: nil, whileDoing: "finish signing in") else {
            throw StorageError(.unknown, "The server didn't return a session, so the sign-in couldn't be finished.")
        }
        return try await storeSession(token)
    }

    /// Email + password sign-in.
    func signInWithPassword(email: String, password: String) async throws -> AuthSession {
        let body = JValue.object([JPair(key: "email", value: .str(email)), JPair(key: "password", value: .str(password))])
        guard let token = try await auth(path: "token", query: [("grant_type", "password")], body: body, bearer: nil, whileDoing: "sign in") else {
            throw signedOutWithoutSession()
        }
        return try await storeSession(token)
    }

    /// Create an account. Returns the session, or NULL when the project requires email
    /// confirmation — GoTrue then returns the user with no tokens, and the only honest thing
    /// the UI can say is "check your inbox".
    func signUp(email: String, password: String, name: String?) async throws -> AuthSession? {
        var pairs: [JPair] = [JPair(key: "email", value: .str(email)), JPair(key: "password", value: .str(password))]
        if let name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
            pairs.append(JPair(key: "data", value: JValue.object([JPair(key: "name", value: .str(name))])))
        }
        guard let result = try await auth(path: "signup", query: [], body: JValue.object(pairs), bearer: nil, whileDoing: "create that account") else {
            return nil
        }
        guard result.element("access_token") != nil else { return nil }
        return try await storeSession(result)
    }

    /// Sign out locally and tell the server. A refused logout still signs out here: the local
    /// session is the part that matters, and the server's revocation must not strand the user
    /// signed-in-looking on this device.
    func signOut() async {
        let stored = await readStoredSession()
        let current = session ?? stored
        session = nil
        await clearStoredSession()
        guard let current else { return }
        do {
            _ = try await auth(path: "logout", query: [], body: nil, bearer: current.accessToken, whileDoing: "sign out")
        } catch {
            log.warn("[supabase] server sign-out failed; the local session was cleared", error)
        }
    }

    // ── Access token ───────────────────────────────────────────────────────

    /// A valid access token, refreshing the stored one first if it is close to expiry.
    func accessToken() async throws -> String {
        let stored = await readStoredSession()
        let current = session ?? stored
        guard let current else {
            throw StorageError(.unavailable, "You're signed out, so that couldn't be saved. Sign in and try again.")
        }
        if !current.needsRefresh() { return current.accessToken }
        let fresh = try await refresh(current)
        try await persistSession(fresh)
        session = fresh
        return fresh.accessToken
    }

    private func refresh(_ session: AuthSession) async throws -> AuthSession {
        let body = JValue.object([JPair(key: "refresh_token", value: .str(session.refreshToken))])
        guard let token = try await auth(path: "token", query: [("grant_type", "refresh_token")], body: body, bearer: nil, whileDoing: "refresh your sign-in") else {
            throw signedOutWithoutSession()
        }
        return try sessionOf(token, fallback: session)
    }

    private func storeSession(_ token: JValue) async throws -> AuthSession {
        let session = try sessionOf(token, fallback: nil)
        try await persistSession(session)
        self.session = session
        return session
    }

    private func signedOutWithoutSession() -> StorageError {
        StorageError(.unavailable, "That didn't return a session, so you're still signed out. Try again.")
    }

    // ── PostgREST ──────────────────────────────────────────────────────────

    /// Read rows. `one` asks PostgREST for a single object (the `.single()` of the web
    /// provider, whose "no rows" failure is `PGRST116` → notFound). `requireSession` false
    /// sends the anon key instead of a user token — only the community library may do this,
    /// whose select policy is `using (true)`, so browsing works signed-out.
    func select(table: String, columns: String, filters: [(String, String)] = [], order: String? = nil, limit: Int? = nil, one: Bool = false, requireSession: Bool = true, whileDoing: String) async throws -> JValue? {
        try await send(method: "GET", url: restUrl(config: config, table: table, filters: [("select", columns)] + filters, order: order, limit: limit), body: nil, accept: one ? Self.pgrstObject : Self.json, prefer: nil, useSessionToken: requireSession, whileDoing: whileDoing)
    }

    /// Insert one row or an array, returning what was written.
    @discardableResult
    func insert(table: String, body: JValue, one: Bool = false, whileDoing: String) async throws -> JValue? {
        try await send(method: "POST", url: restUrl(config: config, table: table, filters: []), body: body.write(), accept: one ? Self.pgrstObject : Self.json, prefer: Self.returnRepresentation, useSessionToken: true, whileDoing: whileDoing)
    }

    /// Update the rows `filters` select; only the columns present in `body` are touched.
    @discardableResult
    func update(table: String, body: JValue, filters: [(String, String)], one: Bool = false, whileDoing: String) async throws -> JValue? {
        try await send(method: "PATCH", url: restUrl(config: config, table: table, filters: filters), body: body.write(), accept: one ? Self.pgrstObject : Self.json, prefer: Self.returnRepresentation, useSessionToken: true, whileDoing: whileDoing)
    }

    @discardableResult
    func delete(table: String, filters: [(String, String)], whileDoing: String) async throws -> JValue? {
        try await send(method: "DELETE", url: restUrl(config: config, table: table, filters: filters), body: nil, accept: Self.json, prefer: nil, useSessionToken: true, whileDoing: whileDoing)
    }

    /// Insert-or-update by the named conflict column — the web provider's `upsert(…, onConflict:)`.
    @discardableResult
    func upsert(table: String, body: JValue, onConflict: String, whileDoing: String) async throws -> JValue? {
        try await send(method: "POST", url: restUrl(config: config, table: table, filters: [("on_conflict", onConflict)]), body: body.write(), accept: Self.json, prefer: "\(Self.returnRepresentation), resolution=merge-duplicates", useSessionToken: true, whileDoing: whileDoing)
    }

    /// Call a `security definer` RPC — the copy counter's `increment_published_copies`.
    @discardableResult
    func rpc(function: String, args: JValue, whileDoing: String) async throws -> JValue? {
        try await send(method: "POST", url: restUrl(config: config, table: "rpc/\(function)", filters: []), body: args.write(), accept: Self.json, prefer: nil, useSessionToken: true, whileDoing: whileDoing)
    }

    // ── Transport ──────────────────────────────────────────────────────────

    /// One HTTP call, with the clock-skew retry.
    private func send(method: String, url: String, body: String?, accept: String, prefer: String?, useSessionToken: Bool, whileDoing: String) async throws -> JValue? {
        for attempt in 0..<Self.clockSkewAttempts {
            let bearer = useSessionToken ? try await accessToken() : config.anonKey
            var request = URLRequest(url: URL(string: url)!)
            request.httpMethod = method
            request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
            request.setValue(accept, forHTTPHeaderField: "Accept")
            if let prefer { request.setValue(prefer, forHTTPHeaderField: "Prefer") }
            if let body {
                request.setValue(Self.json, forHTTPHeaderField: "Content-Type")
                request.httpBody = Data(body.utf8)
            }

            var data = Data()
            var status = 0
            do {
                let (body, response) = try await http.data(for: request)
                data = body
                status = (response as? HTTPURLResponse)?.statusCode ?? 0
            } catch let error as CancellationError {
                throw error
            } catch {
                // A refused connection, a DNS failure, a timeout: nothing reached the
                // server, a different fact from "the server said no".
                throw StorageError(.network, "Couldn't reach the server, so \(whileDoing) didn't go through. Check your connection and try again.", error)
            }

            let text = String(data: data, encoding: .utf8) ?? ""
            if (200..<300).contains(status) {
                // A 204 from a DELETE carries no body, and that is a success.
                return text.isEmpty ? nil : try parseOrThrow(text, whileDoing: whileDoing)
            }
            let failure = JsonEl.parse(text)
            if attempt + 1 < Self.clockSkewAttempts, isClockSkew(status: status, body: failure) {
                try await Task.sleep(nanoseconds: Self.clockSkewRetrySeconds * 1_000_000_000)
                continue
            }
            throw restFailure(status: status, body: failure, whileDoing: whileDoing)
        }
        throw restFailure(status: 0, body: nil, whileDoing: whileDoing)
    }

    /// A GoTrue call: anon-key auth, no session token, no clock-skew retry loop.
    private func auth(path: String, query: [(String, String)], body: JValue?, bearer: String?, whileDoing: String) async throws -> JValue? {
        let urlString = authUrl(config: config, path: path, query: query)
        guard let url = URL(string: urlString) else { throw StorageError(.unknown, "The server address in this build is not valid.") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \((bearer ?? config.anonKey))", forHTTPHeaderField: "Authorization")
        request.setValue(Self.json, forHTTPHeaderField: "Accept")
        request.setValue(Self.json, forHTTPHeaderField: "Content-Type")
        request.httpBody = Data((body ?? JValue.object([])).write().utf8)

        var data = Data()
        var status = 0
        do {
            let (body, response) = try await http.data(for: request)
            data = body
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
        } catch let error as CancellationError {
            throw error
        } catch {
            throw StorageError(.network, "Couldn't reach the server, so that didn't go through. Check your connection and try again.", error)
        }
        let text = String(data: data, encoding: .utf8) ?? ""
        let parsed = JsonEl.parse(text)
        if !(200..<300).contains(status) {
            throw authFailure(status: status, body: parsed, whileDoing: whileDoing)
        }
        return parsed
    }

    private func parseOrThrow(_ text: String, whileDoing: String) throws -> JValue {
        guard let value = JsonEl.parse(text) else {
            throw StorageError(.unknown, "The server sent something this app couldn't read while trying to \(whileDoing).")
        }
        return value
    }

    /// Turn a GoTrue token response into a session. `user_metadata` is read for the display
    /// name exactly as the web does — `name`, then `full_name`, then the local part of the
    /// email — so the author name on a published beat is the same on both platforms.
    private func sessionOf(_ token: JValue?, fallback: AuthSession?) throws -> AuthSession {
        let user = token?.element("user")
        let metadata = user?.element("user_metadata")
        let userId = user?.stringField("id") ?? fallback?.userId
        guard let userId else {
            throw StorageError(.unknown, "The server didn't say who you are, so that sign-in couldn't be completed.")
        }
        let accessToken = token?.stringField("access_token") ?? fallback?.accessToken
        let refreshToken = token?.stringField("refresh_token") ?? fallback?.refreshToken
        guard let accessToken, let refreshToken else { throw signedOutWithoutSession() }
        let expiresIn = token?.element("expires_in")?.asNumber().map { Int($0) } ?? Self.defaultExpiresIn
        let email = user?.stringField("email") ?? fallback?.email
        let localPart = email?.split(separator: "@").first.map(String.init)
        let displayName = metadata?.stringField("name") ?? metadata?.stringField("full_name") ?? localPart ?? fallback?.displayName
        return AuthSession(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAtEpochSeconds: nowEpochSeconds() + expiresIn,
            userId: userId,
            email: email,
            displayName: displayName,
        )
    }
}
