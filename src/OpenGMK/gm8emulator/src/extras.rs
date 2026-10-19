//! Settings and live state of the Android extras that are not part of the original game: quick save
//! and load, fast-forward, hiding the buttons when idle, the screen filters, the on-screen stats and
//! the battery saver.
//!
//! The launcher writes `extras.conf` (`name=value` lines) before it starts the game and this module
//! reads it once. Everything is a plain atomic so the input thread, the game thread and the renderer
//! can each look at it without taking turns. A missing or damaged file means the defaults.

use std::{
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU8, Ordering},
        Mutex,
    },
    time::{Duration, Instant},
};

// ---------------------------------------------------------------------------------------------
// Quick save / load. The buttons only leave a request here; the game thread acts on it between two
// frames, when the game is in a state that can be saved.

const NO_REQUEST: u8 = 0;
const SAVE_REQUEST: u8 = 1;
const LOAD_REQUEST: u8 = 2;

static QUICK_REQUEST: AtomicU8 = AtomicU8::new(NO_REQUEST);
static QUICK_SLOT: AtomicU8 = AtomicU8::new(1);

/// How many slots there are. The launcher picks which one the buttons use.
pub const QUICK_SLOTS: u8 = 3;

pub fn request_quick(save: bool) {
    QUICK_REQUEST.store(if save { SAVE_REQUEST } else { LOAD_REQUEST }, Ordering::SeqCst);
}

/// `Some(true)` for a pending save, `Some(false)` for a pending load. Taking it clears it.
pub fn take_quick_request() -> Option<bool> {
    match QUICK_REQUEST.swap(NO_REQUEST, Ordering::SeqCst) {
        SAVE_REQUEST => Some(true),
        LOAD_REQUEST => Some(false),
        _ => None,
    }
}

pub fn quick_slot() -> u8 {
    QUICK_SLOT.load(Ordering::Relaxed).clamp(1, QUICK_SLOTS)
}

// ---------------------------------------------------------------------------------------------
// Fast-forward.

static FFWD_ON: AtomicBool = AtomicBool::new(false);
static FFWD_SPEED: AtomicU8 = AtomicU8::new(2);
static FFWD_MUTE: AtomicBool = AtomicBool::new(true);

pub fn toggle_ffwd() -> bool {
    let on = !FFWD_ON.load(Ordering::Relaxed) && !thermal_blocks_ffwd();
    FFWD_ON.store(on, Ordering::Relaxed);
    on
}

pub fn ffwd_on() -> bool {
    FFWD_ON.load(Ordering::Relaxed)
}

/// How many times faster than normal the game should run right now (1 = normal speed).
pub fn ffwd_factor() -> u32 {
    if FFWD_ON.load(Ordering::Relaxed) && !thermal_blocks_ffwd() {
        u32::from(FFWD_SPEED.load(Ordering::Relaxed).clamp(2, 4))
    } else {
        1
    }
}

/// Whether the sound is silenced while running fast. Sped-up sound effects pile up into noise and
/// the music stays at its normal speed, so silence is the clean option.
pub fn ffwd_mute() -> bool {
    FFWD_MUTE.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------------------------------------
// Buttons that fade away when nobody is touching the screen.

static AUTOHIDE_MS: AtomicU32 = AtomicU32::new(0);
static LAST_TOUCH: Mutex<Option<Instant>> = Mutex::new(None);

/// How faint the buttons get. They stay faintly visible, because a control that vanishes completely
/// is one the player has to remember is there.
const FADED: f32 = 0.18;
const FADE_TIME: Duration = Duration::from_millis(500);

/// Call on every touch.
pub fn touched() {
    *LAST_TOUCH.lock().unwrap() = Some(Instant::now());
}

/// Multiplier for the buttons' opacity: 1 while they are in use, easing to [FADED] once the screen
/// has been left alone for the chosen time. The buttons keep working while faded.
pub fn overlay_fade() -> f32 {
    let wait = AUTOHIDE_MS.load(Ordering::Relaxed);
    if wait == 0 {
        return 1.0
    }
    let idle = match *LAST_TOUCH.lock().unwrap() {
        Some(at) => at.elapsed(),
        // Nothing touched yet: start faded only after the first wait has passed since the start.
        None => return 1.0,
    };
    let wait = Duration::from_millis(u64::from(wait));
    if idle <= wait {
        return 1.0
    }
    let into = (idle - wait).as_secs_f32() / FADE_TIME.as_secs_f32();
    1.0 - (1.0 - FADED) * into.min(1.0)
}

// ---------------------------------------------------------------------------------------------
// Picture: filter, effect and the area the cutout leaves free.

static FILTER_SMOOTH: AtomicBool = AtomicBool::new(false);
static EFFECT: AtomicU8 = AtomicU8::new(0);
static SAFE_AREA: AtomicBool = AtomicBool::new(true);
static INSETS: [AtomicU32; 4] = [AtomicU32::new(0), AtomicU32::new(0), AtomicU32::new(0), AtomicU32::new(0)];

/// Whether the picture is scaled smoothly (true) or with hard pixels (false).
pub fn filter_smooth() -> bool {
    FILTER_SMOOTH.load(Ordering::Relaxed)
}

/// 0 = none, 1 = LCD grid, 2 = CRT scanlines.
pub fn effect() -> u8 {
    EFFECT.load(Ordering::Relaxed)
}

/// Left, top, right and bottom space (in pixels) the camera cutout and system bars take, or zeros
/// when the player wants the picture to use the whole screen.
pub fn safe_insets() -> [u32; 4] {
    if !SAFE_AREA.load(Ordering::Relaxed) {
        return [0; 4]
    }
    [
        INSETS[0].load(Ordering::Relaxed),
        INSETS[1].load(Ordering::Relaxed),
        INSETS[2].load(Ordering::Relaxed),
        INSETS[3].load(Ordering::Relaxed),
    ]
}

pub fn set_insets(left: u32, top: u32, right: u32, bottom: u32) {
    for (slot, value) in INSETS.iter().zip([left, top, right, bottom]) {
        slot.store(value, Ordering::Relaxed);
    }
}

// ---------------------------------------------------------------------------------------------
// Device state: temperature and the system's own battery saver.

/// `PowerManager` thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency.
static THERMAL: AtomicI32 = AtomicI32::new(0);
static POWER_SAVE: AtomicBool = AtomicBool::new(false);
/// 0 = off, 1 = always on, 2 = automatic (when the system saver is on or the phone runs warm).
static BATTERY_MODE: AtomicU8 = AtomicU8::new(0);

pub fn set_device_state(thermal: i32, power_save: bool) {
    THERMAL.store(thermal, Ordering::Relaxed);
    POWER_SAVE.store(power_save, Ordering::Relaxed);
}

pub fn thermal_status() -> i32 {
    THERMAL.load(Ordering::Relaxed)
}

/// Too hot to run faster than normal.
pub fn thermal_blocks_ffwd() -> bool {
    THERMAL.load(Ordering::Relaxed) >= 3
}

/// Whether the saver is in force: the game then asks the system for no more CPU than a frame needs.
pub fn battery_saver() -> bool {
    match BATTERY_MODE.load(Ordering::Relaxed) {
        1 => true,
        2 => POWER_SAVE.load(Ordering::Relaxed) || THERMAL.load(Ordering::Relaxed) >= 2,
        _ => false,
    }
}

// ---------------------------------------------------------------------------------------------
// The little readout in the corner.

static STATS_ON: AtomicBool = AtomicBool::new(false);
static STATS_TEXT: Mutex<String> = Mutex::new(String::new());

pub fn stats_on() -> bool {
    STATS_ON.load(Ordering::Relaxed)
}

pub fn set_stats_text(text: String) {
    *STATS_TEXT.lock().unwrap() = text;
}

pub fn stats_text() -> String {
    STATS_TEXT.lock().unwrap().clone()
}

// ---------------------------------------------------------------------------------------------
// Pad.

/// 0 = off, 1 = light, 2 = strong.
static RUMBLE: AtomicU8 = AtomicU8::new(1);
static PAD_DEVICE: AtomicI32 = AtomicI32::new(-1);

pub fn rumble_level() -> u8 {
    RUMBLE.load(Ordering::Relaxed).min(2)
}

pub fn set_pad_device(id: i32) {
    PAD_DEVICE.store(id, Ordering::Relaxed);
}

pub fn pad_device() -> i32 {
    PAD_DEVICE.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------------------------------------
// The file.

/// Applies `extras.conf`. Unknown names and unreadable values are skipped.
pub fn load(text: &str) {
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue
        }
        let Some((name, value)) = line.split_once('=') else { continue };
        let (name, value) = (name.trim(), value.trim());
        let flag = value != "0" && !value.eq_ignore_ascii_case("false");
        match name {
            "quick_slot" => {
                if let Ok(slot) = value.parse::<u8>() {
                    QUICK_SLOT.store(slot.clamp(1, QUICK_SLOTS), Ordering::Relaxed);
                }
            },
            "ffwd_speed" => {
                if let Ok(speed) = value.parse::<u8>() {
                    FFWD_SPEED.store(speed.clamp(2, 4), Ordering::Relaxed);
                }
            },
            "ffwd_mute" => FFWD_MUTE.store(flag, Ordering::Relaxed),
            "autohide_ms" => {
                if let Ok(ms) = value.parse::<u32>() {
                    AUTOHIDE_MS.store(ms.min(60_000), Ordering::Relaxed);
                }
            },
            "filter" => FILTER_SMOOTH.store(value == "smooth", Ordering::Relaxed),
            "effect" => EFFECT.store(
                match value {
                    "lcd" => 1,
                    "crt" => 2,
                    _ => 0,
                },
                Ordering::Relaxed,
            ),
            "safe_area" => SAFE_AREA.store(flag, Ordering::Relaxed),
            "stats" => STATS_ON.store(flag, Ordering::Relaxed),
            "battery" => {
                if let Ok(mode) = value.parse::<u8>() {
                    BATTERY_MODE.store(mode.min(2), Ordering::Relaxed);
                }
            },
            "rumble" => {
                if let Ok(level) = value.parse::<u8>() {
                    RUMBLE.store(level.min(2), Ordering::Relaxed);
                }
            },
            _ => {},
        }
    }
}
