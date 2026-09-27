import Foundation

// Pure URL and error helpers for the Supabase transport.
//
// Everything below is free of the network and the store, because it is the part worth
// testing: a wrong filter encoding or a mis-mapped status code is a bug no integration test
// can reach, and both are trivially assertable here.
//
// URL building goes through URLComponents, which percent-encodes the query values. That is
// the point: a filter like `ilike.%text%` or `or=(name.ilike.%a%,author_name.ilike.%b%)` is
// passed in its DECODED form, URLComponents produces the wire form (`%25`, `%2C`), and
// PostgREST decodes it back before applying the operator. A playlist name containing `&`
// or `#` would otherwise truncate the query and silently select the wrong rows.

/// The text of a JSON string field, or nil.
func jstring(_ value: JValue?) -> String? {
    guard let value else { return nil }
    if case .string(let s) = value { return s }
    return nil
}

/// A PostgREST URL: `…/rest/v1/<table>?<filters>&order=…&limit=…`.
func restUrl(config: SupabaseConfig, table: String, filters: [(String, String)], order: String? = nil, limit: Int? = nil) -> String {
    var components = URLComponents(string: "\(config.baseUrl)/rest/v1/\(table)")
    var items = filters.map { URLQueryItem(name: $0.0, value: $0.1) }
    if let order { items.append(URLQueryItem(name: "order", value: order)) }
    if let limit { items.append(URLQueryItem(name: "limit", value: "\(limit)")) }
    components?.queryItems = items
    return components?.url?.absoluteString ?? ""
}

func authUrl(config: SupabaseConfig, path: String, query: [(String, String)]) -> String {
    var components = URLComponents(string: "\(config.baseUrl)/auth/v1/\(path)")
    components?.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) }
    return components?.url?.absoluteString ?? ""
}

/// A Postgres/PostgREST failure, mapped onto the code the app branches on: a unique-key
/// violation is a CONFLICT, PostgREST's "no rows for `.single()`" is NOT_FOUND, and
/// anything the server refused for permission reasons is UNAVAILABLE with a sentence that
/// tells the user to sign in.
func restFailure(status: Int, body: JValue?, whileDoing: String) -> StorageError {
    let code = body?.stringField("code")
    let detail = body?.stringField("message") ?? ""
    if code == "23505" {
        return StorageError(.conflict, "That already exists.")
    }
    if code == "PGRST116" {
        return StorageError(.notFound, "\(whileDoing) no longer exists.")
    }
    if code == "42501" || status == 401 || status == 403 {
        return StorageError(.unavailable, "You're signed out, so that couldn't be saved. Sign in and try again.")
    }
    return StorageError(.unknown, "Something went wrong with \(whileDoing). \(detail)".trimmingCharacters(in: .whitespaces))
}

/// A GoTrue failure. Bad credentials are the common case and get their own sentence;
/// everything else is reported as a plain failure.
func authFailure(status: Int, body: JValue?, whileDoing: String) -> StorageError {
    let description = body?.stringField("error_description")
        ?? body?.stringField("msg")
        ?? body?.stringField("message")
        ?? ""
    if status == 401 || status == 403 {
        return StorageError(.unavailable, "You're signed out, so that couldn't be saved. Sign in and try again.")
    }
    if status == 400 && description.lowercased().contains("invalid") {
        return StorageError(.unknown, "That email and password didn't match. Try again.")
    }
    let message = description.isEmpty
        ? "Couldn't \(whileDoing). The server refused the request."
        : "Couldn't \(whileDoing). \(description)"
    return StorageError(.unknown, message.trimmingCharacters(in: .whitespaces))
}

/// The clock-skew race, recognised from either a GoTrue or a PostgREST body.
func isClockSkew(status: Int, body: JValue?) -> Bool {
    guard status == 401 || status == 403 else { return false }
    let text = (body?.stringField("message")
        ?? body?.stringField("error_description")
        ?? body?.stringField("msg") ?? "").lowercased()
    return text.contains("issued at future") || (text.contains("jwt") && text.contains("future"))
}
