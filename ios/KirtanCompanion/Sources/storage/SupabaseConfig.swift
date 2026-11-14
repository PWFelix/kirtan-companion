import Foundation

// Where the cloud is, and whether there is one.
//
// Both values are compiled in at build time (the Info.plist keys the build adds from the
// same local.properties the Android port reads). Both default to empty, and EMPTY MEANS
// NOT OFFERED rather than misconfigured — the same contract the web app has with no
// VITE_SUPABASE_* set. The anon key is public by design; Row-Level Security in the
// database is what stops one user reading another's beats. Never put a service_role key
// in a client build.
struct SupabaseConfig {
    let url: String
    let anonKey: String

    /// True only when both values are present — the gate for every cloud feature.
    var isConfigured: Bool {
        !url.trimmingCharacters(in: .whitespaces).isEmpty
            && !anonKey.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// The project URL with any trailing slash removed, so paths can be appended directly.
    var baseUrl: String {
        var s = url.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// The keys the build was compiled with, from the app's Info.plist.
    static func fromBundle() -> SupabaseConfig {
        let dict = Bundle.main.infoDictionary
        return SupabaseConfig(
            url: dict?["KIRTAN_SUPABASE_URL"] as? String ?? "",
            anonKey: dict?["KIRTAN_SUPABASE_ANON_KEY"] as? String ?? "",
        )
    }
}
