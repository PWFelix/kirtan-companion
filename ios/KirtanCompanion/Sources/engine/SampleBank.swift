import Foundation
#if canImport(UIKit)
import UIKit
#endif

// Which recording to sound: a lane, and whether the head is open or closed.
//
// The web app keys samples by the STRING "dayan_open" and routes them by name
// prefix — a convention the SoundPlayer header warns you must honour. Here the
// lane is a typed LaneId, so a mis-spelled or unroutable stroke is a compile
// error rather than a silent fall-through to master. `wireName` is kept for log
// messages and asset paths, where the string form is genuinely the key.
struct StrokeKey: Hashable {
    let lane: LaneId
    let open: Bool
    var wireName: String { "\(lane.wireId)_\(open ? "open" : "closed")" }

    static let all: [StrokeKey] = LaneId.ordered.flatMap { lane in
        [StrokeKey(lane: lane, open: true), StrokeKey(lane: lane, open: false)]
    }
}

enum SampleError: Error, CustomStringConvertible {
    case missingFile(String)
    var description: String {
        switch self {
        case .missingFile(let name): return "sound file not in bundle: \(name)"
        }
    }
}

// Reads a raw sample file's bytes. The production loader reads the app bundle;
// tests inject a synthetic one so the whole DSP path runs off-device.
protocol SampleLoader {
    /// Return the bytes of `filename` (a lane-prefixed basename, e.g.
    /// "dayan_open_1.wav"), or throw if it is not in the bundle. Absence is
    /// expected for unbundled variants.
    func read(_ filename: String) throws -> [UInt8]
}

/// Reads from the app bundle.
///
/// The synchronized Resources group may copy files preserving their subdirectory
/// or flatten them to the bundle root, so we try the declared Sounds/ folder
/// first and then the root. Filenames are lane-unique, so a name found anywhere
/// is unambiguous.
final class BundleSampleLoader: SampleLoader {
    static let assetRoot = "Sounds"
    private let bundle = Bundle.main
    private let fileManager = FileManager.default

    func read(_ filename: String) throws -> [UInt8] {
        for sub in [BundleSampleLoader.assetRoot, ""] {
            var url = bundle.bundleURL
            if !sub.isEmpty { url = url.appendingPathComponent(sub) }
            url = url.appendingPathComponent(filename)
            if fileManager.fileExists(atPath: url.path) {
                return [UInt8](try Data(contentsOf: url))
            }
        }
        throw SampleError.missingFile(filename)
    }
}

// Loads and decodes the bundled recordings. Owns asset I/O and decoding, nothing
// else: playback, the round-robin cursor and the per-lane routing all live in
// SoundPlayer, which receives a plain map through `decoded()`. That split is what
// lets the entire DSP path be exercised offline with synthesised samples.
//
// MISSING FILES ARE NOT FATAL, and this is load-bearing: the bundled karatalas
// ship two open variants while STROKE_FILES names three, and the web loader
// resolves a failed fetch to null and filters it out so one absent recording
// cannot leave the app stuck waiting for "ready". A file that cannot be opened is
// skipped with a warning; a stroke is registered only if at least one decoded.
final class SampleBank {

    /// Stroke → the recordings that voice it, in preference order. Filenames carry
    /// the lane prefix (they were flattened into the bundle root, so the name must
    /// be unique). Mirrors STROKE_SAMPLES in SoundPlayer.js, including the three
    /// kartal opens of which only two are currently bundled.
    static let STROKE_FILES: [StrokeKey: [String]] = {
        var map: [StrokeKey: [String]] = [:]
        for lane in LaneId.ordered {
            let id = lane.wireId
            map[StrokeKey(lane: lane, open: true)] = ["\(id)_open_1.wav", "\(id)_open_2.wav", "\(id)_open_3.wav"]
            map[StrokeKey(lane: lane, open: false)] = ["\(id)_closed_1.wav"]
        }
        return map
    }()

    private let loader: SampleLoader
    private var pools: [StrokeKey: [PcmSample]] = [:]

    /// The rate every sample was decoded to, so voices need no further conversion.
    private(set) var sampleRate = 0

    init(loader: SampleLoader) {
        self.loader = loader
    }

    var isLoaded: Bool { !pools.isEmpty }

    /// Decode every stroke's recordings at `targetRate`. Returns the strokes that
    /// produced at least one usable sample. Safe to call repeatedly; a later call
    /// replaces the pool.
    @discardableResult
    func load(targetRate: Int, keys: [StrokeKey] = StrokeKey.all) -> Set<StrokeKey> {
        sampleRate = targetRate
        pools.removeAll()
        var ok = Set<StrokeKey>()

        for key in keys {
            let files = SampleBank.STROKE_FILES[key] ?? []
            var decoded: [PcmSample] = []

            for file in files {
                let bytes: [UInt8]
                do {
                    bytes = try loader.read(file)
                } catch {
                    // Expected for variants that aren't bundled yet.
                    continue
                }
                do {
                    let pcm = try WavDecoder.decode(bytes, targetRate: targetRate)
                    if pcm.frameCount > 0 { decoded.append(pcm) }
                } catch {
                    // Undecodable: skip, don't fail the load.
                }
            }

            if !decoded.isEmpty {
                pools[key] = decoded
                ok.insert(key)
            }
        }
        return ok
    }

    /// Every decoded stroke, as the plain map SoundPlayer.attach wants. A snapshot.
    func decoded() -> [StrokeKey: [PcmSample]] { pools }

    /// Total decoded frames held, for a memory sanity check in tests.
    func frameCount() -> Int { pools.values.reduce(0) { $0 + $1.reduce(0) { $0 + $1.frameCount } } }
}

// A random index that is NOT `lastIndex`. With one sample there is nothing else
// to pick, so 0 comes back. This is _pickIndex from SoundPlayer.js reproduced
// literally: draw from count-1 possibilities and skip over the excluded index.
//
// ROUND-ROBIN IS THE POINT: a real drum never sounds identical twice in a row, so
// each stroke maps to several recordings and playing one picks a variant that is
// not the one just played. Single-sample strokes fall out naturally — with one
// recording the pool of "anything but the last" is empty, so it replays.
func pickIndex(count: Int, lastIndex: Int, random: inout SystemRandomNumberGenerator) -> Int {
    if count <= 1 { return 0 }
    var index = Int.random(in: 0..<(count - 1), using: &random)
    if index >= lastIndex { index += 1 }
    return index
}
