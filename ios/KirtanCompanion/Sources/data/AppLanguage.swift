import Foundation
import ObjectiveC

/// AppLanguage — the language picked in Settings, overriding the phone's.
///
/// `nil` means "follow the system" (the default). A tag ("en", "es") pins the app to
/// that language, live, without a relaunch:
///
/// Every `String(localized:)` passes `bundle: .strings` — the chosen `xx.lproj`, or
/// Bundle.main for "system". Belt and braces for SwiftUI's own Text keys, Bundle.main's
/// class is also swapped at startup for [OverrideBundle], whose classic lookup answers
/// from the same lproj.
/// NavigationShell keys its view tree on the choice (`@AppStorage`), so flipping it
/// re-renders every screen in the new language while keeping the current tab.
///
/// The usual alternative — writing `AppleLanguages` — only takes effect after a
/// relaunch, which is a poor answer to "tap Español". The system's own per-app
/// language (Settings › Kirtanero › Language) still works and is what "System"
/// follows.
enum AppLanguage {
    /// Languages with a full translation, in picker order. Keep in step with the catalog.
    static let supported = ["en", "es"]
    static let defaultsKey = "kirtanLanguage"

    /// The choice, or nil for "system".
    static var choice: String? {
        let v = UserDefaults.standard.string(forKey: defaultsKey) ?? ""
        return supported.contains(v) ? v : nil
    }

    /// Each language named in itself, so whoever needs the picker can read it.
    static func nativeName(_ tag: String) -> String {
        let l = Locale(identifier: tag)
        return (l.localizedString(forLanguageCode: tag) ?? tag).capitalized(with: l)
    }

    /// The language the system would pick for this app, ignoring our override.
    static var systemLanguage: String {
        Bundle.main.preferredLocalizations.first.flatMap { supported.contains($0) ? $0 : nil } ?? "en"
    }

    /// Call once at launch, before the first view.
    static func install() {
        if object_getClass(Bundle.main) != OverrideBundle.self {
            object_setClass(Bundle.main, OverrideBundle.self)
        }
        OverrideBundle.lproj = lprojBundle(choice)
    }

    /// Choose [tag] (nil = system). The bundle switches first, then the stored value,
    /// whose change is what re-renders the UI.
    static func set(_ tag: String?) {
        OverrideBundle.lproj = lprojBundle(tag)
        UserDefaults.standard.set(tag ?? "", forKey: defaultsKey)
    }

    private static func lprojBundle(_ tag: String?) -> Bundle? {
        guard let tag, let path = OverrideBundle.originalPath(forLproj: tag) else { return nil }
        return Bundle(path: path)
    }
}

extension Bundle {
    /// Where interface strings come from: the chosen language's lproj, else main.
    /// Every `String(localized:)` passes `bundle: .strings`, because Swift's
    /// String(localized:) resolves without going through any overridable Bundle
    /// method — redirecting Bundle.main alone leaves those calls in the system
    /// language. (The class swap below still covers SwiftUI's own Text keys.)
    static var strings: Bundle { OverrideBundle.lproj ?? .main }
}

/// Bundle.main's stand-in class; see AppLanguage.
private final class OverrideBundle: Bundle, @unchecked Sendable {
    nonisolated(unsafe) static var lproj: Bundle?

    static func originalPath(forLproj tag: String) -> String? {
        Bundle.main.path(forResource: tag, ofType: "lproj")
    }

    override func localizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        if let lproj = Self.lproj {
            return lproj.localizedString(forKey: key, value: value, table: tableName)
        }
        return super.localizedString(forKey: key, value: value, table: tableName)
    }
}
