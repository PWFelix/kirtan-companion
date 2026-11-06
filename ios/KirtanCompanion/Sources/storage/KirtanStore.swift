import Foundation

// The on-device plumbing every local path shares: ONE key-value store, its keys, and the
// schema stamp. Replaces Android's single Preferences DataStore.
//
// ── ONE FILE, TWO POSTURES ──
// src/storage/eqPrefs.js is a separate FILE from the beat library and its header explains
// why: the library is shaped like the database it becomes (rows, read-modify-write lists,
// errors that surface) while mixer settings are a small blob of cosmetic preference whose
// failures must degrade to flat rather than brick the app. That asymmetry is real and is
// preserved in the CODE — but it lives in behaviour, not file count. On iOS a second disk
// writer is a second chance for the two to disagree about what "now" is, for no benefit:
// LocalBeatsProvider throws and EqPrefs never does, and one JSON file holds both without
// either being able to corrupt the other's keys.
//
// ── ONE INSTANCE, OR IT DEADLOCKS ──
// Every read and write is serialised on a single queue, which is what makes a
// read-modify-write atomic (two tasks creating beats at once cannot lose one — the thing
// the web app's getItem/setItem pair got wrong). Exactly one instance per process; the
// composition root creates it and hands the same store to both consumers.
//
// ── WHAT A VALUE IS ──
// Two kinds only: a String (any JSON blob, or a plain token like "builtin") and an Int
// (the schema stamp). That matches what the keys actually hold and nothing else. The
// beats and categories arrays are JSON STRINGS under string keys — same as the Android
// DataStore, same as the web localStorage.
//
// ── KEY NAMES ──
// The library keys carry the web app's kirtan.v2. namespace. Not because the two stores
// ever meet — a browser's localStorage and an app's file are different disks — but because
// the version marker is part of the SCHEMA, and a schema that means the same thing on both
// platforms is one less thing to translate when someone reads a cloud row written by the
// other.

final class KirtanStore {

    /// Every key this package writes, in one place.
    enum Keys {
        /// The user's beats, as one JSON array — see LibraryJson.
        static let beats = "kirtan.v2.beats"
        /// The user's categories, as one JSON array.
        static let categories = "kirtan.v2.categories"
        /// The id of the category Home cycles within. May hold "builtin"/"custom".
        static let activeCategory = "kirtan.v2.activeCategory"
        /// The layout version of the three keys above.
        static let schemaVersion = "kirtan.schemaVersion"
        /// The whole mixer/notation snapshot as one JSON blob — see EqPrefs.
        static let mixerPrefs = "kirtan.mixer.prefs"
        /// The persisted Supabase session, as one JSON blob.
        static let authSession = "kirtan.auth.session"
        /// The PKCE code verifier for an in-flight browser sign-in.
        static let authPendingVerifier = "kirtan.auth.pendingVerifier"
        /// The CSRF `state` paired with the pending verifier.
        static let authPendingState = "kirtan.auth.pendingState"
        /// The server's built-in beat rows, cached verbatim as one JSON array.
        static let shippedRows = "kirtan.shipped.rows"
        /// The newest `updated_at` among the shipped rows.
        static let shippedRevision = "kirtan.shipped.revision"
    }

    /// The schema version this build writes. The web app's v2 — the namespaced, all-JSON,
    /// all-UUID layout. There is no v1→v2 step: the first install writes v2 directly. The
    /// stamp earns its place because the next shape change is a one-line
    /// `if version < N { migrate }` instead of an archaeology problem.
    static let schemaVersion = 2

    // ── A value: the only two kinds the keys hold ──
    private enum Value {
        case string(String)
        case int(Int)

        var json: Any {
            switch self {
            case .string(let s): return s
            case .int(let i): return i
            }
        }
        init(json: Any) {
            if let s = json as? String { self = .string(s) }
            else if let i = json as? Int { self = .int(i) }
            else if let n = json as? Double, n == n.rounded() { self = .int(Int(n)) }
            else { self = .string("") }   // a bool or object under a scalar key: degrade, don't crash
        }
    }

    private let queue = DispatchQueue(label: "com.kirtan.companion.store")
    private let fileURL: URL
    private var values: [String: Value] = [:]
    private var loaded = false
    private let log: StorageLog

    init(log: StorageLog = OSStorageLog()) {
        self.log = log
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.fileURL = documents.appendingPathComponent("kirtan.store.json")
    }

    // ── Context ────────────────────────────────────────────────────────────
    // The surface a perform body sees. It touches the store's dictionary DIRECTLY (no
    // re-queue), which is only safe because perform runs the body on the store's own
    // queue. Nested so it can reach the store's private members.
    final class Context {
        private unowned let store: KirtanStore
        init(store: KirtanStore) { self.store = store }

        func string(_ key: String) -> String? {
            if case .string(let s)? = store.values[key] { return s }
            return nil
        }
        func setString(_ key: String, _ value: String?) {
            if let value { store.values[key] = .string(value) }
            else { store.values[key] = nil }
        }
        func int(_ key: String) -> Int? {
            if case .int(let i)? = store.values[key] { return i }
            return nil
        }
        func setInt(_ key: String, _ value: Int?) {
            if let value { store.values[key] = .int(value) }
            else { store.values[key] = nil }
        }
    }

    private func ensureLoaded(_ context: Context) {
        guard !loaded else { return }
        loaded = true
        guard let data = try? Data(contentsOf: fileURL) else { return }   // first launch
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            // The whole file is unparseable: there is nothing to refuse on behalf of —
            // no key can be read, so none can be corrupted by a later write. Start empty
            // and say so, exactly as DataStore replaces a protobuf-corrupt file.
            log.warn("the store file could not be parsed; starting empty", nil)
            return
        }
        for (key, json) in object { values[key] = Value(json: json) }
    }

    private func flush(_ context: Context) throws {
        var object: [String: Any] = [:]
        for (key, value) in values { object[key] = value.json }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted]) else {
            log.warn("the store could not be serialised; this write was not persisted", nil)
            throw StoreFlushError()
        }
        do {
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // Surface as a log; the caller's read-modify-write is what classifies a
            // persist failure into a StorageError the user can see.
            log.warn("the store file could not be written", error)
            throw StoreFlushError()
        }
    }

    // ── Async read-modify-write: the atomic unit ──────────────────────────
    // Runs `body` on the store's queue, then persists. Everything in `body` is idempotent
    // under a re-run, so a failed write can be retried safely. Throws StorageError and
    // everything else straight through.
    func perform<T>(_ body: @escaping (Context) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<T, Error>) in
            queue.async {
                let context = Context(store: self)
                self.ensureLoaded(context)
                do {
                    let result = try body(context)
                    try self.flush(context)
                    cont.resume(returning: result)
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    // ── Async read-only: for callers that must not persist (a read is a read) ──
    // Runs `body` on the store's queue and does NOT flush. For load-only callers like
    // EqPrefs, which would otherwise rewrite a file that never changed.
    func read<T>(_ body: @escaping (Context) -> T) async -> T {
        await withCheckedContinuation { (cont: CheckedContinuation<T, Never>) in
            queue.async {
                let context = Context(store: self)
                self.ensureLoaded(context)
                cont.resume(returning: body(context))
            }
        }
    }

    // ── Convenience scalar accessors (blocking; for callers off the hot path) ──
    func string(_ key: String) -> String? {
        queue.sync {
            let context = Context(store: self)
            ensureLoaded(context)
            return context.string(key)
        }
    }
    func setString(_ key: String, _ value: String?) {
        queue.sync {
            let context = Context(store: self)
            ensureLoaded(context)
            context.setString(key, value)
            try? flush(context)
        }
    }
    func int(_ key: String) -> Int? {
        queue.sync {
            let context = Context(store: self)
            ensureLoaded(context)
            return context.int(key)
        }
    }
    func setInt(_ key: String, _ value: Int?) {
        queue.sync {
            let context = Context(store: self)
            ensureLoaded(context)
            context.setInt(key, value)
            try? flush(context)
        }
    }
}

/// Thrown when the in-memory change is made but the file could not be persisted — a
/// genuine disk failure the provider classifies into a StorageError.
struct StoreFlushError: Error {}
