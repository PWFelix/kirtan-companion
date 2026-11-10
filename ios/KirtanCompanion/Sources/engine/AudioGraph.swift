import AVFoundation

// Fills one block of audio. Called from the audio thread, never from the UI.
protocol BlockRenderer: AnyObject {
    /// Write `count` frames of mono float into `out`, beginning at the absolute
    /// stream position `fromFrame`. Implementations place triggers at their exact
    /// offset within the block, which is what makes timing sample-accurate.
    func render(fromFrame: Int64, count: Int, out: inout [Float])
}

// The audio output. Has no counterpart in the web app — there, Tone.start() hands
// the graph to Web Audio and the browser owns the thread, the buffer and the sound
// card. Here we own the graph (a single AVAudioSourceNode into the main mixer) and
// the OS owns the thread: it calls the render block as the hardware needs data,
// which is what paces the whole loop. No manual thread, no write-retry loop — the
// device is the clock, exactly as it is with AudioTrack on Android.
//
// WHY A BLOCK PULL RATHER THAN SCHEDULING. The render block is the only timing
// source, and it is the hardware's. Everything musical is derived from the frame
// position it implies, so the sequencer can never drift against the audio: there
// is exactly one clock, and it is the sample count.
//
// LATENCY. The render quantum plus the hardware output buffer is the delay between
// a trigger and hearing it. It is CONSTANT, so a running loop is unaffected — the
// beat is exactly in time with itself. It is audible only in the editor's pads and
// tap tempo, both tolerable at the buffer sizes iOS uses.
//
// The render block requests in chunks of at most `blockSize`, so the DSP scratch
// (sized to blockSize) is never asked to hold more than it has, whatever quantum
// the hardware happens to request.
final class AudioGraph {

    let sampleRate: Int
    let blockSize: Int

    private var renderer: BlockRenderer?
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode!
    private var scratch: [Float]

    private let framesLock = NSLock()
    private var framesRendered: Int64 = 0
    /// The latest callback's (frame, host time), guarded by framesLock.
    private var stamp: OutputClock.Stamp?
    /// How long after its callback a frame is heard: the session's output
    /// latency plus one IO buffer. Set from the main thread by the playback
    /// layer, which owns AVAudioSession.
    private var latencyNanos: UInt64 = 0

    init(sampleRate: Int, blockSize: Int) {
        self.sampleRate = sampleRate
        self.blockSize = blockSize
        self.scratch = [Float](repeating: 0, count: blockSize)

        // Mono float32: the DSP is mono; the mixer upmixes to the hardware's
        // channels. A fixed sampleRate means the WavDecoder already resampled to
        // it, so no conversion sits in the signal path (the engine only converts
        // to the hardware rate at the very output if the two differ).
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: 1) else {
            preconditionFailure("could not build a mono float format")
        }
        // The render block's fourth argument is a raw AudioBufferList (and the
        // closure returns an OSStatus), not an AVAudioPCMBuffer. Swift imports the
        // flexible `mBuffers` array as a single `AudioBuffer`, so `.mBuffers.mData`
        // is the first buffer's sample pointer — the format is float32 mono, so we
        // fill it through a Float pointer.
        source = AVAudioSourceNode(format: format) { [weak self] isSilence, timestamp, frameCount, outputData in
            guard let self else { return noErr }
            if timestamp.pointee.mFlags.contains(.hostTimeValid) {
                let nanos = OutputClock.hostToNanos(timestamp.pointee.mHostTime)
                self.framesLock.lock()
                self.stamp = OutputClock.Stamp(frame: self.framesRendered, nanos: nanos)
                self.framesLock.unlock()
            }
            let buffer = outputData.pointee.mBuffers
            guard let data = buffer.mData else {
                isSilence.pointee = true
                return noErr
            }
            let channel = data.assumingMemoryBound(to: Float.self)
            self.renderInto(frameCount: frameCount, channel: channel)
            isSilence.pointee = false
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
    }

    var isRunning: Bool { engine.isRunning }

    /// Frames handed to the hardware so far — THE authoritative position the clock,
    /// playhead and trigger placement all read.
    var framesRenderedNow: Int64 {
        framesLock.lock(); defer { framesLock.unlock() }
        return framesRendered
    }

    /// The frame heard at [nowNanos], or nil when there is no fresh timestamp
    /// (stopped, or the engine just restarted).
    func presentedFrame(nowNanos: UInt64) -> Int64? {
        framesLock.lock(); defer { framesLock.unlock() }
        return OutputClock.presentedFrame(
            stamp: stamp, nowNanos: nowNanos, latencyNanos: latencyNanos,
            sampleRate: sampleRate, rendered: framesRendered
        )
    }

    func setOutputLatency(seconds: Double) {
        framesLock.lock(); defer { framesLock.unlock() }
        latencyNanos = UInt64(max(0, seconds) * 1e9)
    }

    var outputLatencyMs: Int {
        framesLock.lock(); defer { framesLock.unlock() }
        return Int(latencyNanos / 1_000_000)
    }

    /// Attach the renderer after the owning object has fully initialised itself,
    /// since it references that very object. Until then the block vends silence.
    func attachRenderer(_ renderer: BlockRenderer) {
        self.renderer = renderer
    }

    /// Start the engine. Idempotent. Returns false if it could not start; the
    /// engine stays usable and silent rather than crashing, so a broken audio path
    /// degrades the way a failed sample load does.
    @discardableResult
    func start() -> Bool {
        if engine.isRunning { return true }
        do {
            try engine.start()
            return true
        } catch {
            return false
        }
    }

    /// Stop the engine. Safe to call twice.
    func stop() {
        if engine.isRunning { engine.stop() }
    }

    private func renderInto(frameCount: AVAudioFrameCount, channel: UnsafeMutablePointer<Float>) {
        guard let renderer else {
            // Not attached yet (only possible in the instant between init and
            // attachRenderer): write silence.
            for i in 0..<Int(frameCount) { channel[i] = 0 }
            return
        }
        let total = Int(frameCount)

        var processed = 0
        while processed < total {
            let n = min(blockSize, total - processed)
            framesLock.lock()
            let base = framesRendered
            framesLock.unlock()

            renderer.render(fromFrame: base + Int64(processed), count: n, out: &scratch)

            // Write to channel + processed, NOT channel: the hardware requests far more
            // frames than one blockSize, so this loop runs several times per callback. Writing
            // every chunk to channel[0] would overwrite the first 256 frames again and again
            // and leave the rest of the buffer uninitialised — exactly the "buzz + pulsing"
            // a half-written audio buffer produces.
            scratch.withUnsafeBufferPointer { buf in
                if let ptr = buf.baseAddress {
                    channel.advanced(by: processed).update(from: ptr, count: n)
                }
            }
            processed += n
        }

        framesLock.lock()
        framesRendered += Int64(total)
        framesLock.unlock()
    }
}
