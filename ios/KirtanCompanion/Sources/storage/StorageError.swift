import Foundation

// The one error type crossing the storage boundary.
//
// Ported from storage/StorageError.kt, which in turn ports the StorageError class in
// src/storage/BeatsProvider.js. `code` is what callers branch on; `message` is written
// to be shown to a person AS-IS — no string formatting at the call site, no "error code 5".
// That is why the messages are full sentences with the failure already named.
//
// NETWORK and CONFLICT are UNUSED BY THE ON-DEVICE PROVIDER and exist so that a remote
// one adds no new codes. The day Supabase landed on the web app its UI already handled
// everything the cloud provider could throw; the same holds here, so SupabaseBeatsProvider
// maps a dropped connection and a unique-key rejection onto codes the local path already
// defined and the UI already renders. Adding a code means every switch over
// StorageErrorCode in the app has to be revisited — do not add one without that.

enum StorageErrorCode: String {
    /// The store refused or could not be reached at all (blocked, unreadable).
    case unavailable
    /// The store is full. Deleting something is the only way forward.
    case quota
    /// The row being patched or deleted is not there any more.
    case notFound
    /// A uniqueness rule rejected the write.
    case conflict
    /// The server could not be reached.
    case network
    /// Anything else. The message still has to be human-readable.
    case unknown
}

/// A storage failure, carrying the code callers branch on and a sentence safe to show
/// verbatim. `cause` is the original error, kept for the log and deliberately not part of
/// the message: a DiskError or a network string is not something to put in front of a
/// person mid-kirtan.
struct StorageError: Error, LocalizedError {
    let code: StorageErrorCode
    let message: String
    let cause: Error?

    var errorDescription: String? { message }

    init(_ code: StorageErrorCode, _ message: String, _ cause: Error? = nil) {
        self.code = code
        self.message = message
        self.cause = cause
    }
}

/// Human-readable text for a failure, whatever it turns out to be. The fallback matters
/// more than the happy path: something threw that was not a StorageError — a bug, a
/// cancelled task, a range error from a row that did not decode. The caller still has to
/// say SOMETHING, and saying the error's own description would leak an internal string
/// into the UI.
func storageErrorMessage(_ error: Error?) -> String {
    if let storage = error as? StorageError, !storage.message.isEmpty {
        return storage.message
    }
    return StorageError.fallbackMessage
}

extension StorageError {
    static let fallbackMessage =
        String(localized: "Something went wrong saving that. Your beat may not have been kept.")
}

// The logging seam. os.Logger is not assertable in a unit test, so everything in this
// package that needs to say something without failing takes one of these: a test passes a
// recorder, the production wiring passes OSStorageLog.
protocol StorageLog {
    func warn(_ message: String, _ error: Error?)
}

/// The production logger. Constructed by the composition root only.
struct OSStorageLog: StorageLog {
    func warn(_ message: String, _ error: Error?) {
        if let error {
            NSLog("[kirtan.storage] %@ — %@", message, "\(error)")
        } else {
            NSLog("[kirtan.storage] %@", message)
        }
    }
}

/// Discards everything. The default where a log line would only be noise.
struct SilentStorageLog: StorageLog {
    func warn(_ message: String, _ error: Error?) {}
}
