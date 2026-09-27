import Foundation

// A small ordered JSON value: the shared currency between the share codec, the stored
// library codec and the cloud client.
//
// WHY ORDERED. The share format must stay byte-compatible with the web app — the same
// beat must encode to the same code on every platform, so links are diffable. That
// requires INSERTION-ordered objects (Swift's [String: T] dictionaries have no order).
// Decoding does not care about order, but the encoder does, so the object is a list of
// (key, value) pairs and the writer emits them in that order.
//
// Parsing reuses Foundation's JSONSerialization (battle-tested, lenient enough) and
// converts to this shape. Numbers all become Double, matching the JS double world: a
// browser-saved 90 and a hypothetical 90.0 are the same beat.

struct JPair: Equatable {
    let key: String
    let value: JValue
}

indirect enum JValue: Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JValue])
    case object([JPair])
}

extension JValue {

    // ── Field readers (the "lenient, never throw" family) ─────────────────

    /// The text of a string field; nil for an absent key, a literal null, or a wrong type.
    func stringField(_ key: String) -> String? {
        switch self {
        case .object(let pairs):
            for p in pairs where p.key == key {
                if case .string(let s) = p.value { return s }
                return nil
            }
            return nil
        default: return nil
        }
    }

    /// A field read through a Double and rounded — every JSON number is a JS double.
    /// Nil when absent, non-numeric, or non-finite (a 1e999 must not become a loop of
    /// that many cells).
    func intField(_ key: String) -> Int? {
        guard let d = doubleField(key), d.isFinite else { return nil }
        return Int(d.rounded())
    }

    /// A fractional field, for meters that need one (7/8 at eighth subdivision is 3.5).
    func doubleField(_ key: String) -> Double? {
        switch self {
        case .object(let pairs):
            for p in pairs where p.key == key {
                if case .number(let d) = p.value { return d }
                return nil
            }
            return nil
        default: return nil
        }
    }

    /// The raw element for a key, or nil when the object has no such key.
    func element(_ key: String) -> JValue? {
        guard case .object(let pairs) = self else { return nil }
        for p in pairs where p.key == key { return p.value }
        return nil
    }

    func isNull() -> Bool { if case .null = self { return true }; return false }

    func asArray() -> [JValue]? { if case .array(let a) = self { return a }; return nil }

    func asString() -> String? { if case .string(let s) = self { return s }; return nil }

    func asNumber() -> Double? { if case .number(let d) = self { return d }; return nil }

    func asBool() -> Bool? { if case .bool(let b) = self { return b }; return nil }

    // ── Building ──────────────────────────────────────────────────────────

    static func obj(_ pairs: (String, JValue)...) -> JValue { .object(pairs.map { JPair(key: $0.0, value: $0.1) }) }
    static func str(_ s: String) -> JValue { .string(s) }
    static func num(_ d: Double) -> JValue { .number(d) }
    static func int(_ i: Int) -> JValue { .number(Double(i)) }
    static func arr(_ v: [JValue]) -> JValue { .array(v) }

    // ── Writing ───────────────────────────────────────────────────────────

    /// Emit the exact JSON text, in pair order. String escaping matches JSON.stringify.
    func write() -> String {
        var out = ""
        write(to: &out)
        return out
    }

    private func write(to out: inout String) {
        switch self {
        case .null:        out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let d):
            if d.isFinite, d == d.rounded(), abs(d) < 1e15 {
                out += String(Int(d))          // 90, not 90.0 — matches the web writer
            } else {
                out += String(d)
            }
        case .string(let s): out += JsonEl.encodeString(s)
        case .array(let a):
            out += "["
            for (i, v) in a.enumerated() { if i > 0 { out += "," }; v.write(to: &out) }
            out += "]"
        case .object(let pairs):
            out += "{"
            for (i, p) in pairs.enumerated() {
                if i > 0 { out += "," }
                out += JsonEl.encodeString(p.key)
                out += ":"
                p.value.write(to: &out)
            }
            out += "}"
        }
    }
}

enum JsonEl {

    /// Escape a string exactly as JSON.stringify does.
    static func encodeString(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"":  out += "\\\""
            case "\\":  out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n":   out += "\\n"
            case "\r":   out += "\\r"
            case "\t":   out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }

    /// Parse JSON text into a JValue. Returns nil when the text is not JSON at all.
    /// `.fragmentsAllowed` lets a bare value parse; the callers then fail their own
    /// shape checks rather than crashing here.
    static func parse(_ text: String) -> JValue? {
        guard let data = text.data(using: .utf8),
              let top = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return nil }
        return convert(top)
    }

    private static func convert(_ any: Any) -> JValue {
        if any is NSNull { return .null }
        // CFBoolean check first: JSON booleans and 0/1 integers both bridge to Bool,
        // and conflating them would turn a "1" into true.
        if CFGetTypeID(any as CFTypeRef) == CFBooleanGetTypeID() {
            return .bool(any as? Bool ?? false)
        }
        if let n = any as? NSNumber { return .number(n.doubleValue) }
        if let s = any as? String { return .string(s) }
        if let a = any as? [Any] { return .array(a.map(convert)) }
        if let o = any as? [String: Any] {
            // JSONSerialization object keys arrive unordered; that is fine for a value
            // being decoded. (The ordered writer is only exercised by our own encoder.)
            return .object(o.map { JPair(key: $0.key, value: convert($0.value)) })
        }
        return .null
    }
}
