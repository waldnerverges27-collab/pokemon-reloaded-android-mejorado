//! Glue between the Android activity and ramen's Android platform backend.
//!
//! `android-activity` hands us an [`InputEvent`] enum; everything it reports is translated
//! into ramen's `InputEvent` queue so that `Window::poll_events()` can drain it. Only the
//! public wrapper API is used, so this doesn't chase ndk's FFI shape between versions.

use std::time::{Duration, Instant};

use std::collections::HashMap;
use std::sync::Mutex;

use android_activity::{
    input::{Axis, InputEvent, KeyAction, MotionAction},
    AndroidApp, PollEvent,
};

use ramen::{input::Key, platform::android};

/// `AKEYCODE_*`, from <android/keycodes.h>. Letters are contiguous from `A`.
type AKeyCode = i32;
const AKEYCODE_A: AKeyCode = 29;
const AKEYCODE_DEL: AKeyCode = 67;
const AKEYCODE_TAB: AKeyCode = 61;
const AKEYCODE_ENTER: AKeyCode = 66;
const AKEYCODE_SPACE: AKeyCode = 62;
const AKEYCODE_DPAD_UP: AKeyCode = 19;
const AKEYCODE_DPAD_DOWN: AKeyCode = 20;
const AKEYCODE_DPAD_LEFT: AKeyCode = 21;
const AKEYCODE_DPAD_RIGHT: AKeyCode = 22;
const AKEYCODE_ESCAPE: AKeyCode = 111;

/// Gamepad buttons. Android reports the same keycodes for every pad, whatever the brand, so the
/// names below are the physical ones the player sees.
const AKEYCODE_BUTTON_A: AKeyCode = 96;
const AKEYCODE_BUTTON_B: AKeyCode = 97;
const AKEYCODE_BUTTON_X: AKeyCode = 99;
const AKEYCODE_BUTTON_Y: AKeyCode = 100;
const AKEYCODE_BUTTON_L1: AKeyCode = 102;
const AKEYCODE_BUTTON_R1: AKeyCode = 103;
const AKEYCODE_BUTTON_L2: AKeyCode = 104;
const AKEYCODE_BUTTON_R2: AKeyCode = 105;
const AKEYCODE_BUTTON_THUMBL: AKeyCode = 106;
const AKEYCODE_BUTTON_THUMBR: AKeyCode = 107;
const AKEYCODE_BUTTON_START: AKeyCode = 108;
const AKEYCODE_BUTTON_SELECT: AKeyCode = 109;

const LETTERS: [Key; 26] = [
    Key::A, Key::B, Key::C, Key::D, Key::E, Key::F, Key::G, Key::H, Key::I, Key::J, Key::K, Key::L,
    Key::M, Key::N, Key::O, Key::P, Key::Q, Key::R, Key::S, Key::T, Key::U, Key::V, Key::W, Key::X,
    Key::Y, Key::Z,
];

fn map_key(keycode: AKeyCode) -> Option<Key> {
    // A layout saved by the launcher's control screen wins over the built-in one; anything it
    // doesn't mention keeps the default below.
    if let Some(map) = std::sync::OnceLock::get(&MAPPED_BUTTONS) {
        if let Some(mapped) = map.lock().ok().and_then(|map| map.get(&keycode).copied()) {
            return Some(mapped)
        }
    }
    Some(match keycode {
        AKEYCODE_DEL => Key::Backspace,
        AKEYCODE_TAB => Key::Tab,
        AKEYCODE_ENTER => Key::Return,
        AKEYCODE_SPACE => Key::Space,
        AKEYCODE_ESCAPE => Key::Escape,
        AKEYCODE_DPAD_UP => Key::UpArrow,
        AKEYCODE_DPAD_DOWN => Key::DownArrow,
        AKEYCODE_DPAD_LEFT => Key::LeftArrow,
        AKEYCODE_DPAD_RIGHT => Key::RightArrow,

        // Gamepad. The game only ever reads the keyboard, so a controller plays by pressing the
        // same keys the keyboard does. That is the whole trick, and it needs nothing from the game:
        //
        //   D-pad                  -> arrow keys
        //   A (Xbox/Nintendo) / X (PS)      -> Z
        //   B (Xbox/Nintendo) / circle (PS) -> X
        //   X (Xbox/Nintendo) / square (PS) -> C
        //   Y (Xbox/Nintendo) / triangle    -> V
        //   select, share, view, minus      -> Enter
        //   start, options, menu, plus      -> Space
        //   R1 / RB / R                      -> B
        //   L1 / LB / L                      -> M
        //   R2 / RT / ZR                     -> O
        //   L2 / LT / ZL                     -> S
        //   stick press (either one)         -> E
        AKEYCODE_BUTTON_A => Key::Z,
        AKEYCODE_BUTTON_B => Key::X,
        AKEYCODE_BUTTON_X => Key::C,
        AKEYCODE_BUTTON_Y => Key::V,
        AKEYCODE_BUTTON_SELECT => Key::Return,
        AKEYCODE_BUTTON_START => Key::Space,
        AKEYCODE_BUTTON_R1 => Key::B,
        AKEYCODE_BUTTON_L1 => Key::M,
        AKEYCODE_BUTTON_R2 => Key::O,
        AKEYCODE_BUTTON_L2 => Key::S,
        AKEYCODE_BUTTON_THUMBL | AKEYCODE_BUTTON_THUMBR => Key::E,

        _ => return None,
    })
}

fn map_letter(keycode: AKeyCode) -> Option<Key> {
    let index = (keycode - AKEYCODE_A) as usize;
    LETTERS.get(index).copied()
}

fn mapped_key(keycode: i32) -> Option<Key> {
    map_key(keycode).or_else(|| map_letter(keycode))
}

/// The pad layout the launcher's control screen saved, keyed by Android key code. The contents are
/// replaced on every run rather than cached, because the engine and the launcher share one process
/// and the layout can change between one run of the game and the next.
static MAPPED_BUTTONS: std::sync::OnceLock<Mutex<HashMap<AKeyCode, Key>>> = std::sync::OnceLock::new();

/// Whether the physical sticks move the player.
static STICK_ENABLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

/// Which arrow keys the physical stick is holding (bit 0 up, 1 down, 2 left, 3 right). Kept apart
/// from the on-screen joystick's state so neither can release a key the other is holding.
static STICK_STATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// Reads the video mode the launcher saved. Only one thing matters here: whether the picture keeps
/// the shape the game was drawn for, or stretches to fill the screen.
fn load_video() {
    let path = PathBuf::from(format!("/data/data/{}/files/video.conf", PACKAGE_DIR));
    if let Ok(text) = fs::read_to_string(&path) {
        for line in text.lines() {
            let Some((name, value)) = line.trim().split_once('=') else { continue };
            if name.trim() != "scaling" {
                continue
            }
            let value = value.trim();
            if value == "aspect" || value == "full" || value == "integer" {
                eprintln!("[video] escalado: {}", value);
                // Read by the engine when it builds its renderer.
                std::env::set_var("OPENGMK_SCALING", value);
            }
        }
    }
}

/// Reads the layout the launcher's control screen left behind. Best effort: with no file, the
/// built-in layout is used exactly as before.
fn load_controls() {
    let path = PathBuf::from(format!("/data/data/{}/files/controls.conf", PACKAGE_DIR));
    let mut buttons = HashMap::new();
    let mut actions = HashMap::new();
    if let Ok(text) = fs::read_to_string(&path) {
        eprintln!("[pad] leyendo {}", path.display());
        for line in text.lines() {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue
                }
                eprintln!("[pad]   {}", line);
            let Some((name, value)) = line.split_once('=') else { continue };
            if name.trim() == "stick" {
                STICK_ENABLED.store(value.trim() != "0", std::sync::atomic::Ordering::Relaxed);
            } else if name.trim() == "pad" {
                // A pad was connected when the game started, so start with the controls out of the
                // way; touching the screen brings them back.
                crate::touch_controls::set_pad_active(value.trim() != "0");
            } else if name.trim() == "style" {
                crate::touch_controls::set_native_style(value.trim() != "pixel");
            } else if name.trim() == "scale" {
                if let Ok(scale) = value.trim().parse::<f32>() {
                    crate::touch_controls::set_button_scale(scale);
                }
            } else if name.trim() == "deadzone" {
                if let Ok(dead) = value.trim().parse::<f32>() {
                    crate::touch_controls::set_joystick_dead(dead);
                }
            } else if name.trim() == "floating" {
                crate::touch_controls::set_joystick_floating(value.trim() != "0");
            } else if name.trim() == "haptics" {
                HAPTICS.store(value.trim().parse::<u8>().unwrap_or(1).min(2), std::sync::atomic::Ordering::Relaxed);
            } else if name.trim() == "opacity" {
                if let Ok(opacity) = value.trim().parse::<f32>() {
                    crate::touch_controls::set_sprite_opacity(opacity);
                }
            } else if let (Ok(code), Some(action)) = (name.trim().parse::<AKeyCode>(), pad_action_named(value.trim())) {
                // A pad button bound to one of the app's own actions instead of a key of the game.
                actions.insert(code, action);
            } else if let (Ok(code), Some(key)) = (name.trim().parse::<AKeyCode>(), parse_key(value.trim())) {
                buttons.insert(code, key);
            }
        }
    }
    // The player's own layout, if the launcher's editor has written one.
    let layout = PathBuf::from(format!("/data/data/{}/files/layout.conf", PACKAGE_DIR));
    crate::touch_controls::load_layout(&fs::read_to_string(&layout).unwrap_or_default());
    // The extras: quick save slot, fast-forward, filters, the stats readout and so on.
    let extras = PathBuf::from(format!("/data/data/{}/files/extras.conf", PACKAGE_DIR));
    crate::extras::load(&fs::read_to_string(&extras).unwrap_or_default());
    if let Ok(mut map) = PAD_ACTIONS.get_or_init(|| Mutex::new(HashMap::new())).lock() {
        *map = actions;
    }
    // Replaced rather than cached: the launcher can change the layout while the app is still alive.
    if let Ok(mut map) = MAPPED_BUTTONS.get_or_init(|| Mutex::new(HashMap::new())).lock() {
        *map = buttons;
    }
}

/// Pad buttons bound to the app's own actions (quick save, quick load, fast-forward).
static PAD_ACTIONS: std::sync::OnceLock<Mutex<HashMap<AKeyCode, crate::touch_controls::SysAction>>> = std::sync::OnceLock::new();

fn pad_action_named(name: &str) -> Option<crate::touch_controls::SysAction> {
    use crate::touch_controls::SysAction;
    match name.to_ascii_uppercase().as_str() {
        "QSAVE" => Some(SysAction::QuickSave),
        "QLOAD" => Some(SysAction::QuickLoad),
        "FFWD" => Some(SysAction::FastForward),
        _ => None,
    }
}

fn pad_action(code: i32) -> Option<crate::touch_controls::SysAction> {
    PAD_ACTIONS.get()?.lock().ok()?.get(&code).copied()
}

/// Does what an action of the app's own asks for. Quick save and load only leave a request: the
/// game thread carries it out between two frames.
fn run_sys_action(action: crate::touch_controls::SysAction) {
    use crate::touch_controls::SysAction;
    match action {
        SysAction::QuickSave => crate::extras::request_quick(true),
        SysAction::QuickLoad => crate::extras::request_quick(false),
        SysAction::FastForward => {
            if crate::extras::toggle_ffwd() {
                toast(&format!("Avance rapido x{}", crate::extras::ffwd_factor()));
            } else if crate::extras::thermal_blocks_ffwd() {
                toast("El movil esta demasiado caliente para el avance rapido");
            } else {
                toast("Velocidad normal");
            }
        },
    }
}

/// A short system message over the game (a `Toast`).
pub fn toast(message: &str) {
    let text = message.to_string();
    with_activity(|env, class| {
        let java = env.new_string(&text)?;
        env.call_static_method(class, "toast", "(Ljava/lang/String;)V", &[jni::objects::JValue::Object(&java)])?;
        Ok(())
    });
}

/// The key names the control screen can write into `controls.conf`.
fn parse_key(name: &str) -> Option<Key> {
    let upper = name.trim().to_ascii_uppercase();
    if upper.len() == 1 {
        let byte = upper.as_bytes()[0];
        if byte.is_ascii_uppercase() {
            return LETTERS.get(usize::from(byte - b'A')).copied()
        }
    }
    Some(match upper.as_str() {
        "RETURN" | "ENTER" => Key::Return,
        "SPACE" => Key::Space,
        "ESCAPE" => Key::Escape,
        "TAB" => Key::Tab,
        "BACKSPACE" => Key::Backspace,
        "UP" => Key::UpArrow,
        "DOWN" => Key::DownArrow,
        "LEFT" => Key::LeftArrow,
        "RIGHT" => Key::RightArrow,
        _ => return None,
    })
}

/// Holds and releases the arrow keys the physical stick asks for, so pushing it feels exactly like
/// the on-screen joystick.
fn stick_update(x: f32, y: f32) {
    let mut state = STICK_STATE.load(std::sync::atomic::Ordering::Relaxed);
    let dirs = crate::touch_controls::stick_dirs_held(x, y, state);
    let before = state;
    for (index, held) in dirs.iter().enumerate() {
        let bit = 1u32 << index;
        let was = state & bit != 0;
        if *held && !was {
            android::push_input(ramen::platform::android::InputEvent::KeyDown { key: JOYSTICK_KEYS[index], text: None });
        } else if !*held && was {
            android::push_input(ramen::platform::android::InputEvent::KeyUp { key: JOYSTICK_KEYS[index] });
        }
        if *held {
            state |= bit;
        } else {
            state &= !bit;
        }
    }
    STICK_STATE.store(state, std::sync::atomic::Ordering::Relaxed);
    if state != before {
        // Only when the direction actually changes, so holding a direction does not flood logcat.
        eprintln!("[pad] ejes x={:.2} y={:.2} -> direcciones {}", x, y, state);
    }
}

/// The value of one axis of a motion event. Axis values belong to the event, and the crate reaches
/// them through its first pointer, so there has to be one.
fn axis_value(motion: &android_activity::input::MotionEvent, axis: Axis) -> f32 {
    if motion.pointer_count() == 0 {
        return 0.0
    }
    motion.pointer_at_index(0).axis_value(axis)
}

fn stick_release() {
    let state = STICK_STATE.swap(0, std::sync::atomic::Ordering::Relaxed);
    for (index, key) in JOYSTICK_KEYS.iter().enumerate() {
        if state & (1 << index) != 0 {
            android::push_input(ramen::platform::android::InputEvent::KeyUp { key: *key });
        }
    }
}

/// `Keycode` is a repr(i32) enum, so the raw AKEYCODE_* value is recoverable.
fn key_code_value(keycode: &android_activity::input::Keycode) -> i32 {
    // Keycode's variants are AKEYCODE_*; its Debug form is "A(29)" style, so use its
    // discriminant through `Clone`-free transmute-free access below.
    unsafe { *std::ptr::from_ref(keycode).cast::<i32>().to_owned() }
}

/// Which control each active pointer is currently holding, so a multi-touch release is paired with
/// the right control. Keyed by Android pointer id.
static POINTER_CONTROLS: Mutex<Option<HashMap<i32, usize>>> = Mutex::new(None);

/// The pointer the party wheel is following, if any.
static WHEEL_PTR: Mutex<Option<i32>> = Mutex::new(None);

/// Sends a key as a quick tap. The engine defers the release to the end of the poll, so the game
/// still sees it as pressed.
fn tap_key(key: ramen::input::Key) {
    android::push_input(ramen::platform::android::InputEvent::KeyDown { key, text: None });
    android::push_input(ramen::platform::android::InputEvent::KeyUp { key });
}

fn control_key_down(index: usize) {
    crate::touch_controls::set_pressed(index, true);
    haptic(HapticKind::Press);
    // The app's own buttons do their job and send nothing to the game.
    if let Some(action) = crate::touch_controls::sys_action(index) {
        run_sys_action(action);
        return
    }
    // TEMP: the trick combination needs both keys held at once, so it is not one key like the rest.
    if crate::touch_controls::CONTROLS[index].name == crate::touch_controls::COMBO_BUTTON_NAME {
        for key in [Key::LeftControl, Key::P] {
            android::push_input(ramen::platform::android::InputEvent::KeyDown { key, text: None });
        }
        // It has done its job: it takes itself away until the next new game.
        crate::touch_controls::set_combo_used();
        return
    }
    let key = crate::touch_controls::key_of(index);
    // On the character picker the overlay's arrows travel with the game's own selection, so the
    // buttons stay around the character the player is on rather than around the first one.
    match key {
        Key::LeftArrow => crate::touch_controls::picker_step(-1),
        Key::RightArrow => crate::touch_controls::picker_step(1),
        _ => {},
    }
    android::push_input(ramen::platform::android::InputEvent::KeyDown { key, text: None });
}

fn control_key_up(index: usize) {
    crate::touch_controls::set_pressed(index, false);
    if crate::touch_controls::sys_action(index).is_some() {
        return
    }
    if crate::touch_controls::CONTROLS[index].name == crate::touch_controls::COMBO_BUTTON_NAME {
        for key in [Key::LeftControl, Key::P] {
            android::push_input(ramen::platform::android::InputEvent::KeyUp { key });
        }
        return
    }
    android::push_input(ramen::platform::android::InputEvent::KeyUp { key: crate::touch_controls::key_of(index) });
}

/// Pointers whose press was swallowed to bring the on-screen controls back, so that their release
/// is ignored too instead of reaching the game as a stray mouse-up.
static SWALLOWED_INPUTS: Mutex<Option<std::collections::HashSet<i32>>> = Mutex::new(None);

/// A touch on an on-screen control presses the key that control maps to; any other touch is
/// forwarded to the game as a mouse, exactly as before.
fn pointer_down(id: i32, x: f32, y: f32) {
    let (w, h) = android::window_size();
    let (wf, hf) = (w as f32, h as f32);
    // The first touch after using a pad only brings the controls back. It must not also land in the
    // game as a click, or reaching for the on-screen buttons would act on whatever is under them.
    if crate::touch_controls::pad_active() {
        crate::touch_controls::set_pad_active(false);
        SWALLOWED_INPUTS.lock().unwrap().get_or_insert_with(std::collections::HashSet::new).insert(id);
        return
    }
    let mut on_joystick = wf > 0.0 && hf > 0.0 && crate::touch_controls::joystick_hit(x / wf, y / hf, wf, hf);
    // A floating stick takes any free touch in its zone, so a button that happens to be there wins.
    if on_joystick && crate::touch_controls::joystick_floating() && crate::touch_controls::hit(x / wf, y / hf, wf, hf).is_some() {
        on_joystick = false;
    }
    if on_joystick && crate::touch_controls::joystick_floating() {
        // Anchor the ring under the thumb, kept inside the screen so it is never half off it.
        let r = crate::touch_controls::joystick_radius() * hf;
        let cx = x.clamp(r, (wf - r).max(r)) / wf;
        let cy = y.clamp(r, (hf - r).max(r)) / hf;
        crate::touch_controls::set_joystick_center(cx, cy);
    }
    // Any *other* touch while the party wheel is up cancels it - but not the movement stick: while
    // walking the player keeps touching and lifting it, and that must not close the wheel.
    if crate::touch_controls::wheel_open() && *WHEEL_PTR.lock().unwrap() != Some(id) && !on_joystick {
        crate::touch_controls::set_wheel_open(false);
        WHEEL_PTR.lock().unwrap().take();
    }
    if on_joystick {
        *JOYSTICK_PTR.lock().unwrap() = Some(id);
        joystick_update(x, y);
        return
    }
    let hit = if w > 0 && h > 0 { crate::touch_controls::hit(x / wf, y / hf, wf, hf) } else { None };
    match hit {
        Some(index) => {
            if crate::touch_controls::CONTROLS[index].name == crate::touch_controls::PARTY_NAME {
                // Holding it opens the party wheel instead of pressing a key of its own.
                crate::touch_controls::set_wheel_open(true);
                *WHEEL_PTR.lock().unwrap() = Some(id);
            } else {
                control_key_down(index);
                POINTER_CONTROLS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, index);
                ROLLING.lock().unwrap().get_or_insert_with(std::collections::HashSet::new).insert(id);
            }
        },
        None => {
            // The game asks for text through its own dialog, and touching it opens the system
            // keyboard. The only clickable thing the game has there is its "Aceptar" box, so a touch
            // anywhere else is free; touching "Aceptar" still confirms, because the game keeps the tap.
            if crate::touch_controls::text_entry_open() {
                ime_set(true);
            }
            mouse_down(id, x, y)
        },
    }
}

fn pointer_up(id: i32, x: f32, y: f32) {
    if WHEEL_PTR.lock().unwrap().take() == Some(id) {
        // The key only goes out now, on the release, and only if a slot was picked. Letting go over
        // the middle of the wheel, or letting go without moving at all, sends nothing.
        let selected = crate::touch_controls::wheel_selected();
        if selected >= 0 {
            tap_key(crate::touch_controls::PICKER_KEYS[selected as usize]);
        }
        crate::touch_controls::set_wheel_open(false);
        return
    }
    if SWALLOWED_INPUTS.lock().unwrap().get_or_insert_with(std::collections::HashSet::new).remove(&id) {
        return
    }
    if is_joystick_pointer(id) {
        joystick_release();
        return
    }
    let index = POINTER_CONTROLS.lock().unwrap().get_or_insert_with(HashMap::new).remove(&id);
    let was_rolling = ROLLING.lock().unwrap().get_or_insert_with(std::collections::HashSet::new).remove(&id);
    match index {
        Some(index) => control_key_up(index),
        // A finger that started on a button and slid off it has nothing to release, and must not
        // turn into a click on the game underneath.
        None if was_rolling => {},
        None => mouse_up(id, x, y),
    }
}

/// A real tap lasts ~100 ms, but a quick one can deliver press and release inside a single poll.
/// The game reads the mouse once per frame, so such a tap is simply missed — that is why clicking
/// the game's menus with the finger felt random. Holding the release briefly makes taps behave
/// like real mouse clicks.
const MIN_TAP: Duration = Duration::from_millis(70);

/// The game decides which menu entry is under the cursor while it *moves*; a click only confirms
/// the current one. A finger sends move and press together, so the press used to land on whatever
/// was selected before. Delivering the move a hair earlier gives the game that frame.
const HOVER_DELAY: Duration = Duration::from_millis(45);

static MOUSE_DOWN_AT: Mutex<Vec<(i32, Instant)>> = Mutex::new(Vec::new());
static DELAYED_UPS: Mutex<Vec<(Instant, f32, f32)>> = Mutex::new(Vec::new());
static DELAYED_DOWNS: Mutex<Vec<(Instant, f32, f32)>> = Mutex::new(Vec::new());

fn mouse_down(id: i32, x: f32, y: f32) {
    let deliver_at = Instant::now() + HOVER_DELAY;
    let mut downs = MOUSE_DOWN_AT.lock().unwrap();
    downs.retain(|(pointer, _)| *pointer != id);
    downs.push((id, deliver_at));
    drop(downs);
    // The move goes out now; the press follows on a later poll so the hover is in place first.
    android::push_input(ramen::platform::android::InputEvent::PointerMoved { x, y });
    DELAYED_DOWNS.lock().unwrap().push((deliver_at, x, y));
}

fn mouse_up(id: i32, x: f32, y: f32) {
    let pressed_at = {
        let mut downs = MOUSE_DOWN_AT.lock().unwrap();
        let at = downs.iter().find(|(pointer, _)| *pointer == id).map(|(_, at)| *at);
        downs.retain(|(pointer, _)| *pointer != id);
        at
    };
    let deadline = pressed_at.map(|at| at + MIN_TAP).unwrap_or_else(Instant::now);
    if deadline > Instant::now() {
        DELAYED_UPS.lock().unwrap().push((deadline, x, y));
    } else {
        android::push_input(ramen::platform::android::InputEvent::PointerUp { x, y, button: 0 });
    }
}

/// Delivers the presses and releases that were held back to make a short tap a proper click.
fn flush_delayed_mouse() {
    let now = Instant::now();
    let mut downs: Vec<(f32, f32)> = Vec::new();
    DELAYED_DOWNS.lock().unwrap().retain(|(at, x, y)| {
        if *at <= now {
            downs.push((*x, *y));
            false
        } else {
            true
        }
    });
    for (x, y) in downs {
        android::push_input(ramen::platform::android::InputEvent::PointerDown { x, y, button: 0 });
    }

    let mut ups: Vec<(f32, f32)> = Vec::new();
    DELAYED_UPS.lock().unwrap().retain(|(at, x, y)| {
        if *at <= now {
            ups.push((*x, *y));
            false
        } else {
            true
        }
    });
    for (x, y) in ups {
        android::push_input(ramen::platform::android::InputEvent::PointerUp { x, y, button: 0 });
    }
}

/// Pointers that started on a button. They keep belonging to the buttons even when they slide off
/// one, so the finger can roll from one button to the next the way it does on a real gamepad.
static ROLLING: Mutex<Option<std::collections::HashSet<i32>>> = Mutex::new(None);

fn pointer_is_control(id: i32) -> bool {
    ROLLING.lock().unwrap().as_ref().map(|m| m.contains(&id)).unwrap_or(false)
}

/// A finger that began on a button has moved: release the button it left and press the one it is
/// over now. Leaving a button needs the finger to go a little past its edge (the test is made
/// against a button 12% larger than the drawn one), so a thumb that jitters on the border does not
/// flicker between pressed and released.
fn pointer_roll(id: i32, x: f32, y: f32) {
    let (w, h) = android::window_size();
    let (wf, hf) = (w as f32, h as f32);
    if wf <= 0.0 || hf <= 0.0 {
        return
    }
    let (nx, ny) = (x / wf, y / hf);
    let current = POINTER_CONTROLS.lock().unwrap().as_ref().and_then(|m| m.get(&id).copied());
    if let Some(index) = current {
        if crate::touch_controls::is_active(index) && crate::touch_controls::hit_with_margin(index, nx, ny, wf, hf, 1.12) {
            return
        }
    }
    let target = crate::touch_controls::hit(nx, ny, wf, hf)
        .filter(|&i| {
            // These two do something heavy on press (open the wheel, fire the trick combo), so a
            // finger only gets them by touching them on purpose, never by sliding across.
            let name = crate::touch_controls::CONTROLS[i].name;
            name != crate::touch_controls::PARTY_NAME && name != crate::touch_controls::COMBO_BUTTON_NAME
        });
    if target == current {
        return
    }
    if let Some(index) = current {
        POINTER_CONTROLS.lock().unwrap().get_or_insert_with(HashMap::new).remove(&id);
        control_key_up(index);
    }
    if let Some(index) = target {
        control_key_down(index);
        POINTER_CONTROLS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, index);
    }
}

// --- Movement joystick -------------------------------------------------------------------

/// Pointer id currently driving the joystick, if any.
static JOYSTICK_PTR: Mutex<Option<i32>> = Mutex::new(None);
/// Which arrow keys the joystick is holding (bit 0 up, 1 down, 2 left, 3 right).
static JOYSTICK_STATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

const JOYSTICK_KEYS: [Key; 4] = [Key::UpArrow, Key::DownArrow, Key::LeftArrow, Key::RightArrow];

fn is_joystick_pointer(id: i32) -> bool {
    *JOYSTICK_PTR.lock().unwrap() == Some(id)
}

/// Presses/releases the arrow keys so they match where the stick is being pushed.
fn joystick_update(x: f32, y: f32) {
    use std::sync::atomic::Ordering;
    let (w, h) = android::window_size();
    let (wf, hf) = (w as f32, h as f32);
    if wf <= 0.0 || hf <= 0.0 {
        return
    }
    // A floating stick follows the thumb: pushed past the ring, it drags the ring along so the
    // thumb ends up on its edge. This is what lets the thumb wander without the stick being lost.
    if crate::touch_controls::joystick_floating() {
        let (jcx, jcy) = crate::touch_controls::joystick_center();
        let r = crate::touch_controls::joystick_radius() * hf;
        let (dx, dy) = (x - jcx * wf, y - jcy * hf);
        let len = (dx * dx + dy * dy).sqrt();
        if len > r && r > 0.0 {
            let (ux, uy) = (dx / len, dy / len);
            let cx = (x - ux * r).clamp(r, (wf - r).max(r)) / wf;
            let cy = (y - uy * r).clamp(r, (hf - r).max(r)) / hf;
            crate::touch_controls::set_joystick_center(cx, cy);
        }
    }
    let mut state = JOYSTICK_STATE.load(Ordering::Relaxed);
    let before = state;
    let dirs = crate::touch_controls::joystick_dirs(x / wf, y / hf, wf, hf, state);
    for (i, &on) in dirs.iter().enumerate() {
        let bit = 1u32 << i;
        if on != (state & bit != 0) {
            if on {
                android::push_input(ramen::platform::android::InputEvent::KeyDown { key: JOYSTICK_KEYS[i], text: None });
                state |= bit;
            } else {
                android::push_input(ramen::platform::android::InputEvent::KeyUp { key: JOYSTICK_KEYS[i] });
                state &= !bit;
            }
        }
    }
    JOYSTICK_STATE.store(state, Ordering::Relaxed);
    // A tick each time the stick engages a new direction, like a d-pad clicking.
    if state & !before != 0 {
        haptic(HapticKind::Tick);
    }

    // Clamp the drawn knob to the ring.
    let (jcx, jcy) = crate::touch_controls::joystick_center();
    let r = crate::touch_controls::joystick_radius() * hf;
    let mut dx = (x - jcx * wf) / r;
    let mut dy = (y - jcy * hf) / r;
    let len = (dx * dx + dy * dy).sqrt();
    if len > 1.0 {
        dx /= len;
        dy /= len;
    }
    crate::touch_controls::set_joystick_offset(dx, dy);
}

fn joystick_release() {
    use std::sync::atomic::Ordering;
    let state = JOYSTICK_STATE.swap(0, Ordering::Relaxed);
    for i in 0..4 {
        if state & (1 << i) != 0 {
            android::push_input(ramen::platform::android::InputEvent::KeyUp { key: JOYSTICK_KEYS[i] });
        }
    }
    crate::touch_controls::set_joystick_offset(0.0, 0.0);
    crate::touch_controls::reset_joystick_center();
    *JOYSTICK_PTR.lock().unwrap() = None;
}

/// The pointer the current action refers to, if it is valid.
fn changed_pointer(motion: &android_activity::input::MotionEvent) -> Option<(i32, f32, f32)> {
    let index = motion.pointer_index();
    if index >= motion.pointer_count() {
        return None
    }
    let p = motion.pointer_at_index(index);
    Some((p.pointer_id(), p.x(), p.y()))
}

/// Android key codes for the hardware volume rocker.
const KEYCODE_VOLUME_UP: i32 = 24;
const KEYCODE_VOLUME_DOWN: i32 = 25;

/// Translates one `android-activity` input event into ramen's queue.
fn translate(event: &InputEvent) {
    match event {
        InputEvent::KeyEvent(key) => match key.action() {
            KeyAction::Down => {
                let code = key_code_value(&key.key_code());
                if code == KEYCODE_VOLUME_UP || code == KEYCODE_VOLUME_DOWN {
                    // Volume keys arrive through the native queue, so Android never handles them.
                    jni_adjust_volume(code == KEYCODE_VOLUME_UP);
                    return
                }
                crate::extras::set_pad_device(key.device_id());
                if let Some(action) = pad_action(code) {
                    crate::touch_controls::set_pad_active(true);
                    run_sys_action(action);
                    pad_rumble();
                    return
                }
                if let Some(k) = mapped_key(code) {
                    // A real key press means a pad or a keyboard is in the player's hands, so the
                    // on-screen controls get out of the way.
                    crate::touch_controls::set_pad_active(true);
                    pad_rumble();
                    eprintln!("[pad] tecla {} -> {:?}", code, k);
                    android::push_input(ramen::platform::android::InputEvent::KeyDown { key: k, text: None });
                }
            },
            KeyAction::Up => {
                if pad_action(key_code_value(&key.key_code())).is_some() {
                    return
                }
                if let Some(k) = mapped_key(key_code_value(&key.key_code())) {
                    android::push_input(ramen::platform::android::InputEvent::KeyUp { key: k });
                }
            },
            _ => {},
        },
        InputEvent::MotionEvent(motion) => {
            // A pad's stick arrives as a generic motion event, not as a key, so its axes have to be
            // read from the event itself rather than from a pointer.
            if motion.source().is_joystick_class() {
                crate::touch_controls::set_pad_active(true);
                if STICK_ENABLED.load(std::sync::atomic::Ordering::Relaxed) {
                    let (mut x, mut y) = (axis_value(motion, Axis::X), axis_value(motion, Axis::Y));
                    // A d-pad is usually reported as a hat axis rather than as keys, and it has to
                    // win over the stick when it is pressed: a resting stick is not always at exactly
                    // zero, and requiring that is what kept the d-pad from doing anything.
                    let (hat_x, hat_y) = (axis_value(motion, Axis::HatX), axis_value(motion, Axis::HatY));
                    if hat_x != 0.0 || hat_y != 0.0 {
                        x = hat_x;
                        y = hat_y;
                    }
                    stick_update(x, y);
                }
                return
            }

            // Any touch wakes the buttons if they had faded.
            crate::extras::touched();
            match motion.action() {
                MotionAction::Down | MotionAction::PointerDown => {
                    if let Some((id, x, y)) = changed_pointer(motion) {
                        pointer_down(id, x, y);
                    }
                },
                MotionAction::Up | MotionAction::PointerUp => {
                    if let Some((id, x, y)) = changed_pointer(motion) {
                        pointer_up(id, x, y);
                    }
                },
                MotionAction::Move | MotionAction::HoverMove => {
                    let wheel_ptr = *WHEEL_PTR.lock().unwrap();
                    for p in motion.pointers() {
                        if wheel_ptr == Some(p.pointer_id()) {
                            let (w, h) = android::window_size();
                            let (wf, hf) = (w as f32, h as f32);
                            if wf > 0.0 && hf > 0.0 {
                                // Only highlight while the finger moves. The key waits for the
                                // release: in battle it sends the Pokémon out, so it must never go
                                // out on a touch that was not meant as a pick.
                                let picked = crate::touch_controls::wheel_item_at(p.x() / wf, p.y() / hf, wf, hf);
                                crate::touch_controls::set_wheel_selected(picked.map(|i| i as i32).unwrap_or(-1));
                            }
                        } else if is_joystick_pointer(p.pointer_id()) {
                            joystick_update(p.x(), p.y());
                        } else if pointer_is_control(p.pointer_id()) {
                            pointer_roll(p.pointer_id(), p.x(), p.y());
                        } else {
                            android::push_input(ramen::platform::android::InputEvent::PointerMoved { x: p.x(), y: p.y() });
                        }
                    }
                },
                MotionAction::Cancel => {
                    // A cancelled gesture must not leave the wheel up forever.
                    if crate::touch_controls::wheel_open() {
                        crate::touch_controls::set_wheel_open(false);
                        WHEEL_PTR.lock().unwrap().take();
                    }
                    joystick_release();
                    ROLLING.lock().unwrap().take();
                    let ids: Vec<i32> =
                        POINTER_CONTROLS.lock().unwrap().as_ref().map(|m| m.keys().copied().collect()).unwrap_or_default();
                    for id in ids {
                        if let Some(index) = POINTER_CONTROLS.lock().unwrap().get_or_insert_with(HashMap::new).remove(&id) {
                            control_key_up(index);
                        }
                    }
                },
                _ => {},
            }
        },
        _ => {},
    }
}

// ---------------------------------------------------------------------------------------------
// Haptics and other calls into `GameActivity`.

/// 0 = off, 1 = light, 2 = strong. Written from `controls.conf`.
static HAPTICS: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(1);

#[derive(Clone, Copy)]
enum HapticKind {
    /// A button went down.
    Press,
    /// The stick engaged a new direction: lighter than a press.
    Tick,
}

/// Vibrates the way the player asked in the settings. The last tick is remembered so a burst of
/// events (several fingers, a stick scrubbing the dead zone) does not turn into a buzz.
fn haptic(kind: HapticKind) {
    use std::sync::atomic::Ordering;
    let level = HAPTICS.load(Ordering::Relaxed);
    if level == 0 {
        return
    }
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    {
        let mut last = LAST.lock().unwrap();
        let now = Instant::now();
        if last.map(|at| now.duration_since(at) < Duration::from_millis(30)).unwrap_or(false) {
            return
        }
        *last = Some(now);
    }
    let effect = match kind {
        HapticKind::Press => 0,
        HapticKind::Tick => 1,
    };
    jni_activity_call("haptic", "(II)V", &[jni::objects::JValue::Int(level as i32), jni::objects::JValue::Int(effect)]);
}

/// Calls a static `void` method of `GameActivity` from any thread. Failures (no activity, a pending
/// exception) are swallowed: none of what goes through here is worth stopping the game for.
fn jni_activity_call(method: &str, signature: &str, args: &[jni::objects::JValue]) {
    with_activity(|env, class| {
        env.call_static_method(class, method, signature, args)?;
        Ok(())
    });
}

// ---------------------------------------------------------------------------------------------
// Performance: Android's performance hint session (ADPF) and the frame-rate hint.

/// Opens a hint session for the calling thread, which should be the one that runs the game. With a
/// target of one frame, Android raises the CPU clock ahead of a heavy frame instead of after it,
/// and lets it drop in light ones. Does nothing where it is not supported (before Android 12).
pub fn perf_start(target_frame: Duration) {
    // SAFETY: gettid has no preconditions.
    let tid = unsafe { libc::gettid() };
    jni_activity_call(
        "perfStart",
        "(IJ)V",
        &[jni::objects::JValue::Int(tid), jni::objects::JValue::Long(target_frame.as_nanos() as i64)],
    );
}

/// A short buzz in the pad that is being played with, as the player asked in the settings. Skipped
/// when a burst of presses would turn it into a continuous hum.
fn pad_rumble() {
    let level = crate::extras::rumble_level();
    let device = crate::extras::pad_device();
    if level == 0 || device < 0 {
        return
    }
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    {
        let mut last = LAST.lock().unwrap();
        let now = Instant::now();
        if last.map(|at| now.duration_since(at) < Duration::from_millis(60)).unwrap_or(false) {
            return
        }
        *last = Some(now);
    }
    jni_activity_call("padRumble", "(II)V", &[jni::objects::JValue::Int(device), jni::objects::JValue::Int(i32::from(level))]);
}

/// Changes the frame length the hint session aims for (the battery saver asks for a longer one).
pub fn perf_set_target(target_frame: Duration) {
    jni_activity_call("perfTarget", "(J)V", &[jni::objects::JValue::Long(target_frame.as_nanos() as i64)]);
}

/// Tells the hint session how long the last frame's work took.
pub fn perf_report(work: Duration) {
    jni_activity_call("perfReport", "(J)V", &[jni::objects::JValue::Long(work.as_nanos() as i64)]);
}

/// Hands memory the allocator is holding back to the system. Loading the game builds and then frees
/// hundreds of MB of temporary buffers (decoded sprites before they go into the atlas, the parsed
/// executable) that Android's allocator would otherwise keep around for reuse that never comes.
pub fn purge_memory(why: &str) {
    extern "C" {
        fn mallopt(param: i32, value: i32) -> i32;
    }
    let before = rss_mb();
    // SAFETY: mallopt only takes integers. M_PURGE (-101) releases what scudo can; M_PURGE_ALL
    // (-104, Android 14+) also empties its caches. Either returns 0 where unsupported.
    unsafe {
        mallopt(-101, 0);
        mallopt(-104, 0);
    }
    eprintln!("[mem] {}: {} MB -> {} MB", why, before, rss_mb());
}

/// Resident memory of the process, in MB (0 if it cannot be read).
pub fn rss_mb() -> u64 {
    fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|text| text.split_whitespace().nth(1).and_then(|pages| pages.parse::<u64>().ok()))
        .map(|pages| pages * 4096 / (1024 * 1024))
        .unwrap_or(0)
}

/// Tells the system the game produces 30 frames a second. The display then runs at a rate that
/// divides evenly (60 or 120 Hz, never 90) and the compositor stops guessing, which is what makes
/// a 30 fps game on a high-refresh phone look steady. `ANativeWindow_setFrameRate` is Android 11+,
/// so it is looked up at run time; older versions just do without.
fn set_frame_rate_hint(window: *mut std::ffi::c_void, fps: f32) {
    type SetFrameRate = unsafe extern "C" fn(*mut std::ffi::c_void, f32, i8) -> i32;
    // SAFETY: dlsym with RTLD_DEFAULT only reads the symbol table; the function is called with the
    // signature the NDK documents for it.
    unsafe {
        let symbol = libc::dlsym(libc::RTLD_DEFAULT, b"ANativeWindow_setFrameRate\0".as_ptr().cast());
        if !symbol.is_null() {
            let set: SetFrameRate = std::mem::transmute(symbol);
            // 1 = ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            set(window, fps, 1);
        }
    }
}

/// A NativeActivity receives key events through the native queue, so the system never sees the
/// volume keys and the volume can't be changed. Adjust the music stream ourselves instead.
fn jni_adjust_volume(up: bool) {
    let ctx = ndk_context::android_context();
    let vm = match unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) } {
        Ok(vm) => vm,
        Err(_) => return,
    };
    let mut env = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };
    let _ = (|| -> jni::errors::Result<()> {
        let activity = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
        let name = env.new_string("audio")?;
        let manager = env
            .call_method(
                &activity,
                "getSystemService",
                "(Ljava/lang/String;)Ljava/lang/Object;",
                &[jni::objects::JValue::Object(&name)],
            )?
            .l()?;
        if manager.is_null() {
            return Ok(())
        }
        // STREAM_MUSIC = 3, ADJUST_RAISE = 1 / ADJUST_LOWER = -1, FLAG_SHOW_UI = 1
        let direction = if up { 1 } else { -1 };
        env.call_method(
            &manager,
            "adjustStreamVolume",
            "(III)V",
            &[
                jni::objects::JValue::Int(3),
                jni::objects::JValue::Int(direction),
                jni::objects::JValue::Int(1),
            ],
        )?;
        Ok(())
    })();
}

// ---------------------------------------------------------------------------------------------
// System (soft) keyboard.
//
// The game has no Android view of its own to type into, so the activity's `InputConnection` sends
// what is typed here through the JNI exports below. `ime_pump` then feeds it to ramen the same way
// a hardware keyboard would, right before each poll, so the game's dialog sees a normal key tap.

/// What the system keyboard committed, waiting for the next poll.
enum ImeEvent {
    Text(String),
    Backspace,
    Enter,
}

fn ime_queue() -> &'static Mutex<std::collections::VecDeque<ImeEvent>> {
    static QUEUE: std::sync::OnceLock<Mutex<std::collections::VecDeque<ImeEvent>>> = std::sync::OnceLock::new();
    QUEUE.get_or_init(|| Mutex::new(std::collections::VecDeque::new()))
}

/// Feeds what the system keyboard typed into ramen's queue. Each character goes in as a quick key
/// tap that carries its text (`KeyDown{text}` + `KeyUp`): the press sets `keyboard_lastkey` and
/// `vk_anykey`, the text fills `keyboard_string`, and the deferred release keeps the key down for
/// the poll it arrived in - exactly what `tap_key` does for the on-screen buttons. Backspace and
/// enter are plain taps of the keys the game already reads for those.
pub fn ime_pump() {
    let events: Vec<ImeEvent> = match ime_queue().lock() {
        Ok(mut queue) => queue.drain(..).collect(),
        Err(_) => return,
    };
    for event in events {
        match event {
            ImeEvent::Text(text) => {
                for c in text.chars() {
                    let key = ime_char_to_key(c);
                    android::push_input(ramen::platform::android::InputEvent::KeyDown { key, text: Some(c) });
                    android::push_input(ramen::platform::android::InputEvent::KeyUp { key });
                }
            },
            ImeEvent::Backspace => tap_key(Key::Backspace),
            ImeEvent::Enter => tap_key(Key::Return),
        }
    }
}

/// The key a committed character is reported as. It only decides `keyboard_lastkey`; the character
/// itself travels in the event's text. Letters and digits become the same keys a hardware keyboard
/// would send; anything without a Game Maker key (accents, most signs) falls back to space, whose
/// code no dialog key uses.
fn ime_char_to_key(c: char) -> Key {
    if c.is_ascii_alphabetic() {
        return LETTERS[usize::from(c.to_ascii_uppercase() as u8 - b'A')]
    }
    if c.is_ascii_digit() {
        const DIGITS: [Key; 10] = [
            Key::Alpha0, Key::Alpha1, Key::Alpha2, Key::Alpha3, Key::Alpha4, Key::Alpha5, Key::Alpha6,
            Key::Alpha7, Key::Alpha8, Key::Alpha9,
        ];
        return DIGITS[usize::from(c as u8 - b'0')]
    }
    match c {
        ' ' => Key::Space,
        '.' => Key::Period,
        ',' => Key::Comma,
        '-' => Key::Minus,
        '+' => Key::Plus,
        '=' => Key::Equals,
        '/' => Key::Slash,
        '\\' => Key::Backslash,
        ';' => Key::Semicolon,
        ':' => Key::Colon,
        '\'' => Key::Apostrophe,
        '"' => Key::Quote,
        '?' => Key::QuestionMark,
        '_' => Key::Underscore,
        _ => Key::Space,
    }
}

/// Called from the activity's `InputConnection` for every committed string.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeCommitText<'local>(
    mut env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
    text: jni::objects::JString<'local>,
) {
    let value: String = env.get_string(&text).map(Into::into).unwrap_or_default();
    if value.is_empty() {
        return
    }
    if let Ok(mut queue) = ime_queue().lock() {
        queue.push_back(ImeEvent::Text(value));
    }
}

/// Called from the activity's `InputConnection` for the delete key.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeBackspace<'local>(
    _env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
) {
    if let Ok(mut queue) = ime_queue().lock() {
        queue.push_back(ImeEvent::Backspace);
    }
}

/// Called from the activity's `InputConnection` for the enter/done key.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeEnter<'local>(
    _env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
) {
    if let Ok(mut queue) = ime_queue().lock() {
        queue.push_back(ImeEvent::Enter);
    }
}

/// Called from the activity when the phone's temperature or the system battery saver changes.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeDeviceState<'local>(
    _env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
    thermal: jni::sys::jint,
    power_save: jni::sys::jboolean,
) {
    let was_blocking = crate::extras::thermal_blocks_ffwd();
    crate::extras::set_device_state(thermal, power_save != 0);
    if crate::extras::thermal_blocks_ffwd() && !was_blocking && crate::extras::ffwd_on() {
        // Too hot to keep running faster than normal: the game drops back on its own, and says so.
        crate::extras::toggle_ffwd();
        toast("Avance rapido desactivado: el movil esta muy caliente");
    }
}

/// Called from the activity with the space the camera cutout and system bars take, in pixels.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeInsets<'local>(
    _env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
    left: jni::sys::jint,
    top: jni::sys::jint,
    right: jni::sys::jint,
    bottom: jni::sys::jint,
) {
    crate::extras::set_insets(left.max(0) as u32, top.max(0) as u32, right.max(0) as u32, bottom.max(0) as u32);
}

/// Called from the activity whenever the system keyboard's height changes, so the renderer can keep
/// the game above it.
#[no_mangle]
pub extern "system" fn Java_com_klisee_reloaded_GameActivity_nativeImeInset<'local>(
    _env: jni::JNIEnv<'local>,
    _this: jni::objects::JObject<'local>,
    height: jni::sys::jint,
) {
    // A keyboard taller than the window is not a keyboard: a value like that would push the game off
    // the top and hide the controls behind nothing, so it is dropped rather than believed.
    let (_, window_height) = android::window_size();
    let height = height.max(0) as u32;
    let height = if window_height > 0 && height >= window_height { 0 } else { height };
    crate::touch_controls::set_ime_inset(height);
    // The keyboard can be dropped by the system as well as by us - its own close button, or the back
    // gesture - so whether it is up is taken from its height. Without this, the next touch on the
    // game's text would be read as "already up" and would not raise it again.
    IME_SHOWN.store(height > 0, std::sync::atomic::Ordering::Relaxed);
}

/// Whether the system keyboard is up, so it is only raised or dropped on a change.
static IME_SHOWN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Raises or drops the system keyboard, but only when that is a change.
fn ime_set(visible: bool) {
    if IME_SHOWN.swap(visible, std::sync::atomic::Ordering::Relaxed) != visible {
        jni_set_ime(visible);
    }
}

/// Raises or drops the system keyboard by calling the activity's static `GameActivity.setImeVisible`.
/// The activity shows it from the UI thread, which Kotlin takes care of; here we only need to reach
/// the class. `FindClass` would look on the system class path (the context the activity publishes is
/// the Application), so the class is loaded through its own loader instead.
fn jni_set_ime(visible: bool) {
    with_activity(|env, class| {
        env.call_static_method(class, "setImeVisible", "(Z)V", &[jni::objects::JValue::Bool(visible as u8)])?;
        Ok(())
    });
}

/// Asks the activity to read the keyboard's height again.
///
/// The height normally arrives on its own, from the inset callback. This is the belt to that braces:
/// while the engine believes the keyboard is up it asks again once in a while, so an inset callback
/// that never came cannot leave the game pushed up top with the controls hidden.
fn jni_refresh_ime_height() {
    with_activity(|env, class| {
        env.call_static_method(class, "refreshImeHeight", "()V", &[])?;
        Ok(())
    });
}

/// The activity's class, held for the life of the process.
///
/// It has to be looked up through the activity's own class loader: `FindClass` would look on the
/// system class path, because the context the activity publishes for JNI is the Application. Holding
/// it globally means the lookup happens once instead of on every call, and that the watchdog below
/// does not leave a reference behind each time it runs.
static ACTIVITY_CLASS: std::sync::OnceLock<jni::objects::GlobalRef> = std::sync::OnceLock::new();

/// Attaches this thread and runs `f` with the activity's class.
fn with_activity(f: impl FnOnce(&mut jni::JNIEnv, &jni::objects::JClass) -> jni::errors::Result<()>) {
    let ctx = ndk_context::android_context();
    let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
    // Permanent: the thread stays attached, and attaching on every call would only add work.
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
    if ACTIVITY_CLASS.get().is_none() {
        let loaded = (|| -> jni::errors::Result<jni::objects::GlobalRef> {
            let context = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
            let loader = env.call_method(&context, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])?.l()?;
            let name = env.new_string("com.klisee.reloaded.GameActivity")?;
            let class = env
                .call_method(
                    &loader,
                    "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;",
                    &[jni::objects::JValue::Object(&name)],
                )?
                .l()?;
            env.new_global_ref(class)
        })();
        match loaded {
            Ok(global) => {
                let _ = ACTIVITY_CLASS.set(global);
            },
            Err(_) => return,
        }
    }
    let Some(global) = ACTIVITY_CLASS.get() else { return };
    let class = unsafe { jni::objects::JClass::from_raw(global.as_obj().as_raw()) };
    if f(&mut env, &class).is_err() {
        let _ = env.exception_clear();
    }
}

fn publish_window(app: &AndroidApp) {
    if let Some(window) = app.native_window() {
        let ptr = window.ptr().as_ptr() as *mut std::ffi::c_void;
        // The `NativeWindow` wrapper above owns a reference and drops it when this function
        // returns; without acquiring our own, the window can be freed before the game thread
        // calls eglCreateWindowSurface on it, which crashes with a null function pointer.
        // SAFETY: `ptr` is a valid ANativeWindow handed out by the activity, and this only
        // bumps its reference count.
        unsafe { ANativeWindow_acquire(ptr) };
        let width = window.width() as u32;
        let height = window.height() as u32;
        android::set_native_window(ptr, width, height);
        set_frame_rate_hint(ptr, 30.0);
    }
}

extern "C" {
    /// Bumps the reference count of an `ANativeWindow`. From `libandroid`, always linked.
    fn ANativeWindow_acquire(window: *mut std::ffi::c_void) -> i32;
}

/// Copies everything the engine prints to logcat.
///
/// An Android app's stdout and stderr go nowhere, so every diagnostic the emulator writes with
/// `println!`/`eprintln!` - the `Runtime error: ... (room N)` line that says why the runner stopped
/// above all - is thrown away, and a report of "the game threw me back to the app" arrives with no
/// reason in it. With the process's own stdout and stderr pointing at a pipe, all of it lands in
/// logcat, which is also what the launcher's log screen reads.
fn pipe_std_to_logcat() {
    let mut fds = [0 as libc::c_int; 2];
    // SAFETY: `fds` is a valid array of two ints, which is what pipe() fills in.
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        return
    }
    // SAFETY: both ends are valid fds. Duplicating the write end onto stdout and stderr is what puts
    // the engine's prints in the pipe, and the spare write end is not needed after that.
    unsafe {
        libc::dup2(fds[1], libc::STDOUT_FILENO);
        libc::dup2(fds[1], libc::STDERR_FILENO);
        libc::close(fds[1]);
    }
    let read_fd = fds[0];
    std::thread::spawn(move || {
        use std::io::Read;
        use std::os::unix::io::FromRawFd;
        // SAFETY: read_fd is the pipe's read end and nothing else owns it.
        let mut pipe = unsafe { std::fs::File::from_raw_fd(read_fd) };
        let mut chunk = [0u8; 2048];
        let mut pending: Vec<u8> = Vec::new();
        let mut last = String::new();
        let mut repeats = 0u32;
        loop {
            let Ok(read) = pipe.read(&mut chunk) else { break };
            if read == 0 {
                break
            }
            pending.extend_from_slice(&chunk[..read]);
            while let Some(end) = pending.iter().position(|byte| *byte == b'\n') {
                let line: Vec<u8> = pending.drain(..=end).collect();
                let text = String::from_utf8_lossy(&line).trim_end().to_string();
                if text.is_empty() {
                    continue
                }
                // A message repeated every frame - an unimplemented function called from a draw
                // event, say - would flood the log and slow the game down with it, so runs of the
                // same line are counted instead of copied.
                if text == last {
                    repeats += 1;
                    continue
                }
                if repeats > 0 {
                    android_log_line(&format!("  (repetido {} veces)", repeats));
                    repeats = 0;
                }
                last = text.clone();
                android_log_line(&text);
            }
        }
    });
}

/// One line to logcat, under the tag the launcher's log screen shows.
fn android_log_line(text: &str) {
    const ANDROID_LOG_INFO: i32 = 4;
    let text = format!("{}\0", text);
    // SAFETY: both arguments are NUL-terminated C strings that outlive the call.
    unsafe {
        __android_log_write(
            ANDROID_LOG_INFO,
            b"gm8emulator\0".as_ptr() as *const libc::c_char,
            text.as_ptr() as *const libc::c_char,
        );
    }
}

extern "C" {
    /// From `liblog`, which this library already depends on.
    fn __android_log_write(priority: i32, tag: *const libc::c_char, text: *const libc::c_char) -> i32;
}

/// Runs the emulator. `poll_events` must be driven from the thread `android_main` is called
/// on, because android-activity asserts it, so the pump cannot live on a worker thread.
/// The emulator itself runs on a worker thread instead.
pub fn android_entry(app: AndroidApp) {
    // First thing, so the opening lines of the session are captured as well.
    pipe_std_to_logcat();
    // Panics then land in the same log, with a backtrace.
    std::env::set_var("RUST_BACKTRACE", "1");

    // The pad layout and the video mode are read once, before any input can arrive.
    load_controls();
    load_video();

    publish_window(&app);

    let app = std::sync::Arc::new(app);
    let game_app = app.clone();

    // Set when the emulator returns, i.e. the game ended from its own "Salir del juego" (or failed).
    // Returning from `android_main` makes android-activity finish the Activity, so the launcher
    // comes back instead of leaving a dead black window on screen.
    let game_over = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let game_over_thread = game_over.clone();

    std::thread::spawn(move || {
        // The activity asks for landscape in the manifest, so the window is normally already the right
        // way round; the rotate still takes a moment on some phones. The engine lays its screens out
        // from the size of the window it is given, and whatever it gets here is what the whole session
        // keeps, so wait for the landscape window instead of racing it. Bounded, so a phone that never
        // reports one cannot hang the launch.
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(2000);
        while std::time::Instant::now() < deadline {
            let (w, h) = android::window_size();
            if w > h {
                break
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        let game_exe = match ensure_game_extracted() {
            Ok(exe) => exe,
            Err(e) => {
                logf(&format!("no se pudo preparar el juego: {}", e));
                game_over_thread.store(true, std::sync::atomic::Ordering::SeqCst);
                return
            },
        };

        crate::render::opengl::egl::log_stubbed_once();
        provision_soundfont(game_exe.parent().unwrap_or_else(|| std::path::Path::new("/")));
        logf(&format!("arrancando {}", game_exe.display()));
        // The emulator returning is what finishes the Activity, so how it returned - its exit code,
        // or a panic - is the answer to "why did the game kick me back to the launcher?".
        let outcome = std::panic::catch_unwind(|| {
            crate::run_game(vec!["gm8emulator".to_string(), game_exe.to_string_lossy().into_owned()])
        });
        match outcome {
            Ok(code) => logf(&format!("el emulador termino (codigo {}); se cierra la actividad", code)),
            Err(_) => logf("el emulador se cayo (panic); se cierra la actividad"),
        }
        game_over_thread.store(true, std::sync::atomic::Ordering::SeqCst);
    });

    let mut destroying = false;
    let mut ime_check = Instant::now();
    let mut text_entry_was_open = false;
    loop {
        if game_over.load(std::sync::atomic::Ordering::SeqCst) || destroying {
            break
        }
        // When the game moves to another state, drop the keys of controls that are no longer shown
        // so none of them stays stuck down.
        for index in crate::touch_controls::stale_pressed() {
            control_key_up(index);
        }
        flush_delayed_mouse();
        // The keyboard follows the game's text dialog: it comes up by itself the moment the game asks
        // for a name, and goes away when the dialog closes. Only the change is acted on, so closing
        // it by hand while the dialog is open is respected instead of being undone on the next tick;
        // touching the text brings it back (see `pointer_down`). The engine reports the dialog from
        // inside its own wait loop, so the game thread keeps the flag current even though the frame
        // loop is not coming round.
        let text_entry = crate::touch_controls::text_entry_open();
        if text_entry != text_entry_was_open {
            text_entry_was_open = text_entry;
            ime_set(text_entry);
        }
        // Belt and braces: while the keyboard is believed to be up, ask the activity for its height
        // again about once a second. A callback that never arrived would otherwise leave the game
        // pushed up top and the controls hidden, with no way for the player to get them back.
        if crate::touch_controls::ime_inset() > 0 && ime_check.elapsed() >= Duration::from_secs(1) {
            ime_check = Instant::now();
            jni_refresh_ime_height();
        }
        game_app.poll_events(Some(Duration::from_millis(8)), |event| match event {
            PollEvent::Main(main_event) => match main_event {
                // Init/Resume/Start all mean the surface is (re)available, which is also what
                // happens when the user comes back from the home screen.
                android_activity::MainEvent::InitWindow { .. }
                | android_activity::MainEvent::WindowResized { .. }
                | android_activity::MainEvent::RedrawNeeded { .. }
                | android_activity::MainEvent::Resume { .. }
                | android_activity::MainEvent::GainedFocus
                | android_activity::MainEvent::Start => {
                    ramen::platform::android::set_paused(false);
                    crate::game::audio::set_output_paused(false);
                    publish_window(&game_app);
                    if let Some(window) = game_app.native_window() {
                        // The activity has given us a window: build the GL surface for it now, while
                        // it is alive. Building it later, from the render loop, is what left the
                        // screen black and made EGL complain about a window already connected.
                        unsafe {
                            let ptr = window.ptr().as_ptr() as *mut std::ffi::c_void;
                            crate::render::opengl::egl::attach_surface(ptr);
                        }
                        android::push_resize(window.width() as u16, window.height() as u16);
                    }
                },
                // Going away. The surface is let go on `Pause`/`Stop` as well as on
                // `TerminateWindow`, because Android does not always send the latter - and a dead
                // surface left lying around is worse than none: the renderer believes it can draw,
                // and OpenGL answers with an error that kills the thread.
                android_activity::MainEvent::TerminateWindow { .. }
                | android_activity::MainEvent::Pause { .. }
                | android_activity::MainEvent::Stop => {
                    ramen::platform::android::set_paused(true);
                    crate::game::audio::set_output_paused(true);
                    unsafe { crate::render::opengl::egl::detach_surface() };
                    // Out of sight: hand back what the allocator is holding, so the system has less
                    // reason to kill a game that is sitting in the background.
                    purge_memory("segundo plano");
                },
                android_activity::MainEvent::InputAvailable => {
                    if let Ok(mut iter) = game_app.input_events_iter() {
                        loop {
                            let handled = iter.next(|event| {
                                translate(&event);
                                android_activity::InputStatus::Handled
                            });
                            if !handled {
                                break
                            }
                        }
                    }
                },
                // The system is tearing the activity down (the app was closed): stop pumping so
                // `android_main` can return and the activity really goes away.
                android_activity::MainEvent::Destroy => destroying = true,
                _ => {},
            },
            PollEvent::Wake => {},
            PollEvent::Timeout => {},
            _ => {},
        });
    }
    logf(&format!(
        "cerrando la actividad (juego terminado: {}, destruyendo: {})",
        game_over.load(std::sync::atomic::Ordering::SeqCst),
        destroying
    ));
}

/// android-activity provides `ANativeActivity_onCreate`; this is the plain-Rust-ABI symbol
/// it looks for and calls on a dedicated thread.
#[no_mangle]
fn android_main(app: AndroidApp) {
    android_entry(app);
}

// ---------------------------------------------------------------------------------------
// Asset provisioning
//
// The game ships as a .zip laid out exactly like the game folder it comes from, so
// extracting it reproduces the layout the engine already expects on Linux. It is found
// in a list of conventional places, extracted once into the app's own storage, and then
// the game runs straight off the extracted files.

use std::{
    fs,
    path::{Path, PathBuf},
};

/// Where the app looks for `game.zip`, most specific first. The first two are the app's own
/// storage and need no permissions; the last one is shared storage and may need one.
const ZIP_NAME: &str = "game.zip";
pub(crate) const PACKAGE_DIR: &str = "com.klisee.reloaded";

/// Free bytes on the filesystem containing `path`, or `None` if it can't be queried.
fn free_bytes_on(path: &Path) -> Option<u64> {
    // SAFETY: zeroed statvfs is a valid argument, and the call only reads.
    unsafe {
        let path_c = std::ffi::CString::new(path.as_os_str().as_encoded_bytes()).ok()?;
        let mut stats: libc::statvfs = std::mem::zeroed();
        if libc::statvfs(path_c.as_ptr(), &mut stats) != 0 {
            return None
        }
        Some(stats.f_bavail * u64::from(stats.f_frsize))
    }
}

/// Enough room for the zip plus its extracted contents.
fn enough_space(zip_len: u64, target: &Path) -> bool {
    match free_bytes_on(target.parent().unwrap_or(target)) {
        Some(free) => free > zip_len * 2,
        None => true, // can't tell, so let the extraction try and fail loudly if it must
    }
}

fn zip_candidates() -> Vec<PathBuf> {
    let mut dirs = vec![
        PathBuf::from(format!("/sdcard/Android/data/{}/files", PACKAGE_DIR)),
        PathBuf::from(format!("/data/data/{}/files", PACKAGE_DIR)),
        PathBuf::from("/sdcard/Download".to_string()),
        PathBuf::from("/sdcard".to_string()),
        // Writable by `adb push`, which is how the game is usually sideloaded for testing.
        PathBuf::from("/data/local/tmp".to_string()),
    ];
    dirs.retain(|d| d.is_dir());
    dirs.into_iter().map(|d| d.join(ZIP_NAME)).filter(|p| p.is_file()).collect()
}

/// The `.exe` inside the extracted game directory. Its name is not pinned, so a different
/// version of the game, or another GameMaker 8 fangame, works unchanged.
fn find_exe(dir: &Path) -> Option<PathBuf> {
    let mut found = fs::read_dir(dir)
        .ok()?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("exe"))
        })
        .collect::<Vec<_>>();
    found.sort();
    found.into_iter().next()
}

fn logf(msg: &str) {
    // `eprintln` goes to logcat under NativeActivity, which is where this is read from.
    eprintln!("[assets] {}", msg);
    // Keep a small file too, so the launcher can show a log without needing adb/logcat.
    append_log_file(msg);
}

/// Appends one line to `<files>/emulator.log`, rotating once past [LOG_MAX_BYTES].
fn append_log_file(msg: &str) {
    const LOG_MAX_BYTES: u64 = 256 * 1024;
    let path = PathBuf::from(format!("/data/data/{}/files/emulator.log", PACKAGE_DIR));
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let _ = (|| -> std::io::Result<()> {
        use std::io::Write;
        if fs::metadata(&path).map(|m| m.len()).unwrap_or(0) > LOG_MAX_BYTES {
            let _ = fs::rename(&path, path.with_extension("log.1"));
        }
        // O_APPEND means concurrent writers interleave whole lines rather than corrupting them.
        let mut file = fs::OpenOptions::new().create(true).append(true).open(&path)?;
        writeln!(file, "{}", msg)
    })();
}

/// MIDI music needs a General MIDI SoundFont, and Android ships none at the paths the engine looks
/// at. If one was placed next to the game (or pushed to the app's storage), point the engine at it.
fn provision_soundfont(game_dir: &Path) {
    let candidates = [
        game_dir.join("soundfont.sf2"),
        game_dir.join("TimGM6mb.sf2"),
        PathBuf::from(format!("/sdcard/Android/data/{}/files/soundfont.sf2", PACKAGE_DIR)),
        PathBuf::from("/sdcard/Download/soundfont.sf2"),
    ];
    for path in candidates {
        if path.is_file() {
            logf(&format!("soundfont MIDI: {}", path.display()));
            std::env::set_var("OPENGMK_SOUNDFONT", &path);
            return
        }
    }
    logf("aviso: no hay .sf2; la musica MIDI no sonara (copia soundfont.sf2 a la carpeta del juego)");
}

/// Makes the game available on disk and returns the path of its executable.
fn ensure_game_extracted() -> Result<PathBuf, String> {
    let storage = PathBuf::from(format!("/data/data/{}/files", PACKAGE_DIR));
    let game_dir = storage.join("game");
    fs::create_dir_all(&game_dir).map_err(|e| format!("no se pudo crear {}: {}", game_dir.display(), e))?;

    // Already provisioned? Both the exe and the data folder have to be there.
    if let Some(exe) = find_exe(&game_dir) {
        if game_dir.join("data").is_dir() {
            logf(&format!("juego ya extraido en {}", game_dir.display()));
            return Ok(exe)
        }
    }

    let zips = zip_candidates();
    if zips.is_empty() {
        return Err(format!(
            "no encontre {}. Copialo a /sdcard/Android/data/{}/files/",
            ZIP_NAME, PACKAGE_DIR
        ))
    }
    let zip_path = &zips[0];
    let zip_len = fs::metadata(zip_path).map_err(|e| format!("{}: {}", zip_path.display(), e))?.len();
    logf(&format!("encontrado {} ({} bytes)", zip_path.display(), zip_len));

    if !enough_space(zip_len, &game_dir) {
        return Err("espacio insuficiente: hacen falta unas dos veces el tamano del zip".into())
    }

    // Extract into a staging dir and swap it in, so a failed run leaves the previous
    // contents intact rather than a half-extracted game.
    let staging = storage.join("game.staging");
    let _ = fs::remove_dir_all(&staging);
    fs::create_dir_all(&staging).map_err(|e| format!("no se pudo crear {}: {}", staging.display(), e))?;

    {
        let file = fs::File::open(zip_path).map_err(|e| format!("{}: {}", zip_path.display(), e))?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| format!("no pude leer el zip {}: {}", zip_path.display(), e))?;
        logf(&format!("extrayendo {} entradas", archive.len()));
        archive
            .extract(&staging)
            .map_err(|e| format!("fallo la extraccion de {}: {}", zip_path.display(), e))?;
    }

    let exe = find_exe(&staging).ok_or_else(|| "el zip no contiene ningun .exe en su raiz".to_string())?;
    let _ = fs::remove_dir_all(&game_dir);
    fs::rename(&staging, &game_dir)
        .map_err(|e| format!("no pude mover lo extraido a {}: {}", game_dir.display(), e))?;

    let exe = game_dir.join(exe.file_name().unwrap());
    logf(&format!("listo: {}", exe.display()));
    Ok(exe)
}
