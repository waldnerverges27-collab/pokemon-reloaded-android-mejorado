//! Settings and live state of the Android extras that are not part of the original game: quick save
//! and load, fast-forward, hiding the buttons when idle, the screen filters, the on-screen stats and
//! the battery saver.
//!
//! The launcher writes `extras.conf` (`name=value` lines) before it starts the game and this module
//! reads it once. Everything is a plain atomic so the input thread, the game thread and the renderer
//! can each look at it without taking turns. A missing or damaged file means the defaults.

use std::{
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU16, AtomicU32, AtomicU8, Ordering},
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

/// The slot after the current one (1 -> 2 -> 3 -> 1), for this run of the game. Returns the new slot.
pub fn next_quick_slot() -> u8 {
    let next = quick_slot() % QUICK_SLOTS + 1;
    QUICK_SLOT.store(next, Ordering::Relaxed);
    next
}

/// Set when the launcher was opened from the "load quick save" shortcut: the game loads the slot by
/// itself once it is up, and asks only once.
static STARTUP_LOAD: AtomicBool = AtomicBool::new(false);

/// Whether a quick load is still waiting to be done because of the shortcut. Taking it clears it.
pub fn take_startup_load() -> bool {
    STARTUP_LOAD.swap(false, Ordering::SeqCst)
}

pub fn startup_load_pending() -> bool {
    STARTUP_LOAD.load(Ordering::SeqCst)
}

// ---------------------------------------------------------------------------------------------
// Fast-forward.

static FFWD_ON: AtomicBool = AtomicBool::new(false);
static SPEED_PERCENT: AtomicU16 = AtomicU16::new(200);
static FFWD_MUTE: AtomicBool = AtomicBool::new(true);

/// The speeds the player can choose from, as a percentage of normal. Below 100 is slow motion.
pub const SPEEDS: [u16; 6] = [50, 75, 150, 200, 300, 400];

/// The chosen speed, whether or not it is in force right now.
pub fn selected_speed() -> u32 {
    u32::from(SPEED_PERCENT.load(Ordering::Relaxed).clamp(SPEEDS[0], SPEEDS[SPEEDS.len() - 1]))
}

/// Whether the chosen speed is faster than normal (the only kind a hot phone refuses).
pub fn selected_is_fast() -> bool {
    selected_speed() > 100
}

/// Turns the chosen speed on or off. Returns whether it is on afterwards; a phone that is too hot
/// refuses a speed above normal.
pub fn toggle_ffwd() -> bool {
    let on = !FFWD_ON.load(Ordering::Relaxed) && !(selected_is_fast() && thermal_blocks_ffwd());
    FFWD_ON.store(on, Ordering::Relaxed);
    on
}

pub fn ffwd_on() -> bool {
    FFWD_ON.load(Ordering::Relaxed)
}

/// Moves on to the next speed: normal, then each of the list in turn, then normal again. The chosen
/// speed is kept while it is in use. Speeds above normal are skipped while the phone is too hot.
/// Returns the speed now in force (100 = normal).
pub fn cycle_speed() -> u32 {
    const STEPS: [u16; 7] = [100, 50, 75, 150, 200, 300, 400];
    let current = if FFWD_ON.load(Ordering::Relaxed) { selected_speed() as u16 } else { 100 };
    let start = STEPS.iter().position(|&s| s == current).unwrap_or(0);
    for step in 1..=STEPS.len() {
        let next = STEPS[(start + step) % STEPS.len()];
        if next > 100 && thermal_blocks_ffwd() {
            continue
        }
        if next == 100 {
            FFWD_ON.store(false, Ordering::Relaxed);
        } else {
            SPEED_PERCENT.store(next, Ordering::Relaxed);
            FFWD_ON.store(true, Ordering::Relaxed);
        }
        return u32::from(next)
    }
    100
}

/// How fast the game should run right now, as a percentage of normal (100 = normal speed).
pub fn speed_percent() -> u32 {
    if FFWD_ON.load(Ordering::Relaxed) && !(selected_is_fast() && thermal_blocks_ffwd()) {
        selected_speed()
    } else {
        100
    }
}

/// "x2", "x1,5", "x0,5": a speed as the player reads it.
pub fn speed_label(percent: u32) -> String {
    if percent % 100 == 0 {
        format!("x{}", percent / 100)
    } else {
        let text = format!("{}", f64::from(percent) / 100.0);
        format!("x{}", text.replace('.', ","))
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

/// Shows or hides the readout, for this run of the game. Returns whether it is shown now.
pub fn toggle_stats() -> bool {
    !STATS_ON.fetch_xor(true, Ordering::Relaxed)
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
    // Asked for afresh by each start of the game: an old request must not carry over.
    STARTUP_LOAD.store(false, Ordering::SeqCst);
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
            // The first launcher wrote a whole factor (2 to 4); it still reads.
            "ffwd_speed" => {
                if let Ok(speed) = value.parse::<u16>() {
                    SPEED_PERCENT.store(speed.clamp(2, 4) * 100, Ordering::Relaxed);
                }
            },
            "speed_percent" => {
                if let Ok(speed) = value.parse::<u16>() {
                    if SPEEDS.contains(&speed) {
                        SPEED_PERCENT.store(speed, Ordering::Relaxed);
                    }
                }
            },
            "startup" => STARTUP_LOAD.store(value == "qload", Ordering::Relaxed),
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

#[cfg(test)]
mod tests {
    use super::*;

    /// The speed tests change the same settings, so they take turns.
    static SPEED_TESTS: Mutex<()> = Mutex::new(());

    #[test]
    fn pad_shortcuts_cycle_the_slot_and_flip_the_readout() {
        let start = quick_slot();
        let mut seen = Vec::new();
        for _ in 0..QUICK_SLOTS {
            seen.push(next_quick_slot());
        }
        assert_eq!(quick_slot(), start, "a full turn comes back to the same slot");
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), usize::from(QUICK_SLOTS), "every slot comes up once");
        assert!(seen.iter().all(|slot| (1..=QUICK_SLOTS).contains(slot)));

        let before = stats_on();
        assert_eq!(toggle_stats(), !before);
        assert_eq!(stats_on(), !before);
        assert_eq!(toggle_stats(), before);
    }

    #[test]
    fn speeds_cycle_and_read_well() {
        let _turn = SPEED_TESTS.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(speed_label(50), "x0,5");
        assert_eq!(speed_label(75), "x0,75");
        assert_eq!(speed_label(150), "x1,5");
        assert_eq!(speed_label(300), "x3");
        SPEED_PERCENT.store(300, Ordering::Relaxed);
        THERMAL.store(0, Ordering::Relaxed);
        FFWD_ON.store(true, Ordering::Relaxed);
        assert_eq!(cycle_speed(), 400);
        assert_eq!(cycle_speed(), 100, "after the last one comes normal speed");
        assert_eq!(speed_percent(), 100);
        assert_eq!(cycle_speed(), 50);
        assert_eq!(speed_percent(), 50);
        assert_eq!(cycle_speed(), 75);
        assert_eq!(cycle_speed(), 150);
        THERMAL.store(3, Ordering::Relaxed);
        assert_eq!(speed_percent(), 100, "too hot: 1.5x is refused");
        assert_eq!(cycle_speed(), 100, "and the fast ones are skipped");
        assert_eq!(cycle_speed(), 50);
        THERMAL.store(0, Ordering::Relaxed);
        FFWD_ON.store(false, Ordering::Relaxed);
        assert_eq!(speed_percent(), 100);
    }

    #[test]
    fn the_shortcut_load_is_asked_once_and_never_carried_over() {
        let _turn = SPEED_TESTS.lock().unwrap_or_else(|e| e.into_inner());
        load("startup=qload\nspeed_percent=150\n");
        assert!(startup_load_pending());
        assert_eq!(selected_speed(), 150);
        assert!(take_startup_load());
        assert!(!take_startup_load(), "it is done once");
        load("startup=qload\n");
        load("quick_slot=2\n");
        assert!(!startup_load_pending(), "a later start without it does not inherit it");
        // The first launcher's whole factor still reads, and a speed outside the list is ignored.
        load("ffwd_speed=3\n");
        assert_eq!(selected_speed(), 300);
        load("speed_percent=120\n");
        assert_eq!(selected_speed(), 300);
    }
}
