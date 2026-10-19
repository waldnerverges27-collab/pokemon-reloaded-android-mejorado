//! Native Android audio output via AAudio.
//!
//! The rest of the engine feeds a `Mixer` (an `udon::Source`). On desktop, `udon`'s SoundIo
//! session pulls from it; there is no such backend for Android, so this module opens an AAudio
//! output stream and drives the very same `Mixer`.
//!
//! AAudio lives in `libaaudio.so` (linked from the NDK sysroot), so this is plain FFI — no extra
//! crates and no C++ toolchain.
//!
//! # Why the mixer does not run in AAudio's callback
//!
//! The mixer does real work: it synthesizes MIDI (rustysynth), decodes MP3, resamples and sums
//! every active sound. Doing that inside AAudio's real-time callback, whose deadline is a few
//! milliseconds in low-latency mode, produced dropouts on phones whose cores were throttled or
//! busy. Instead:
//!
//! * a dedicated, raised-priority *render thread* runs the mixer in 10 ms blocks and keeps a
//!   lock-free ring buffer topped up to ~40 ms;
//! * AAudio's callback only copies from that ring (and writes silence on an underrun, which is
//!   counted and logged), so it never blocks, allocates or does any maths of consequence;
//! * if the stream is torn down by the system (headphones, Bluetooth, route change) it is
//!   reopened, so the sound no longer stays dead until the app is restarted.

use std::{
    cell::UnsafeCell,
    ffi::c_void,
    sync::{
        atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering},
        Arc, OnceLock,
    },
    time::Duration,
};

use udon::source::{ChannelCount, Sample, SampleRate, Source};

use super::mixer::{Mixer, MixerHandle};

// aaudio/AAudio.h
const AAUDIO_OK: i32 = 0;
const AAUDIO_ERROR_DISCONNECTED: i32 = -899;
const AAUDIO_DIRECTION_OUTPUT: i32 = 0;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
const AAUDIO_PERFORMANCE_MODE_NONE: i32 = 10;
const AAUDIO_USAGE_GAME: i32 = 14;
const AAUDIO_CALLBACK_RESULT_CONTINUE: i32 = 0;

/// How much audio the render thread keeps queued ahead of the device, in milliseconds. A 30 fps
/// game does not need tighter latency than this, and it is what absorbs scheduling hiccups.
const RING_TARGET_MS: usize = 60;
/// Size of one mixer block, in milliseconds.
const BLOCK_MS: usize = 10;

extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_setDirection(builder: *mut c_void, direction: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut c_void, format: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut c_void, channel_count: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: *mut c_void, sample_rate: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut c_void, mode: i32);
    fn AAudioStreamBuilder_setUsage(builder: *mut c_void, usage: i32);
    fn AAudioStreamBuilder_setDataCallback(
        builder: *mut c_void,
        callback: extern "C" fn(*mut c_void, *mut c_void, *mut c_void, i32) -> i32,
        user_data: *mut c_void,
    );
    fn AAudioStreamBuilder_setErrorCallback(
        builder: *mut c_void,
        callback: extern "C" fn(*mut c_void, *mut c_void, i32),
        user_data: *mut c_void,
    );
    fn AAudioStreamBuilder_openStream(builder: *mut c_void, stream: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_delete(builder: *mut c_void) -> i32;
    fn AAudioStream_requestStart(stream: *mut c_void) -> i32;
    fn AAudioStream_requestPause(stream: *mut c_void) -> i32;
    fn AAudioStream_requestFlush(stream: *mut c_void) -> i32;
    fn AAudioStream_close(stream: *mut c_void) -> i32;
    fn AAudioStream_getChannelCount(stream: *mut c_void) -> i32;
    fn AAudioStream_getSampleRate(stream: *mut c_void) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: *mut c_void) -> i32;
    fn AAudioStream_getBufferCapacityInFrames(stream: *mut c_void) -> i32;
    fn AAudioStream_setBufferSizeInFrames(stream: *mut c_void, frames: i32) -> i32;
}

/// Single-producer / single-consumer ring buffer of samples. The render thread is the only
/// producer and AAudio's callback the only consumer, so two monotonic counters are enough.
struct Ring {
    buf: Box<[UnsafeCell<Sample>]>,
    /// Total samples ever written / read. Their difference is the fill level.
    head: AtomicUsize,
    tail: AtomicUsize,
}

// SAFETY: the producer only writes the region between `head` and `tail + cap`, the consumer only
// reads the region between `tail` and `head`; the atomics (acquire/release) hand regions over.
unsafe impl Sync for Ring {}

impl Ring {
    fn new(capacity: usize) -> Self {
        Self {
            buf: (0..capacity).map(|_| UnsafeCell::new(0.0)).collect(),
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
        }
    }

    fn available(&self) -> usize {
        self.head.load(Ordering::Acquire).wrapping_sub(self.tail.load(Ordering::Acquire))
    }

    /// Producer side. Returns how many samples fit.
    fn push(&self, data: &[Sample]) -> usize {
        let cap = self.buf.len();
        let head = self.head.load(Ordering::Relaxed);
        let free = cap - head.wrapping_sub(self.tail.load(Ordering::Acquire));
        let n = data.len().min(free);
        for (i, s) in data[..n].iter().enumerate() {
            // SAFETY: slot `(head + i) % cap` is in the free region, owned by the producer.
            unsafe { *self.buf[head.wrapping_add(i) % cap].get() = *s };
        }
        self.head.store(head.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumer side. Returns how many samples were copied into `out`.
    fn pop(&self, out: &mut [Sample]) -> usize {
        let cap = self.buf.len();
        let tail = self.tail.load(Ordering::Relaxed);
        let n = out.len().min(self.head.load(Ordering::Acquire).wrapping_sub(tail));
        for (i, o) in out[..n].iter_mut().enumerate() {
            // SAFETY: slot `(tail + i) % cap` is in the filled region, owned by the consumer.
            *o = unsafe { *self.buf[tail.wrapping_add(i) % cap].get() };
        }
        self.tail.store(tail.wrapping_add(n), Ordering::Release);
        n
    }
}

/// What the render thread and the callback share. Lives for the whole process.
struct Shared {
    ring: Ring,
    /// Samples per frame of the mixer (and of the ring).
    channels: usize,
    sample_rate: u32,
    /// Callbacks that found the ring short and had to pad with silence.
    underruns: AtomicU32,
}

static SHARED: OnceLock<Shared> = OnceLock::new();

/// The output stream, so it can be stopped and resumed when the app goes to the background and
/// comes back. Updated whenever the stream is reopened.
static STREAM: AtomicUsize = AtomicUsize::new(0);
/// Whether the app asked for the output to be paused (it is in the background).
static PAUSED: AtomicBool = AtomicBool::new(false);
/// A reopen is in flight; further disconnect notifications are ignored until it ends.
static REOPENING: AtomicBool = AtomicBool::new(false);

/// Set once the mixer has produced something audible. The launcher waits for this before handing the
/// screen over: the game paints a small "Loading…" screen well before its first sound.
static SOUND: AtomicBool = AtomicBool::new(false);

/// Whether anything has actually been heard yet.
pub fn sound_started() -> bool {
    SOUND.load(Ordering::Relaxed)
}

/// Stops or resumes the output. Without this the music keeps playing behind the home screen, which
/// is both wrong and how a game that never really paused gives itself away.
pub fn set_paused(paused: bool) {
    PAUSED.store(paused, Ordering::Relaxed);
    let stream = STREAM.load(Ordering::Relaxed) as *mut c_void;
    if stream.is_null() {
        return
    }
    unsafe {
        if paused {
            AAudioStream_requestPause(stream);
            // Drop what is already queued, so the silence arrives now instead of draining first.
            AAudioStream_requestFlush(stream);
        } else {
            let result = AAudioStream_requestStart(stream);
            if result != AAUDIO_OK {
                // The stream died while we were away (a route change in the background, say).
                eprintln!("[aaudio] no se pudo reanudar (error {}); reabriendo la salida", result);
                reopen_async();
            }
        }
    }
}

/// AAudio's real-time callback: copy from the ring, pad with silence if it ran dry. Nothing here
/// may block, allocate or take a lock.
extern "C" fn data_callback(
    _stream: *mut c_void,
    _user_data: *mut c_void,
    audio_data: *mut c_void,
    num_frames: i32,
) -> i32 {
    let channels = SHARED.get().map(|s| s.channels).unwrap_or(2);
    let len = (num_frames.max(0) as usize) * channels;
    // SAFETY: AAudio guarantees `audio_data` points to `num_frames * channels` f32 samples.
    let buffer = unsafe { std::slice::from_raw_parts_mut(audio_data as *mut Sample, len) };
    let copied = match SHARED.get() {
        Some(shared) => {
            let n = shared.ring.pop(buffer);
            if n < len && SOUND.load(Ordering::Relaxed) {
                shared.underruns.fetch_add(1, Ordering::Relaxed);
            }
            n
        },
        None => 0,
    };
    buffer[copied..].iter_mut().for_each(|x| *x = 0.0);
    AAUDIO_CALLBACK_RESULT_CONTINUE
}

/// Called by AAudio, on a thread of its own, when the stream is lost. The stream must not be
/// stopped or closed from here, so the recovery happens on another thread.
extern "C" fn error_callback(_stream: *mut c_void, _user_data: *mut c_void, error: i32) {
    eprintln!("[aaudio] error del stream: {}", error);
    if error == AAUDIO_ERROR_DISCONNECTED {
        reopen_async();
    }
}

fn reopen_async() {
    if REOPENING.swap(true, Ordering::SeqCst) {
        return
    }
    let spawned = std::thread::Builder::new().name("aaudio-reopen".into()).spawn(|| {
        let old = STREAM.swap(0, Ordering::SeqCst) as *mut c_void;
        if !old.is_null() {
            unsafe { AAudioStream_close(old) };
        }
        let (rate, channels) = match SHARED.get() {
            Some(s) => (s.sample_rate, s.channels),
            None => {
                REOPENING.store(false, Ordering::SeqCst);
                return
            },
        };
        // The new device may need a moment to appear, so retry for a few seconds.
        for attempt in 1..=15 {
            match open_stream(rate, channels) {
                Ok(stream) => {
                    if stream.sample_rate != rate {
                        // The mixer is fixed at `rate`; a device that insists on another one plays
                        // everything at the wrong speed, so say so in the log.
                        eprintln!("[aaudio] AVISO: el dispositivo nuevo usa {} Hz y la mezcla {} Hz", stream.sample_rate, rate);
                    }
                    STREAM.store(stream.pointer as usize, Ordering::SeqCst);
                    if !PAUSED.load(Ordering::Relaxed) {
                        unsafe { AAudioStream_requestStart(stream.pointer) };
                    }
                    eprintln!("[aaudio] salida reabierta (intento {})", attempt);
                    break
                },
                Err(e) => {
                    eprintln!("[aaudio] reabrir fallo (intento {}): {}", attempt, e);
                    std::thread::sleep(Duration::from_millis(400));
                },
            }
        }
        REOPENING.store(false, Ordering::SeqCst);
    });
    if spawned.is_err() {
        REOPENING.store(false, Ordering::SeqCst);
    }
}

fn check(result: i32, what: &str) -> Result<(), String> {
    if result == AAUDIO_OK {
        Ok(())
    } else {
        Err(format!("{} fallo (AAudio error {})", what, result))
    }
}

struct OpenStream {
    pointer: *mut c_void,
    channels: u32,
    sample_rate: u32,
}

/// Opens (but does not start) an output stream asking for `sample_rate` / `channels`.
fn open_stream(sample_rate: u32, channels: usize) -> Result<OpenStream, String> {
    unsafe {
        let mut builder: *mut c_void = std::ptr::null_mut();
        check(AAudio_createStreamBuilder(&mut builder), "AAudio_createStreamBuilder")?;

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, channels as i32);
        AAudioStreamBuilder_setSampleRate(builder, sample_rate as i32);
        // The ring buffer provides the safety margin, and the game does not need the tiny
        // buffers (and the MMAP path) that low-latency mode forces, which glitch on many phones.
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setDataCallback(builder, data_callback, std::ptr::null_mut());
        AAudioStreamBuilder_setErrorCallback(builder, error_callback, std::ptr::null_mut());

        let mut stream: *mut c_void = std::ptr::null_mut();
        let result = AAudioStreamBuilder_openStream(builder, &mut stream);
        AAudioStreamBuilder_delete(builder);
        check(result, "AAudioStreamBuilder_openStream")?;

        // A little more than the default two bursts, to ride out a late callback.
        let burst = AAudioStream_getFramesPerBurst(stream);
        let capacity = AAudioStream_getBufferCapacityInFrames(stream);
        if burst > 0 && capacity > 0 {
            AAudioStream_setBufferSizeInFrames(stream, (burst * 3).min(capacity));
        }

        let actual_channels = AAudioStream_getChannelCount(stream).max(1) as u32;
        let actual_rate = AAudioStream_getSampleRate(stream).max(8000) as u32;
        eprintln!(
            "[aaudio] stream abierto: {} canales @ {} Hz, burst {} frames, capacidad {} frames",
            actual_channels, actual_rate, burst, capacity
        );
        Ok(OpenStream { pointer: stream, channels: actual_channels, sample_rate: actual_rate })
    }
}

/// Gentle limiter: music plus several effects can sum past full scale, and hard clipping on the
/// device is what makes busy moments crackle. Only samples above the knee are touched.
#[inline]
fn soft_clip(buffer: &mut [Sample]) {
    const KNEE: f32 = 0.8;
    for x in buffer.iter_mut() {
        let a = x.abs();
        if a > KNEE {
            *x = x.signum() * (KNEE + (1.0 - KNEE) * ((a - KNEE) / (1.0 - KNEE)).tanh());
        }
    }
}

/// Best effort: ask for audio-thread priority. Fails quietly where the system does not allow it.
fn raise_priority() {
    unsafe {
        let tid = libc::gettid();
        // Process.THREAD_PRIORITY_AUDIO
        let result = libc::setpriority(libc::PRIO_PROCESS as _, tid as _, -16);
        let now = libc::getpriority(libc::PRIO_PROCESS as _, tid as _);
        eprintln!("[aaudio] prioridad del hilo de audio: resultado {}, nice actual {}", result, now);
    }
}

/// Runs the mixer ahead of the device, keeping the ring around [RING_TARGET_MS] full.
fn render_loop(mut mixer: Mixer, shared: &'static Shared) {
    raise_priority();
    let channels = shared.channels;
    let block_samples = (shared.sample_rate as usize * BLOCK_MS / 1000).max(64) * channels;
    let target = (shared.sample_rate as usize * RING_TARGET_MS / 1000) * channels;
    let mut block = vec![0.0 as Sample; block_samples];
    let mut reported = 0u32;
    let mut last_report = std::time::Instant::now();

    loop {
        if PAUSED.load(Ordering::Relaxed) {
            // Nothing is listening: do not burn battery waking up in the background.
            std::thread::sleep(Duration::from_millis(50));
            continue
        }

        if shared.ring.available() < target {
            let count = mixer.write_samples(&mut block).min(block.len());
            block[count..].iter_mut().for_each(|x| *x = 0.0);
            soft_clip(&mut block);
            if !SOUND.load(Ordering::Relaxed) && block.iter().any(|x| *x != 0.0) {
                SOUND.store(true, Ordering::Relaxed);
            }
            let mut written = 0;
            while written < block.len() {
                written += shared.ring.push(&block[written..]);
                if written < block.len() {
                    // Cannot happen while the capacity is several times the target, but never spin.
                    std::thread::sleep(Duration::from_millis(1));
                }
            }
        } else {
            std::thread::sleep(Duration::from_millis(2));
        }

        if last_report.elapsed() >= Duration::from_secs(10) {
            last_report = std::time::Instant::now();
            let underruns = shared.underruns.load(Ordering::Relaxed);
            if underruns != reported {
                eprintln!("[aaudio] {} huecos de audio en total (+{})", underruns, underruns - reported);
                reported = underruns;
            }
        }
    }
}

/// Opens the output stream, starts the render thread and hands back the handle used to add
/// sounds, plus the stream pointer, the device channel count and sample rate.
pub fn open(global_volume: Arc<AtomicU32>) -> Result<(usize, ChannelCount, SampleRate, MixerHandle), String> {
    if SHARED.get().is_some() {
        return Err("la salida de audio ya esta abierta".into())
    }
    let stream = open_stream(48000, 2)?;
    let channel_count = ChannelCount::new(stream.channels as u16).ok_or("canal de audio invalido")?;
    let sample_rate = SampleRate::new(stream.sample_rate).ok_or("frecuencia de audio invalida")?;
    let channels = stream.channels as usize;

    let (mixer, handle) = Mixer::new(sample_rate, channel_count, global_volume);

    let target = (stream.sample_rate as usize * RING_TARGET_MS / 1000) * channels;
    let shared = SHARED.get_or_init(|| Shared {
        ring: Ring::new(target * 4),
        channels,
        sample_rate: stream.sample_rate,
        underruns: AtomicU32::new(0),
    });

    std::thread::Builder::new()
        .name("audio-render".into())
        .spawn(move || render_loop(mixer, shared))
        .map_err(|e| format!("no se pudo crear el hilo de audio: {}", e))?;

    STREAM.store(stream.pointer as usize, Ordering::Relaxed);
    // Give the render thread a head start so the first callbacks find audio (silence) waiting.
    std::thread::sleep(Duration::from_millis(BLOCK_MS as u64 * 2));
    check(unsafe { AAudioStream_requestStart(stream.pointer) }, "AAudioStream_requestStart")?;
    eprintln!("[aaudio] salida lista (mezcla en hilo propio, colchon de {} ms)", RING_TARGET_MS);
    Ok((stream.pointer as usize, channel_count, sample_rate, handle))
}
