import Foundation

// The one length limit for anything a person names: beats and lists.
//
// 255 is generous for a name and matches the web and Android apps, so a beat named on
// one device never arrives somewhere it can't be saved. The fields stop at the limit;
// clampName is also the backstop for names that arrive any other way (imports, the
// " (2)" de-duplication suffix).

enum Names {
    static let maxLength = 255

    /// Cut `name` to at most `max` UTF-16 units (what the web's `maxLength` counts),
    /// dropping whole characters so an emoji is never split in half.
    static func clamp(_ name: String, max: Int = maxLength) -> String {
        guard name.utf16.count > max else { return name }
        var out = name
        while out.utf16.count > max { out.removeLast() }
        return out
    }
}
