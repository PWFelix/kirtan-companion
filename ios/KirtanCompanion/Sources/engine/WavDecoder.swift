import Foundation

// A minimal RIFF/WAVE decoder, written by hand rather than using AVAudioFile for
// three reasons:
//   1. THE MIXER NEEDS RAW PCM. Every system API wants to own playback; none of
//      them sum through a per-end biquad chain at a caller-chosen gain — exactly
//      what the routing in SoundPlayer.js specifies.
//   2. THE BUNDLED FILES ARE NOT ONE FORMAT (16-bit mridanga, 24-bit karatalas,
//      both stereo 44.1 kHz); a decoder assuming one bit depth would fail the
//      cymbals.
//   3. IT STAYS PURE — no audio imports, so it runs in a unit test with a byte
//      array, the only way to check the 24-bit and stereo paths off a device.
//
// Only the `fmt ` and `data` chunks are read; everything else is skipped by
// length, and chunk order is not assumed because encoders vary. The roadmap's
// "record or upload custom samples" lands here, so every common PCM width is
// handled, not just the two the bundled set uses.

enum WavFormatError: Error, CustomStringConvertible {
    case message(String)
    var description: String {
        switch self { case .message(let s): return s }
    }
}

enum WavDecoder {

    static let formatPCM = 1
    static let formatIEEEFloat = 3
    static let formatExtensible = 0xFFFE

    /// Decode a WAV file to mono float PCM at `targetRate`.
    /// Throws `WavFormatError` if the bytes are not a RIFF/WAVE this decoder
    /// understands. Callers decide whether that is fatal — the sample bank treats
    /// it as "this stroke is silent".
    static func decode(_ bytes: [UInt8], targetRate: Int) throws -> PcmSample {
        guard bytes.count >= 12 else { throw WavFormatError.message("file too short to be a RIFF file") }
        guard tag(bytes, 0) == "RIFF" else { throw WavFormatError.message("missing RIFF header") }
        guard tag(bytes, 8) == "WAVE" else { throw WavFormatError.message("not a WAVE file") }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var formatTag = 0
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while pos + 8 <= bytes.count {
            let id = tag(bytes, pos)
            let size = Int(leInt32(bytes, pos + 4))
            if size < 0 { break }
            let payload = pos + 8

            if id == "fmt " {
                guard payload + 16 <= bytes.count else { throw WavFormatError.message("truncated fmt chunk") }
                formatTag = Int(leInt16(bytes, payload))
                channels = Int(leInt16(bytes, payload + 2))
                sampleRate = Int(leInt32(bytes, payload + 4))
                bitsPerSample = Int(leInt16(bytes, payload + 14))
                // WAVE_FORMAT_EXTENSIBLE carries the real tag in the SubFormat GUID.
                if formatTag == formatExtensible, payload + 26 <= bytes.count {
                    formatTag = Int(leInt16(bytes, payload + 24))
                }
            } else if id == "data" {
                dataOffset = payload
                // Trust the smaller of the declared size and the bytes actually
                // present: truncated recordings decode what they have.
                dataLength = min(size, bytes.count - payload)
            }

            pos = payload + size + (size & 1)
        }

        guard dataOffset >= 0 else { throw WavFormatError.message("no data chunk") }
        guard channels > 0 else { throw WavFormatError.message("channel count is \(channels)") }
        guard sampleRate > 0 else { throw WavFormatError.message("sample rate is \(sampleRate)") }
        guard dataLength > 0 else { return PcmSample(data: [], sampleRate: targetRate) }

        let bytesPerSample = bitsPerSample / 8
        guard bytesPerSample > 0 else { throw WavFormatError.message("bit depth is \(bitsPerSample)") }

        let frameBytes = bytesPerSample * channels
        let frameCount = dataLength / frameBytes

        var mono = [Float](repeating: 0, count: frameCount)

        if formatTag == formatIEEEFloat, bitsPerSample == 32 {
            for f in 0..<frameCount {
                var sum: Float = 0
                for c in 0..<channels { sum += leFloat(bytes, dataOffset + f * frameBytes + c * 4) }
                mono[f] = sum / Float(channels)
            }
        } else if formatTag == formatPCM {
            switch bitsPerSample {
            case 8: // 8-bit WAV is UNSIGNED, centred on 128 — the one width that isn't two's complement.
                for f in 0..<frameCount {
                    var sum = 0
                    for c in 0..<channels { sum += Int(bytes[dataOffset + f * frameBytes + c]) - 128 }
                    mono[f] = Float(sum) / Float(channels) / 128
                }
            case 16:
                for f in 0..<frameCount {
                    var sum = 0
                    for c in 0..<channels { sum += Int(leInt16(bytes, dataOffset + f * frameBytes + c * 2)) }
                    mono[f] = Float(sum) / Float(channels) / 32768
                }
            case 24:
                for f in 0..<frameCount {
                    var sum = 0
                    for c in 0..<channels { sum += read24(bytes, dataOffset + f * frameBytes + c * 3) }
                    mono[f] = Float(sum) / Float(channels) / 8388608
                }
            case 32:
                for f in 0..<frameCount {
                    var sum: Int64 = 0
                    for c in 0..<channels { sum += Int64(leInt32(bytes, dataOffset + f * frameBytes + c * 4)) }
                    mono[f] = Float(Double(sum) / Double(channels) / 2147483648.0)
                }
            default:
                throw WavFormatError.message("unsupported PCM bit depth \(bitsPerSample)")
            }
        } else {
            throw WavFormatError.message("unsupported format tag \(formatTag)")
        }

        if sampleRate == targetRate {
            return PcmSample(data: mono, sampleRate: targetRate)
        }
        return PcmSample(data: resampleLinear(mono, srcRate: sampleRate, dstRate: targetRate), sampleRate: targetRate)
    }

    /// Three little-endian bytes as a signed 24-bit sample. Bit 23 is the sign bit;
    /// if it is set the value is negative, so subtract 2^24 to recover the
    /// two's-complement value. (The `(raw << 8) >> 8` trick that sign-extends for a
    /// 32-bit int does NOT work on Swift's 64-bit Int — it leaves negative samples
    /// as large positives, which is how the 24-bit karatalas decoded at double
    /// amplitude and drove the mix into a constant clipped buzz.)
    private static func read24(_ b: [UInt8], _ at: Int) -> Int {
        let raw = Int(b[at]) | (Int(b[at + 1]) << 8) | (Int(b[at + 2]) << 16)
        return raw & 0x80_0000 != 0 ? raw - 0x100_0000 : raw
    }

    private static func tag(_ b: [UInt8], _ at: Int) -> String {
        String(bytes: b[at..<at + 4].map { UInt8($0) }, encoding: .ascii) ?? "????"
    }

    // ── Little-endian readers ────────────────────────────────────────────────
    private static func leInt16(_ b: [UInt8], _ at: Int) -> Int16 {
        Int16(b[at]) | (Int16(b[at + 1]) << 8)
    }
    private static func leInt32(_ b: [UInt8], _ at: Int) -> Int32 {
        Int32(b[at]) | (Int32(b[at + 1]) << 8) | (Int32(b[at + 2]) << 16) | (Int32(b[at + 3]) << 24)
    }
    private static func leFloat(_ b: [UInt8], _ at: Int) -> Float {
        let bits = UInt32(b[at]) | (UInt32(b[at + 1]) << 8) | (UInt32(b[at + 2]) << 16) | (UInt32(b[at + 3]) << 24)
        return Float(bitPattern: bits)
    }

    /// Linear-interpolating resampler. The bundled set is 44.1 kHz and devices are
    /// 48 kHz, so this upsamples by ~1.088 — no aliasing to prevent, only mild
    /// imaging above the drums' content. A windowed-sinc resampler would be more
    /// correct and slower at load; replace this only if a sample set ships far
    /// from the device rate, or downsampling appears.
    static func resampleLinear(_ input: [Float], srcRate: Int, dstRate: Int) -> [Float] {
        if input.isEmpty || srcRate == dstRate { return input }
        let ratio = Double(srcRate) / Double(dstRate)
        let outLength = max(1, Int(Double(input.count) / ratio))
        var out = [Float](repeating: 0, count: outLength)
        let last = input.count - 1

        for i in 0..<outLength {
            let pos = Double(i) * ratio
            let i0 = Int(pos)
            if i0 >= last {
                out[i] = input[last]
                continue
            }
            let frac = Float(pos - Double(i0))
            let s0 = input[i0]
            out[i] = s0 + (input[i0 + 1] - s0) * frac
        }
        return out
    }
}
