package com.kirtan.companion.storage

/**
 * The one error type crossing the storage boundary.
 *
 * Ported from the `StorageError` class in `src/storage/BeatsProvider.js`, which
 * declares it beside the contract it belongs to. Here it gets its own file
 * because Kotlin has no "the file that is only a typedef", and because both the
 * providers and the layer above them need it without importing the contract.
 *
 * `code` is what callers branch on; `message` is written to be shown to a person
 * AS-IS — no string formatting at the call site, no "error code 5". That is why
 * the messages are full sentences with the failure already named.
 *
 * NETWORK and CONFLICT ARE UNUSED BY THE ON-DEVICE PROVIDER and exist so that a
 * remote one adds no new codes. The day Supabase landed on the web app, its UI
 * already handled everything the cloud provider could throw; the same holds
 * here, so `SupabaseBeatsProvider` maps a dropped connection and a unique-key
 * rejection onto codes the local path already defined and the UI already
 * renders. Adding a code means every `when` over [StorageErrorCode] in the app
 * has to be revisited — do not add one without that.
 */
enum class StorageErrorCode(val wireId: String) {
    /** The store refused or could not be reached at all (blocked, unreadable). */
    UNAVAILABLE("unavailable"),

    /** The store is full. Deleting something is the only way forward. */
    QUOTA("quota"),

    /** The row being patched or deleted is not there any more. */
    NOT_FOUND("notFound"),

    /** A uniqueness rule rejected the write. */
    CONFLICT("conflict"),

    /** The server could not be reached. */
    NETWORK("network"),

    /** Anything else. The message still has to be human-readable. */
    UNKNOWN("unknown"),
}

/**
 * A storage failure, carrying the code callers branch on and a sentence safe to
 * show verbatim.
 *
 * `cause` is the original throwable, kept for the log and deliberately not part
 * of the message: a Postgres or `IOException` string is not something to put in
 * front of a person mid-kirtan.
 */
class StorageError(
    val code: StorageErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String = "StorageError(${code.wireId}): $message"
}

/**
 * Human-readable text for a failure, whatever it turns out to be.
 *
 * The fallback matters more than the happy path: something threw that was not a
 * [StorageError] — a bug, a cancelled coroutine, a `NoSuchElementException` from
 * a row that did not decode. The caller still has to say SOMETHING, and saying
 * the exception's own text would leak a class name into the UI.
 *
 * A cancelled coroutine is not a failure the user should be told about, so it is
 * reported as null-ish by convention at the call sites that can cancel; see
 * [LibraryRepository], which never surfaces one.
 */
fun storageErrorMessage(error: Throwable?): String = when (error) {
    is StorageError -> error.message ?: FALLBACK_MESSAGE
    else -> FALLBACK_MESSAGE
}

/** The sentence used when nothing better is available. */
internal const val FALLBACK_MESSAGE =
    "Something went wrong saving that. Your beat may not have been kept."

/**
 * The logging seam.
 *
 * `android.util.Log` is not available in a plain JVM unit test — the build sets
 * `unitTests.isReturnDefaultValues`, so a call returns 0 rather than throwing,
 * but the line is then invisible and unassertable. Everything in this package
 * that needs to say something without failing takes one of these instead, so a
 * test can pass a recorder and the production wiring passes [AndroidStorageLog].
 */
fun interface StorageLog {
    fun warn(message: String, error: Throwable?)
}

private const val LOG_TAG = "kirtan.storage"

/** The production logger. Constructed by the composition root only. */
object AndroidStorageLog : StorageLog {
    override fun warn(message: String, error: Throwable?) {
        if (error == null) android.util.Log.w(LOG_TAG, message)
        else android.util.Log.w(LOG_TAG, message, error)
    }
}

/** Discards everything. The default where a log line would only be noise. */
object SilentStorageLog : StorageLog {
    override fun warn(message: String, error: Throwable?) = Unit
}
