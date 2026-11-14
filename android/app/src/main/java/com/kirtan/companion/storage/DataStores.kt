package com.kirtan.companion.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * The on-device plumbing every local path shares: one DataStore, its keys, and
 * the schema stamp.
 *
 * ── ONE FILE, TWO POSTURES ──
 * `src/storage/eqPrefs.js` is a separate FILE from the beat library and its
 * header is at pains to explain why: the library is shaped like the database it
 * becomes (rows, read-modify-write lists, errors that surface) while mixer
 * settings are a small blob of cosmetic preference whose failures must degrade
 * to flat rather than brick the app. That asymmetry is real and is preserved
 * here — but it lives in the CODE, not in the file layout. On Android a second
 * DataStore means a second disk writer and a second chance for the two to
 * disagree about what "now" is, for no benefit: [LocalBeatsProvider] throws and
 * [EqPrefs] never does, and one `.preferences_pb` file holds both without either
 * being able to corrupt the other's keys.
 *
 * ── ONE INSTANCE, OR IT CRASHES ──
 * DataStore refuses two instances over one file ("There are multiple DataStores
 * active for the same file"), which is a process-wide `IllegalStateException` at
 * the first read. The property delegate below is therefore a `val`, not a
 * function: exactly one instance per process, created the first time anything
 * asks. [com.kirtan.companion.AppContainer] should read it once and hand the same
 * `DataStore` to both consumers rather than touching the delegate twice from two
 * places.
 *
 * ── KEY NAMES ──
 * The library keys carry the web app's `kirtan.v2.` namespace. Not because the
 * two stores ever meet — a browser's localStorage and an app's DataStore are
 * different disks — but because the version marker is part of the SCHEMA, and a
 * schema that means the same thing on both platforms is one less thing to
 * translate when someone reads a cloud row written by the other.
 */
private const val STORE_NAME = "kirtan.store"

/**
 * The one DataStore for everything this package persists: the beat library, the
 * mixer preferences, and the signed-in session.
 */
val Context.kirtanStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

/**
 * Every key this package writes, in one place.
 *
 * The library keys are the web app's `KEYS` object from `src/storage/migrate.js`;
 * the rest have no web counterpart because the web app keeps them elsewhere (mixer
 * settings in `kirtan-eq-prefs`, the session in the Supabase client's own storage).
 */
internal object Keys {

    /** The user's beats, as one JSON array — see [LibraryJson]. */
    val BEATS = stringPreferencesKey("kirtan.v2.beats")

    /** The user's categories, as one JSON array. */
    val CATEGORIES = stringPreferencesKey("kirtan.v2.categories")

    /**
     * The id of the category Home cycles within. May hold [BUILTIN_CATEGORY] or
     * [CUSTOM_CATEGORY], which are not rows anywhere.
     *
     * A bare string, not a JSON-encoded one: the web app stores `"builtin"` as
     * `"\"builtin\""` purely because localStorage has no types, and
     * `migrate.js` calls that out as the odd one out. DataStore has types.
     */
    val ACTIVE_CATEGORY = stringPreferencesKey("kirtan.v2.activeCategory")

    /** The layout version of the three keys above. See [SCHEMA_VERSION]. */
    val SCHEMA_VERSION = intPreferencesKey("kirtan.schemaVersion")

    /** The whole mixer/notation snapshot as one JSON blob — see [EqPrefs]. */
    val MIXER_PREFS = stringPreferencesKey("kirtan.mixer.prefs")

    /** The persisted Supabase session, as one JSON blob — see [AuthSession]. */
    val AUTH_SESSION = stringPreferencesKey("kirtan.auth.session")

    /**
     * The PKCE code verifier for an in-flight browser sign-in.
     *
     * Persisted, not held in memory: the round trip leaves the app, and Android
     * is free to kill the process while the browser is in front. A verifier that
     * died with the process is a sign-in that silently fails on the way back.
     */
    val AUTH_PENDING_VERIFIER = stringPreferencesKey("kirtan.auth.pendingVerifier")

    /** The CSRF `state` paired with [AUTH_PENDING_VERIFIER]. */
    val AUTH_PENDING_STATE = stringPreferencesKey("kirtan.auth.pendingState")

    /**
     * The server's built-in beat rows, cached VERBATIM as one JSON array.
     *
     * Rows rather than beats: a cache written by an older build then goes back
     * through today's validation and today's derivation rules on the way in, so
     * neither can be smuggled past by a blob on disk, and changing how a row
     * becomes a beat needs no cache-version bump. See [ShippedBeatsClient].
     */
    val SHIPPED_ROWS = stringPreferencesKey("kirtan.shipped.rows")

    /**
     * The newest `updated_at` among [SHIPPED_ROWS], so a check that fetched the
     * same set again can say so instead of rewriting the blob.
     */
    val SHIPPED_REVISION = stringPreferencesKey("kirtan.shipped.revision")
}

/**
 * The schema version this build writes.
 *
 * It is the web app's version 2 — the namespaced, all-JSON, all-UUID layout that
 * `src/storage/migrate.js` migrates INTO. There is no v1→v2 step here because
 * nothing on this device predates the port: the first install writes v2 directly.
 * The stamp still earns its place, because the next shape change is a one-line
 * `if (version < N) migrate()` instead of an archaeology problem — and because
 * migrating is only safe if the store says what it is.
 */
internal const val SCHEMA_VERSION = 2
