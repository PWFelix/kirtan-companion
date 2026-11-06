import Foundation

// THE CONTRACT between the app and wherever the user's saved work lives.
//
// Ported from storage/BeatsProvider.kt, which holds no storage code at all — it is the
// shape every provider must implement, the error type they all throw, and the id minter
// they all share. LocalBeatsProvider is the on-device implementation; SupabaseBeatsProvider
// is the cloud twin; LibraryRepository is the only consumer allowed to know which is which.
//
// THREE RULES, and the reason each exists:
//
//  1. EVERY METHOD IS ASYNC, even though the on-device store is local. This is the whole
//     point of the layer. A remote provider can be dropped in without touching a single
//     call site, because every caller already awaits. If any method here were synchronous,
//     swapping the store would ripple into every screen that reads a beat.
//
//  2. A PROVIDER OWNS ONLY WHAT THE USER MADE. The beats compiled into Beats.beats are
//     identical for every user and needed without waiting on I/O, so they are merged one
//     level up, in LibraryRepository. Putting them here would mean writing that merge
//     again inside every future provider, and would leave deleteBeat having to refuse rows
//     the provider never stored.
//
//  3. FAILURES THROW StorageError. They do not return false and they are not swallowed.
//     The web code this layer replaced wrapped every write in catch {}, so a full or
//     blocked store looked EXACTLY like a successful save: the beat appeared in the list
//     and was gone on the next reload. Every method here throws instead, and
//     LibraryRepository turns that into something the user can see and — for optimistic
//     writes — into a rollback.
//
// ── DRAFTS AND PATCHES ──
// A beat's draft is just a Beat with id == nil; the model already documents that absence
// as "an unsaved editor draft", and the provider mints the real id. A beat patch is a
// whole Beat, because the sole caller (saveBeat) always hands over the complete body —
// the cloud provider then replaces its jsonb blob wholesale rather than merging, which is
// what keeps an editor that DELETED a field from resurrecting it on the next save.
//
// Categories need two small types because Category.id is non-nullable: a draft cannot be
// expressed as "a Category with no id" the way a beat's can.
//
// ── VIRTUAL CATEGORIES ──
// BUILTIN_CATEGORY and CUSTOM_CATEGORY are pseudo-categories that were never stored as
// rows. They may appear as an activeCategoryId — and did, in the web app's v1 layout — so
// a provider must pass them through untouched rather than treating them as a missing row.

/// The "all shipped beats" pseudo-category. Never stored as a row.
let BUILTIN_CATEGORY = "builtin"

/// The "everything you have made" pseudo-category. Never stored as a row.
let CUSTOM_CATEGORY = "custom"

/// What a new category needs: a name, and the progression it starts holding.
struct CategoryDraft {
    var name: String
    var beatIds: [String] = []
}

/// A PARTIAL category write: a null field is left alone. Partial because the two things a
/// caller may want to change are independent — reordering a progression patches beatIds
/// and must not touch the name, and a rename must not touch the order.
struct CategoryPatch {
    var name: String? = nil
    var beatIds: [String]? = nil
}

protocol BeatsProvider: AnyObject {
    /// Everything the user has saved. Never includes a built-in beat.
    func loadAll() async throws -> Library

    /// Save one new beat and return it with its minted id. `draft.id` is ignored.
    func createBeat(_ draft: Beat) async throws -> Beat

    /// Save several new beats in ONE write and return them in the order given. Plural on
    /// purpose: importing a shared category writes several at once, and the
    /// loop-of-single-creates version of that has already been a bug in the web app once.
    func createBeats(_ drafts: [Beat]) async throws -> [Beat]

    /// Overwrite the beat with `id` using the complete body in `patch`, and return what
    /// was stored. Throws StorageError(.notFound) when there is no such beat.
    func updateBeat(id: String, patch: Beat) async throws -> Beat

    /// Remove a beat. Removing one that is already gone is not an error.
    func deleteBeat(id: String) async throws

    /// Save one new category and return it with its minted id.
    func createCategory(_ draft: CategoryDraft) async throws -> Category

    /// Apply the fields `patch` carries and return the stored category.
    func updateCategory(id: String, patch: CategoryPatch) async throws -> Category

    /// Remove a category. Removing one that is already gone is not an error.
    func deleteCategory(id: String) async throws

    /// Remember which category Home cycles within. Accepts a virtual id.
    func setActiveCategory(id: String) async throws
}

/// Mint an id. A v4 UUID, so the cloud `uuid` primary-key columns accept an id minted on
/// either platform. (The web version needs a fallback ladder because crypto.randomUUID
/// only exists in a secure context; UUID() has no such hole, so there is no second path.)
func newId() -> String {
    UUID().uuidString
}
