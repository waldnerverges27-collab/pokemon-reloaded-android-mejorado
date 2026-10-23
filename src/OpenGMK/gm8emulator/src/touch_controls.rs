//! On-screen touch controls for the Android port.
//!
//! The game only understands keyboard input, so each on-screen control injects the key the game
//! expects. The key set is the one the game binds by default:
//!
//! - arrows: move
//! - `Z` confirm/act, `X` cancel, `C`/`V` extra actions (fishing/bike, attacks 3/4)
//! - `1`..`6`: pick a party Pokémon in battle (and act as the HM shortcuts / berries)
//! - `B` pokéball, `S` switch, `M` mega, `E` run, `A` walk/assign, `N` use item
//! - `ENTER` menus, `ESC` quit
//!
//! Movement is an analog joystick (bottom-left); the rest are buttons. Positions are normalised to
//! the window so the input hit-test and the renderer share the exact same layout.
//!
//! Controls are grouped by the game's current state (see [current_context]): the title/menu rooms
//! only need the basics, the overworld needs the joystick and interaction keys, and battle needs
//! the combat extras. The emulator reports the current room every frame.

use ramen::input::Key;
use std::{
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU16, AtomicU64, AtomicUsize, Ordering},
        Mutex,
    },
    time::{Duration, Instant},
};

/// How long a state the engine reported keeps being believed after it stops being reported.
///
/// A long conversation is drawn as several boxes one after another, and between two of them the game
/// clears its modal for a frame or two. Reading that literally put the overlay back in the middle of
/// the conversation: the buttons blink in, and they are useless there anyway, because the player
/// cannot move while a dialogue is up.
const GRACE: Duration = Duration::from_millis(600);

/// A yes/no the engine reports every frame, that only drops once the game has stopped saying yes for
/// [GRACE].
struct Sticky {
    flag: AtomicBool,
    seen: Mutex<Option<Instant>>,
}

impl Sticky {
    const fn new() -> Self {
        Self { flag: AtomicBool::new(false), seen: Mutex::new(None) }
    }

    /// Notes this frame's reading. True when the answer actually changed.
    fn report(&self, on: bool) -> bool {
        if on {
            *self.seen.lock().unwrap() = Some(Instant::now());
            return !self.flag.swap(true, Ordering::Relaxed)
        }
        let recent = self.seen.lock().unwrap().map(|at| at.elapsed()).is_some_and(|ago| ago < GRACE);
        if recent {
            return false
        }
        self.flag.swap(false, Ordering::Relaxed)
    }

    fn get(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
}

/// Which game states a control is shown in.
pub const MENU: u16 = 1;
pub const WORLD: u16 = 2;
pub const BATTLE: u16 = 4;
/// A dialogue box is open: only the button that advances it is shown, so nothing covers the text.
pub const DIALOG: u16 = 8;
/// A new game asking you to pick your character. That screen is moved with the left and right keys,
/// so it gets a pair of arrows and nothing else.
pub const CHARACTER: u16 = 16;
/// A modal asking you to pick from a list of options - the preferences the new game asks about right
/// after the character, drawn over that same screen. The list is walked with the movement keys and
/// answered with Z and X, so it gets the joystick and those two buttons, and nothing else.
pub const CHOICE: u16 = 32;
/// The Safari Zone's catching room: everything the map shows, minus the movement stick and the
/// attack diamond - none of your own Pokémon fights there - plus the ball, which is the key that
/// room answers to for throwing. The flee (E), the bag and the rest stay where the map has them.
pub const SAFARI: u16 = 64;
/// One of the game's option lists (the start menu, the bag's action list, options...). They are
/// walked with the movement keys and the entries are tapped directly, so only the joystick is left.
pub const MENU_NAV: u16 = 128;
/// One of the game's full-screen menus (the bag, the map, the team, the trainer card...) or one of its
/// shops. They are walked with the movement keys and answered with Z and X, so those and the stick are
/// all they get; everything the overworld has (the number keys, the bag and map shortcuts, the
/// diamond's C and V...) would only cover the menu, and its keys do nothing there.
pub const SCREEN: u16 = 256;
/// Like [SCREEN], for the menus whose list or cursor has a third key of its own: C sorts the bag, the
/// team and the Pokédex, and moves the map's cursor.
pub const SCREEN_C: u16 = 512;

#[derive(Clone, Copy, PartialEq)]
pub enum Shape {
    Circle { cx: f32, cy: f32, r: f32 },
    Rect { cx: f32, cy: f32, w: f32, h: f32 },
}

#[derive(Clone, Copy, PartialEq)]
pub enum Dir {
    Up,
    Down,
    Left,
    Right,
}

#[derive(Clone, Copy, PartialEq)]
pub enum Label {
    None,
    Arrow(Dir),
    Text(&'static str),
}

pub struct Control {
    /// Stable name, used as the key when the layout is saved and loaded. Reordering the array of
    /// controls must not move a player's customised button, so the name is what identifies it.
    pub name: &'static str,
    pub key: Key,
    pub shape: Shape,
    pub label: Label,
    /// Bitmask of [MENU] / [WORLD] / [BATTLE] states this control belongs to.
    pub contexts: u16,
}

/// The joystick is not a button but is customised (and saved) the same way.
pub const JOYSTICK_NAME: &str = "joystick";

pub const CONTROLS: &[Control] = &[
    // Number keys (first row along the top). They are for the base game only: in battle those keys
    // pick a party Pokémon, and that is what the SEL wheel below is for, so showing them there would
    // only get in the way.
    Control { name: "alpha1", key: Key::Alpha1, shape: Shape::Circle { cx: 0.300, cy: 0.055, r: 0.020 }, label: Label::Text("1"), contexts: WORLD | SAFARI },
    Control { name: "alpha2", key: Key::Alpha2, shape: Shape::Circle { cx: 0.372, cy: 0.055, r: 0.020 }, label: Label::Text("2"), contexts: WORLD | SAFARI },
    Control { name: "alpha3", key: Key::Alpha3, shape: Shape::Circle { cx: 0.444, cy: 0.055, r: 0.020 }, label: Label::Text("3"), contexts: WORLD | SAFARI },
    Control { name: "alpha5", key: Key::Alpha5, shape: Shape::Circle { cx: 0.516, cy: 0.055, r: 0.020 }, label: Label::Text("5"), contexts: WORLD | SAFARI },
    Control { name: "alpha7", key: Key::Alpha7, shape: Shape::Circle { cx: 0.588, cy: 0.055, r: 0.020 }, label: Label::Text("7"), contexts: WORLD | SAFARI },
    // Menu / misc keys (second row along the top).
    Control { name: "space", key: Key::Space, shape: Shape::Circle { cx: 0.300, cy: 0.145, r: 0.022 }, label: Label::Text("PKM"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "o", key: Key::O, shape: Shape::Circle { cx: 0.372, cy: 0.145, r: 0.022 }, label: Label::Text("O"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "m", key: Key::M, shape: Shape::Circle { cx: 0.444, cy: 0.145, r: 0.022 }, label: Label::Text("M"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "e", key: Key::E, shape: Shape::Circle { cx: 0.516, cy: 0.145, r: 0.022 }, label: Label::Text("E"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "t", key: Key::T, shape: Shape::Circle { cx: 0.588, cy: 0.145, r: 0.022 }, label: Label::Text("T"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "l", key: Key::L, shape: Shape::Circle { cx: 0.660, cy: 0.145, r: 0.022 }, label: Label::Text("L"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "p", key: Key::P, shape: Shape::Circle { cx: 0.732, cy: 0.145, r: 0.022 }, label: Label::Text("P"), contexts: WORLD | BATTLE | SAFARI },
    // Main actions, in the diamond a gamepad's face buttons use: the main one at the bottom, cancel
    // to the right, and the other two on the left and on top. The cluster is about the size of the
    // movement joystick and sits at the same height, so the two thumbs fall in the same place.
    Control { name: "z", key: Key::Z, shape: Shape::Circle { cx: 0.865, cy: 0.851, r: 0.033 }, label: Label::Text("Z"), contexts: WORLD | BATTLE | CHOICE | SCREEN | SCREEN_C },
    Control { name: "x", key: Key::X, shape: Shape::Circle { cx: 0.933, cy: 0.700, r: 0.033 }, label: Label::Text("X"), contexts: WORLD | BATTLE | CHOICE | MENU_NAV | SCREEN | SCREEN_C },
    Control { name: "c", key: Key::C, shape: Shape::Circle { cx: 0.797, cy: 0.700, r: 0.033 }, label: Label::Text("C"), contexts: WORLD | BATTLE | SCREEN_C },
    Control { name: "v", key: Key::V, shape: Shape::Circle { cx: 0.865, cy: 0.549, r: 0.033 }, label: Label::Text("V"), contexts: WORLD | BATTLE },
    // Secondary.
    Control { name: "b", key: Key::B, shape: Shape::Circle { cx: 0.712, cy: 0.840, r: 0.026 }, label: Label::Text("B"), contexts: BATTLE | SAFARI },
    Control { name: "s", key: Key::S, shape: Shape::Circle { cx: 0.712, cy: 0.700, r: 0.026 }, label: Label::Text("S"), contexts: BATTLE },
    // Holding this opens the party wheel (see below): each slot is one Pokémon of the team.
    Control { name: PARTY_NAME, key: Key::Alpha1, shape: Shape::Circle { cx: 0.300, cy: 0.760, r: 0.038 }, label: Label::Text("SEL"), contexts: BATTLE },
    Control { name: "a", key: Key::A, shape: Shape::Circle { cx: 0.640, cy: 0.900, r: 0.024 }, label: Label::Text("A"), contexts: WORLD | SAFARI },
    Control { name: "n", key: Key::N, shape: Shape::Circle { cx: 0.640, cy: 0.760, r: 0.024 }, label: Label::Text("N"), contexts: WORLD | SAFARI },
    // Menu keys.
    Control { name: "return", key: Key::Return, shape: Shape::Circle { cx: 0.500, cy: 0.945, r: 0.032 }, label: Label::Text("OK"), contexts: WORLD | BATTLE | SAFARI },
    Control { name: "escape", key: Key::Escape, shape: Shape::Circle { cx: 0.400, cy: 0.905, r: 0.026 }, label: Label::Text("ESC"), contexts: WORLD | BATTLE | SAFARI },
    // While a dialogue box is open the overlay leaves only this one button, so the text and the
    // portrait are not buried under anything else. Its position is measured from the game image (not
    // the window), so it sits on the box whatever the screen's shape is. It sends the key the game
    // advances dialogues with; a dialogue is only ever gone through forwards, so there is no cancel.
    Control { name: "dialogo", key: Key::Z, shape: Shape::Circle { cx: 0.940, cy: 0.880, r: 0.024 }, label: Label::Text("Z"), contexts: DIALOG },
    // The character picker of a new game: an arrow either side of the character, which is where the
    // eye already is. They are the only controls on that screen.
    Control { name: LEFT_ARROW_NAME, key: Key::LeftArrow, shape: Shape::Circle { cx: LEFT_ARROW_CX, cy: ARROW_CY, r: ARROW_R }, label: Label::Arrow(Dir::Left), contexts: CHARACTER },
    Control { name: RIGHT_ARROW_NAME, key: Key::RightArrow, shape: Shape::Circle { cx: RIGHT_ARROW_CX, cy: ARROW_CY, r: ARROW_R }, label: Label::Arrow(Dir::Right), contexts: CHARACTER },
    // The picker's own buttons, down the right edge: the screen is confirmed with enter and left
    // with the key its own text names ("Pulsa X para cancelar"). Both sit above the dialogue box,
    // clear of it, with the X set a little further up so the two do not crowd each other.
    Control { name: "eleccion_ok", key: Key::Return, shape: Shape::Circle { cx: 0.932, cy: 0.600, r: 0.036 }, label: Label::Text("OK"), contexts: CHARACTER },
    Control { name: "eleccion_atras", key: Key::X, shape: Shape::Circle { cx: 0.972, cy: 0.420, r: 0.028 }, label: Label::Text("X"), contexts: CHARACTER },
    // TEMP: the key combination the game listens for as a trick. Its `key` is the first of the two;
    // the pair is pressed together (see `control_key_down`). Centred along the very top, and drawn
    // smaller than the rest because its label is a wide one.
    Control { name: COMBO_BUTTON_NAME, key: Key::LeftControl, shape: Shape::Circle { cx: 0.500, cy: 0.060, r: 0.018 }, label: Label::Text("C+P"), contexts: CHARACTER },
    // Controls of the app itself, not of the game: quick save, quick load and fast-forward. They
    // press no key (see `sys_action`) and sit in the top-left corner, clear of the stick and the rest.
    Control { name: SYS_QSAVE, key: Key::Space, shape: Shape::Circle { cx: 0.045, cy: 0.110, r: 0.022 }, label: Label::None, contexts: WORLD | BATTLE | SAFARI },
    Control { name: SYS_QLOAD, key: Key::Space, shape: Shape::Circle { cx: 0.045, cy: 0.215, r: 0.022 }, label: Label::None, contexts: WORLD | BATTLE | SAFARI },
    Control { name: SYS_FFWD, key: Key::Space, shape: Shape::Circle { cx: 0.045, cy: 0.320, r: 0.022 }, label: Label::None, contexts: WORLD | BATTLE | SAFARI },
    // Slots for buttons the player adds in the editor. Each stays inactive (no contexts) until
    // `layout.conf` gives it a key, a label and the screens it belongs to; see `CustomDef`.
    Control { name: "custom1", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom2", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom3", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom4", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom5", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom6", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom7", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
    Control { name: "custom8", key: Key::Space, shape: Shape::Circle { cx: 0.500, cy: 0.500, r: 0.026 }, label: Label::None, contexts: 0 },
];

pub const SYS_QSAVE: &str = "sys_qsave";
pub const SYS_QLOAD: &str = "sys_qload";
pub const SYS_FFWD: &str = "sys_ffwd";

/// What a control of the app itself does instead of pressing a key.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum SysAction {
    QuickSave,
    QuickLoad,
    FastForward,
    /// Shows or hides the readout of frames per second.
    Stats,
    /// Moves on to the next quick-save slot.
    NextSlot,
    /// Goes through the speeds one by one (normal, slow motion, fast-forward, normal again).
    Speed,
    /// Shows or hides the table of what each type is strong and weak against.
    TypeChart,
}

pub fn sys_action_named(name: &str) -> Option<SysAction> {
    match name {
        SYS_QSAVE => Some(SysAction::QuickSave),
        SYS_QLOAD => Some(SysAction::QuickLoad),
        SYS_FFWD => Some(SysAction::FastForward),
        _ => None,
    }
}

pub fn sys_action(index: usize) -> Option<SysAction> {
    if let Some(def) = custom_def(index) {
        return match def.mode {
            CustomMode::Action(action) => Some(action),
            _ => None,
        }
    }
    sys_action_named(CONTROLS[index].name)
}

/// How a button of the player's acts when it is touched.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum CustomMode {
    /// The key is down while the finger is.
    Press,
    /// While held, the key is tapped over and over (for mashing through texts and the like).
    Turbo,
    /// A touch switches the key on and a second touch switches it off: it stays down in between.
    Lock,
    /// Does something of the app's own instead of pressing a key.
    Action(SysAction),
}

/// What a custom button's key field says: a key, a key with `:turbo` or `:lock` after it, or an
/// action written `@speed` / `@types`. Actions have no key; they come back with a harmless one.
pub fn parse_custom_token(token: &str) -> Option<(Key, CustomMode)> {
    let token = token.trim();
    if let Some(action) = token.strip_prefix('@') {
        let action = match action.to_ascii_lowercase().as_str() {
            "speed" => SysAction::Speed,
            "types" => SysAction::TypeChart,
            _ => return None,
        };
        return Some((Key::Space, CustomMode::Action(action)))
    }
    let (key, mode) = match token.split_once(':') {
        None => (token, CustomMode::Press),
        Some((key, suffix)) => match suffix.trim().to_ascii_lowercase().as_str() {
            "turbo" => (key, CustomMode::Turbo),
            "lock" => (key, CustomMode::Lock),
            _ => return None,
        },
    };
    Some((parse_custom_key(key)?, mode))
}

/// How the button at `index` acts, if it is one of the player's own.
pub fn custom_mode(index: usize) -> Option<CustomMode> {
    custom_def(index).map(|def| def.mode)
}

/// Whether the lock at `index` still is what it was made as: still a lock, still on this key, and
/// not hidden.
pub fn lock_valid(index: usize, key: Key) -> bool {
    custom_def(index).map_or(false, |def| def.mode == CustomMode::Lock && def.key == key)
        && placement(CONTROLS[index].name).visible
}

static LOCKED: AtomicU32 = AtomicU32::new(0);

/// Marks a button as switched on (a lock that is down), so it is drawn lit.
pub fn set_locked(index: usize, locked: bool) {
    if locked {
        LOCKED.fetch_or(1 << index, Ordering::Relaxed);
    } else {
        LOCKED.fetch_and(!(1 << index), Ordering::Relaxed);
    }
}

/// Whether a button is drawn lit: it is being touched, it is a lock that is on, or it is the speed
/// button while the game runs at another speed.
pub fn is_lit(index: usize) -> bool {
    is_pressed(index)
        || LOCKED.load(Ordering::Relaxed) & (1 << index) != 0
        || (custom_mode(index) == Some(CustomMode::Action(SysAction::Speed)) && crate::extras::ffwd_on())
}

/// The table of types, over the game. The value is the screen it was opened on (0 = closed): the
/// table closes by itself when the game moves to another one.
static TYPE_CHART: AtomicU16 = AtomicU16::new(0);

pub fn type_chart_open() -> bool {
    TYPE_CHART.load(Ordering::Relaxed) != 0
}

pub fn open_type_chart() {
    TYPE_CHART.store(current_context().max(1), Ordering::Relaxed);
}

pub fn close_type_chart() {
    TYPE_CHART.store(0, Ordering::Relaxed);
}

/// Closes the table if the screen it was opened over is gone. Call every frame.
pub fn type_chart_follow() {
    let opened = TYPE_CHART.load(Ordering::Relaxed);
    if opened != 0 && opened != current_context() {
        close_type_chart();
    }
}

/// A button the player made: the key it presses, its label and the screens it shows on.
#[derive(Clone, Copy)]
pub struct CustomDef {
    pub key: Key,
    pub mode: CustomMode,
    pub label: &'static str,
    /// A picture instead of the label: the label is written `~name` (see `icon_named`).
    pub icon: Option<Icon>,
    pub contexts: u16,
}

pub const CUSTOM_SLOTS: usize = 8;
static CUSTOM: Mutex<[Option<CustomDef>; CUSTOM_SLOTS]> = Mutex::new([None; CUSTOM_SLOTS]);

fn custom_slot(name: &str) -> Option<usize> {
    let number: usize = name.strip_prefix("custom")?.parse().ok()?;
    (1..=CUSTOM_SLOTS).contains(&number).then(|| number - 1)
}

fn custom_def(index: usize) -> Option<CustomDef> {
    let slot = custom_slot(CONTROLS[index].name)?;
    CUSTOM.lock().unwrap()[slot]
}

/// The screens a control belongs to: its own, or the ones the player gave a custom button.
pub fn contexts_of(index: usize) -> u16 {
    if custom_slot(CONTROLS[index].name).is_some() {
        return custom_def(index).map(|def| def.contexts).unwrap_or(0)
    }
    CONTROLS[index].contexts
}

/// The key a control presses.
pub fn key_of(index: usize) -> Key {
    custom_def(index).map(|def| def.key).unwrap_or(CONTROLS[index].key)
}

/// What a control shows on its face.
pub fn label_of(index: usize) -> Label {
    match custom_def(index) {
        Some(def) => Label::Text(def.label),
        None => CONTROLS[index].label,
    }
}

/// The keys a custom button may press, by the names the editor writes.
pub fn parse_custom_key(name: &str) -> Option<Key> {
    const LETTERS: [Key; 26] = [
        Key::A, Key::B, Key::C, Key::D, Key::E, Key::F, Key::G, Key::H, Key::I, Key::J, Key::K, Key::L,
        Key::M, Key::N, Key::O, Key::P, Key::Q, Key::R, Key::S, Key::T, Key::U, Key::V, Key::W, Key::X,
        Key::Y, Key::Z,
    ];
    const DIGITS: [Key; 10] = [
        Key::Alpha0, Key::Alpha1, Key::Alpha2, Key::Alpha3, Key::Alpha4, Key::Alpha5, Key::Alpha6,
        Key::Alpha7, Key::Alpha8, Key::Alpha9,
    ];
    const FUNCTION: [Key; 12] = [
        Key::F1, Key::F2, Key::F3, Key::F4, Key::F5, Key::F6, Key::F7, Key::F8, Key::F9, Key::F10, Key::F11, Key::F12,
    ];
    let upper = name.trim().to_ascii_uppercase();
    let bytes = upper.as_bytes();
    if bytes.len() == 1 {
        if bytes[0].is_ascii_uppercase() {
            return Some(LETTERS[usize::from(bytes[0] - b'A')])
        }
        if bytes[0].is_ascii_digit() {
            return Some(DIGITS[usize::from(bytes[0] - b'0')])
        }
    }
    if let Some(number) = upper.strip_prefix('F').and_then(|n| n.parse::<usize>().ok()) {
        if (1..=12).contains(&number) {
            return Some(FUNCTION[number - 1])
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
        "SHIFT" => Key::LeftShift,
        "CONTROL" | "CTRL" => Key::LeftControl,
        _ => return None,
    })
}

/// TEMP: the button that presses the game's trick combination. The game wants both keys held at the
/// same time, so it is not an ordinary one-key control.
pub const COMBO_BUTTON_NAME: &str = "truco_ctrl_p";

/// Whether the picker's one-off trick button has already been used in this new game. It opens the
/// whole character roster, which only needs doing once, so it disappears after the first press and
/// is offered again only when a new game brings the picker back.
static COMBO_USED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_combo_used() {
    COMBO_USED.store(true, Ordering::Relaxed);
}

/// The room the character picker reports. Its own dialogues and its list of questions both live here.
const CHARACTER_ROOM: i32 = 3;

/// The modals the game draws, right after the character, as lists of options to pick from: the
/// preferences it asks about first and the difficulty right after. Both are walked with the movement
/// keys and answered with Z and X. Confirmed on the device: the picker's room 3 reports these ids
/// while each list is up, and nothing that is not one of them.
const PREFERENCES_MODAL: i32 = 7744;
const DIFFICULTY_MODAL: i32 = 7836;

/// The game's yes/no question dialog (script 9673, `ShowDialogQuestion`). It is used for "¿Salir
/// del juego?" and for "¿Deseas escapar del Pokémon Salvaje?", among others. It is answered by
/// touching its Sí/No boxes, so the overlay steps aside while it is up.
const QUESTION_MODAL: i32 = 9673;

/// The game's option-list modal (script 7778, drawn by `DrawModalBase`). It is the start menu
/// (Pokémon, Mochila, Pokédex...), the bag's action list and the options list. Confirmed on the
/// device: the start menu reports this id.
const LIST_MODAL: i32 = 7778;

/// The most characters the picker offers at once: one while choosing the player, two while choosing
/// the rival. Anything beyond that is the whole roster drawn at once by the game's own trick.
const PICKER_MAX_CHARACTERS: usize = 3;

/// The character picker's two arrows, and where they sit: one either side of the character.
const LEFT_ARROW_NAME: &str = "flechaizquierda";
const RIGHT_ARROW_NAME: &str = "flechaderecha";
const LEFT_ARROW_CX: f32 = 0.095;
const RIGHT_ARROW_CX: f32 = 0.430;
const ARROW_CY: f32 = 0.340;
const ARROW_R: f32 = 0.030;

/// How far from the character's edge the picker's arrows sit, as a fraction of the window width.
const ARROW_MARGIN: f32 = 0.045;

thread_local! {
    /// One box per character-sized sprite drawn this frame, in the game's own coordinates. The picker's
    /// arrows are placed from this, so they follow the character instead of sitting at a spot guessed
    /// in advance. Each candidate is kept apart: the arrows belong to the one that is selected, not
    /// around all of them.
    static CHARACTER_SPRITES: std::cell::RefCell<Vec<(f64, f64, f64, f64)>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

/// Notes one drawn sprite that the renderer judged to be character-sized.
pub fn note_sprite(x1: f64, y1: f64, x2: f64, y2: f64) {
    CHARACTER_SPRITES.with(|cell| {
        let mut seen = cell.borrow_mut();
        // The same sprite is drawn more than once in a frame, and one character can be drawn again in
        // another way (a shadow, an outline). Anything overlapping a box already kept is the same
        // character, and counting it twice would make the arrows step twice over one of them.
        let overlaps = seen.iter().any(|other: &(f64, f64, f64, f64)| {
            x1 < other.2 && other.0 < x2 && y1 < other.3 && other.1 < y2
        });
        if !overlaps {
            seen.push((x1, y1, x2, y2));
        }
    });
}

/// Starts a frame with nothing noted.
pub fn reset_character_sprites() {
    CHARACTER_SPRITES.with(|cell| cell.borrow_mut().clear());
}

/// The candidate boxes of the frame, in game coordinates, left to right.
pub fn character_sprites() -> Vec<(f64, f64, f64, f64)> {
    CHARACTER_SPRITES.with(|cell| {
        let mut boxes = cell.borrow().clone();
        boxes.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
        boxes
    })
}

/// The candidates' boxes as fractions of the window, left to right, as the engine last worked them
/// out. On the picker's screen there are two of them (the rival's) or one (the player's).
static PICKER_BOXES: Mutex<Vec<(f32, f32, f32, f32)>> = Mutex::new(Vec::new());

pub fn set_picker_boxes(boxes: Vec<(f32, f32, f32, f32)>) {
    // A frame that draws no character at all is almost always a blink of the game's own redrawing
    // rather than the end of the picker. Keeping the last ones means the arrows stay where they are
    // instead of falling back to their guessed spot and out again on the next frame.
    if boxes.is_empty() && !picker_boxes().is_empty() {
        return
    }
    *PICKER_BOXES.lock().unwrap() = boxes;
}

fn picker_boxes() -> Vec<(f32, f32, f32, f32)> {
    PICKER_BOXES.lock().unwrap().clone()
}

/// Whether the game has any of its modals up - the text dialog, a question it asks, a summary. The
/// engine reports it from the game's own modal state, and the picker's controls step aside for all of
/// them: those screens are gone through with the game's own keyboard and buttons.
static MODAL_OPEN: Sticky = Sticky::new();

pub fn set_modal_open(open: bool) {
    MODAL_OPEN.report(open);
}

/// The id of the modal the game is drawing (-1 when there is none), kept only for the log.
///
/// The game gives every one of its screens a modal of its own, and that id is what tells them apart:
/// the text dialog it types a name in is one, the boxes it asks questions with are others, the lists
/// it makes you choose from are others again. Reading that id is the only exact way of saying which
/// screens should have the joystick and the two buttons and which should have nothing at all.
static MODAL_ID: AtomicI32 = AtomicI32::new(-1);

pub fn set_modal_id(id: i32) {
    if MODAL_ID.swap(id, Ordering::Relaxed) != id {
        eprintln!("[overlay] modal {id}");
    }
}

pub fn modal_open() -> bool {
    MODAL_OPEN.get()
}

/// The modal the game is drawing right now, as it reported it this frame (-1 when none).
pub fn modal_id() -> i32 {
    MODAL_ID.load(Ordering::Relaxed)
}

/// Which of the candidates the arrows are around. The buttons step it, so they move from one to the
/// other the way the game's own selection does.
static PICKER_TARGET: AtomicUsize = AtomicUsize::new(0);

pub fn picker_step(delta: isize) {
    let next = PICKER_TARGET.load(Ordering::Relaxed) as isize + delta;
    PICKER_TARGET.store(next.max(0) as usize, Ordering::Relaxed);
}

pub fn picker_reset() {
    PICKER_TARGET.store(0, Ordering::Relaxed);
}

/// The control's shape. Positions and sizes are built in and not customisable, so this is the table's
/// own shape; the indirection stays because the renderer and the hit test share it.
pub fn effective_shape(index: usize) -> Shape {
    let shape = base_shape(index);
    let name = CONTROLS[index].name;
    // The picker's arrows follow the characters on screen, so the player's layout does not move them.
    let moved = name != LEFT_ARROW_NAME && name != RIGHT_ARROW_NAME;
    let place = if moved { placement(name) } else { Placement::default() };
    let scale = button_scale() * place.scale;
    match shape {
        Shape::Circle { cx, cy, r } => {
            let (cx, cy) = if place.moved { (place.cx, place.cy) } else { (cx, cy) };
            Shape::Circle { cx, cy, r: r * scale }
        },
        Shape::Rect { cx, cy, w, h } => {
            let (cx, cy) = if place.moved { (place.cx, place.cy) } else { (cx, cy) };
            Shape::Rect { cx, cy, w: w * scale, h: h * scale }
        },
    }
}

// ---------------------------------------------------------------------------------------------
// The player's layout (`layout.conf`, written by the launcher's editor): where each button sits,
// how big it is and whether it is shown. Lines are `<name>=<cx>,<cy>,<scale>,<visible>`; the
// stick is `joystick` and takes the same fields. Anything unrecognised is ignored, so an old or
// hand-edited file can never stop the game from starting.

#[derive(Clone, Copy)]
pub struct Placement {
    /// Whether `cx`/`cy` replace the built-in position.
    pub moved: bool,
    pub cx: f32,
    pub cy: f32,
    pub scale: f32,
    pub visible: bool,
}

impl Default for Placement {
    fn default() -> Self {
        Self { moved: false, cx: 0.0, cy: 0.0, scale: 1.0, visible: true }
    }
}

static LAYOUT: Mutex<Vec<(String, Placement)>> = Mutex::new(Vec::new());

pub fn placement(name: &str) -> Placement {
    let layout = LAYOUT.lock().unwrap();
    if layout.is_empty() {
        return Placement::default()
    }
    layout.iter().find(|(known, _)| known == name).map(|(_, p)| *p).unwrap_or_default()
}

/// Replaces the layout with what `text` says.
pub fn load_layout(text: &str) {
    let mut entries = Vec::new();
    let mut defs: [Option<CustomDef>; CUSTOM_SLOTS] = [None; CUSTOM_SLOTS];
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue
        }
        let Some((name, value)) = line.split_once('=') else { continue };
        let name = name.trim();
        if name != JOYSTICK_NAME && index_of(name).is_none() {
            continue
        }
        let mut parts = value.trim().split(',').map(|part| part.trim());
        let (Some(Ok(cx)), Some(Ok(cy)), Some(Ok(scale))) = (
            parts.next().map(|v| v.parse::<f32>()),
            parts.next().map(|v| v.parse::<f32>()),
            parts.next().map(|v| v.parse::<f32>()),
        ) else {
            continue
        };
        let visible = !matches!(parts.next(), Some("0") | Some("false") | Some("no"));
        if !(0.0..=1.0).contains(&cx) || !(0.0..=1.0).contains(&cy) {
            continue
        }
        // A custom button also says which key it presses, what it shows and where it appears:
        // `custom1=<cx>,<cy>,<scale>,<visible>,<key>,<label>,<contexts>`.
        if let Some(slot) = custom_slot(name) {
            let (Some((key, mode)), Some(label), Some(Ok(contexts))) = (
                parts.next().and_then(parse_custom_token),
                parts.next(),
                parts.next().map(|v| v.parse::<u16>()),
            ) else {
                continue
            };
            // Kept for the rest of the run: eight short labels at most, once per start of the game.
            let icon = label.strip_prefix('~').and_then(icon_named);
            let text = if icon.is_some() { "" } else { label.trim_start_matches('~') };
            let label: &'static str = Box::leak(text.chars().take(3).collect::<String>().to_ascii_uppercase().into_boxed_str());
            defs[slot] = Some(CustomDef { key, mode, label, icon, contexts });
        }
        entries.push((name.to_string(), Placement { moved: true, cx, cy, scale: scale.clamp(0.4, 2.5), visible }));
    }
    *CUSTOM.lock().unwrap() = defs;
    *LAYOUT.lock().unwrap() = entries;
}

// ---------------------------------------------------------------------------------------------
// Look. The default is the native one (smooth translucent buttons drawn as vectors); the pixel-art
// sprites remain as the "classic" style.

static NATIVE_STYLE: AtomicBool = AtomicBool::new(true);

/// How opaque the buttons are drawn right now: the player's setting times the fade of the idle
/// screen (see `extras::overlay_fade`).
pub fn overlay_opacity() -> f32 {
    sprite_opacity() * crate::extras::overlay_fade()
}

pub fn native_style() -> bool {
    NATIVE_STYLE.load(Ordering::Relaxed)
}

pub fn set_native_style(value: bool) {
    NATIVE_STYLE.store(value, Ordering::Relaxed);
}

/// Controls that keep their artwork in the native style: the character picker's arrows are part of
/// the picker's own screen, not a gamepad button.
pub fn keeps_art(name: &str) -> bool {
    name == LEFT_ARROW_NAME || name == RIGHT_ARROW_NAME
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Icon {
    Pause,
    Back,
    Ball,
    Save,
    Load,
    Forward,
    Up,
    Down,
    Left,
    Right,
    Star,
    Heart,
    Plus,
    Menu,
    Check,
    /// An exclamation mark: act, interact.
    Act,
    /// A runner.
    Run,
    /// A fishing hook.
    Hook,
    Bike,
    /// Three Pokémon: the party.
    Team,
    Bag,
    Map,
    /// An arrow leaving a doorway: run away.
    Exit,
    Smile,
    Chat,
    Clock,
    Magnifier,
    Person,
    Bolt,
    /// A wrapped box: the assigned item.
    Box,
    /// Two opposite arrows: switch.
    Swap,
    /// The mega evolution. Only the game's own `M` wears it in battle; it draws like the star when
    /// there is no picture of the game to borrow.
    Mega,
    /// Three bars: the table of types.
    Chart,
}

/// How many pictures there are, for tables that hold one slot per icon.
pub const ICON_COUNT: usize = Icon::Chart as usize + 1;

/// The pictures a custom button can wear, by the names the editor writes after a `~`.
pub fn icon_named(name: &str) -> Option<Icon> {
    match name.trim().to_ascii_lowercase().as_str() {
        "pause" => Some(Icon::Pause),
        "back" => Some(Icon::Back),
        "ball" => Some(Icon::Ball),
        "save" => Some(Icon::Save),
        "load" => Some(Icon::Load),
        "forward" => Some(Icon::Forward),
        "up" => Some(Icon::Up),
        "down" => Some(Icon::Down),
        "left" => Some(Icon::Left),
        "right" => Some(Icon::Right),
        "star" => Some(Icon::Star),
        "heart" => Some(Icon::Heart),
        "plus" => Some(Icon::Plus),
        "menu" => Some(Icon::Menu),
        "check" => Some(Icon::Check),
        "act" => Some(Icon::Act),
        "run" => Some(Icon::Run),
        "hook" => Some(Icon::Hook),
        "bike" => Some(Icon::Bike),
        "team" => Some(Icon::Team),
        "bag" => Some(Icon::Bag),
        "map" => Some(Icon::Map),
        "exit" => Some(Icon::Exit),
        "smile" => Some(Icon::Smile),
        "chat" => Some(Icon::Chat),
        "clock" => Some(Icon::Clock),
        "magnifier" => Some(Icon::Magnifier),
        "person" => Some(Icon::Person),
        "bolt" => Some(Icon::Bolt),
        "box" => Some(Icon::Box),
        "swap" => Some(Icon::Swap),
        "chart" => Some(Icon::Chart),
        _ => None,
    }
}

/// The icon a control wears: the one the player gave a custom button, or its own.
pub fn icon_at(index: usize) -> Option<Icon> {
    match custom_def(index) {
        Some(def) => def.icon,
        None => icon_for(CONTROLS[index].name, current_context()),
    }
}

static FUNCTION_ICONS: AtomicBool = AtomicBool::new(true);

/// Whether the function pictures are on (the buttons borrow the game's own art only then).
pub fn function_icons() -> bool {
    FUNCTION_ICONS.load(Ordering::Relaxed)
}

/// Whether the game's own buttons wear a picture of what they do (otherwise, their letter).
pub fn set_function_icons(value: bool) {
    FUNCTION_ICONS.store(value, Ordering::Relaxed);
}

/// The picture a button of the game wears in a given state of the game, if its job has one.
///
/// The same key does different things in different places (`M` is the map out of battle and the
/// mega evolution in it), so the picture follows the state. In battle `Z X C V` are the four
/// attacks, whose slot is what matters there, so they keep their letters.
pub fn icon_for(name: &str, context: u16) -> Option<Icon> {
    if let Some(icon) = icon_of(name) {
        return Some(icon)
    }
    if !FUNCTION_ICONS.load(Ordering::Relaxed) {
        return None
    }
    let fight = context & (BATTLE | SAFARI) != 0;
    let world = context & WORLD != 0;
    match name {
        "c" if world => Some(Icon::Hook),
        "v" if world => Some(Icon::Bike),
        "space" => Some(Icon::Team),
        "o" => Some(Icon::Bag),
        "m" => Some(if context & BATTLE != 0 { Icon::Mega } else { Icon::Map }),
        "e" => Some(if fight { Icon::Exit } else { Icon::Smile }),
        "t" => Some(if context & BATTLE != 0 { Icon::Clock } else { Icon::Chat }),
        "l" => Some(Icon::Magnifier),
        "p" => Some(Icon::Person),
        "n" => Some(Icon::Bolt),
        "a" => Some(Icon::Box),
        "s" => Some(Icon::Swap),
        _ => None,
    }
}

/// The icon a button wears instead of its letter, if it has one.
pub fn icon_of(name: &str) -> Option<Icon> {
    match name {
        "return" => Some(Icon::Menu),
        "escape" => Some(Icon::Back),
        "b" => Some(Icon::Ball),
        SYS_QSAVE => Some(Icon::Save),
        SYS_QLOAD => Some(Icon::Load),
        SYS_FFWD => Some(Icon::Forward),
        _ => None,
    }
}

/// Accent colour of a button, and whether its label takes it at rest. The four main buttons keep the
/// colours they have always had, which is how the player tells them apart at a glance.
pub fn accent_of(name: &str) -> ([f32; 3], bool) {
    match name {
        "x" | "eleccion_atras" => ([1.00, 0.36, 0.42], true),
        "z" | "dialogo" => ([0.56, 0.92, 0.42], true),
        "c" => ([0.46, 0.58, 1.00], true),
        "v" => ([1.00, 0.88, 0.30], true),
        "return" | "eleccion_ok" => ([0.40, 0.85, 1.00], false),
        SYS_QSAVE | SYS_QLOAD => ([0.62, 0.95, 0.80], false),
        SYS_FFWD => ([1.00, 0.78, 0.35], false),
        _ => ([0.55, 0.78, 1.00], false),
    }
}

/// How pressed each control looks, 0..1. It eases towards where the finger is, quickly down and a
/// little slower back up, so a press has a visible weight even at 30 frames a second.
static PRESS_ANIM: Mutex<[f32; 64]> = Mutex::new([0.0; 64]);
static ANIM_LAST: Mutex<Option<Instant>> = Mutex::new(None);

/// Call once per overlay draw. Returns the time since the previous one, in seconds.
pub fn anim_tick() -> f32 {
    let now = Instant::now();
    let mut last = ANIM_LAST.lock().unwrap();
    let dt = last.map(|at| now.duration_since(at).as_secs_f32()).unwrap_or(0.033).clamp(0.001, 0.1);
    *last = Some(now);
    dt
}

pub fn press_anim(index: usize, pressed: bool, dt: f32) -> f32 {
    let mut anim = PRESS_ANIM.lock().unwrap();
    let Some(value) = anim.get_mut(index) else { return if pressed { 1.0 } else { 0.0 } };
    let target = if pressed { 1.0 } else { 0.0 };
    // Time constants: ~40 ms down, ~110 ms up.
    let k = if pressed { 1.0 - (-dt / 0.04).exp() } else { 1.0 - (-dt / 0.11).exp() };
    *value += (target - *value) * k;
    *value
}

fn base_shape(index: usize) -> Shape {
    let control = &CONTROLS[index];
    // The picker's arrows sit beside the character, placed from where the game drew it: that way they
    // follow the sprite, on the player's screen and on the rival's, where the two characters are a
    // good way apart.
    if control.name == LEFT_ARROW_NAME || control.name == RIGHT_ARROW_NAME {
        let boxes = picker_boxes();
        let target = PICKER_TARGET.load(Ordering::Relaxed).min(boxes.len().saturating_sub(1));
        if let Some(&(x1, y1, x2, y2)) = boxes.get(target) {
            let cx = if control.name == LEFT_ARROW_NAME { x1 - ARROW_MARGIN } else { x2 + ARROW_MARGIN };
            // Kept inside the window, so a character drawn at the edge cannot push its arrow off it.
            let inside = |v: f32| v.clamp(ARROW_R, 1.0 - ARROW_R);
            return Shape::Circle { cx: inside(cx), cy: inside((y1 + y2) / 2.0), r: ARROW_R }
        }
    }
    // Nothing character-sized was drawn - a screen that is not the picker, or a sprite that could not
    // be measured - so the places built into the table are kept.
    control.shape
}

/// The rectangle the game image occupies in the window (normalised), as the renderer last presented
/// it. Dialogue controls are placed inside it, so the button lands on the dialogue box whatever the
/// screen's shape is and whichever scaling mode is in use.
static VIEWPORT: Mutex<(f32, f32, f32, f32)> = Mutex::new((0.0, 0.0, 1.0, 1.0));

pub fn set_viewport(x: f32, y: f32, w: f32, h: f32) {
    *VIEWPORT.lock().unwrap() = (x, y, w, h);
}

pub fn viewport() -> (f32, f32, f32, f32) {
    *VIEWPORT.lock().unwrap()
}

/// A control's shape in window coordinates. Most controls are placed relative to the window (they
/// live around its edges, where the thumbs are); the dialogue button is placed relative to the game
/// image, because it has to sit on the dialogue box the game draws.
pub fn window_shape(index: usize) -> Shape {
    let shape = effective_shape(index);
    if contexts_of(index) & DIALOG == 0 {
        return shape
    }
    let (vx, vy, vw, vh) = viewport();
    match shape {
        Shape::Circle { cx, cy, r } => Shape::Circle { cx: vx + cx * vw, cy: vy + cy * vh, r: r * vw },
        Shape::Rect { cx, cy, w, h } => Shape::Rect { cx: vx + cx * vw, cy: vy + cy * vh, w: w * vw, h: h * vh },
    }
}

/// The inverse: a point in window coordinates, expressed in a control's own coordinates.
pub fn control_point(index: usize, nx: f32, ny: f32) -> (f32, f32) {
    if contexts_of(index) & DIALOG == 0 {
        return (nx, ny)
    }
    let (vx, vy, vw, vh) = viewport();
    ((nx - vx) / vw.max(0.0001), (ny - vy) / vh.max(0.0001))
}

/// Looks a control up by its name.
pub fn index_of(name: &str) -> Option<usize> {
    CONTROLS.iter().position(|control| control.name == name)
}

/// The room the emulator reported, or -1 before the first frame.
static ROOM: AtomicI32 = AtomicI32::new(-1);

/// Called by the emulator every frame with the current room id.
pub fn set_room(room: i32) {
    static LAST_ROOM: AtomicI32 = AtomicI32::new(i32::MIN);
    if LAST_ROOM.swap(room, Ordering::Relaxed) != room {
        // A new screen starts on its first character, which is where the game's own selection starts.
        picker_reset();
        // A fresh picker offers its one-off trick button again; using it once was enough last time.
        COMBO_USED.store(false, Ordering::Relaxed);
    }
    ROOM.store(room, Ordering::Relaxed);
    log_state_change(room);
}

/// Whether the game has one of its dialogue boxes open. Reported every frame by the engine, which
/// reads it from the game's own modal state; the overlay then leaves a single button.
static DIALOGUE: Sticky = Sticky::new();

pub fn set_dialogue(open: bool) {
    if DIALOGUE.report(open) {
        eprintln!(
            "[overlay] dialogo {}",
            if open { "abierto: solo el boton de avanzar" } else { "cerrado" }
        );
    }
}

pub fn dialogue_open() -> bool {
    DIALOGUE.get()
}

/// Whether the game's own Pokémon box (the PC storage) is open. Its screen is driven entirely with
/// the game's own click boxes, so nothing of the overlay is wanted there; the engine reports it from
/// the game's `ENCAJA` global.
static BOX_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_box_open(open: bool) {
    BOX_OPEN.store(open, Ordering::Relaxed);
}

pub fn box_open() -> bool {
    BOX_OPEN.load(Ordering::Relaxed)
}

/// Whether the game has its own text-entry dialog open (the modal that draws the on-screen keyboard,
/// GML script 7754). The engine reports it every time it draws, and the Android side raises the
/// system keyboard while it is true.
static TEXT_ENTRY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_text_entry(open: bool) {
    let was = TEXT_ENTRY.swap(open, Ordering::Relaxed);
    if was != open {
        eprintln!("[ime] entrada de texto {}", if open { "abierta" } else { "cerrada" });
    }
}

pub fn text_entry_open() -> bool {
    TEXT_ENTRY.load(Ordering::Relaxed)
}

/// Height of the system keyboard, in window pixels, while it is up. The Android side reports it and
/// the renderer slides the game picture up with it, so the text being edited stays above the
/// keyboard instead of hiding under it.
static IME_INSET: AtomicU32 = AtomicU32::new(0);

pub fn set_ime_inset(pixels: u32) {
    IME_INSET.store(pixels, Ordering::Relaxed);
}

pub fn ime_inset() -> u32 {
    IME_INSET.load(Ordering::Relaxed)
}

/// The height the system keyboard is taking, but only while the game's text dialog is open.
///
/// Outside the text dialog the reported height is ignored on purpose. A height left behind by a
/// callback that never came would otherwise keep the game pushed up top and the on-screen controls
/// hidden, and the player would have no way of getting them back: tying it to the dialog means
/// closing the dialog always puts everything back where it was.
pub fn text_entry_inset() -> u32 {
    if text_entry_open() {
        ime_inset()
    } else {
        0
    }
}

/// Records every change of state, which is what decides which on-screen controls exist.
///
/// A report of buttons missing, or in the wrong place, cannot be chased without knowing what the
/// overlay believed at the time: which room, which group of controls it picked, and how many of
/// them it counted as active. It only writes when something changes, so it stays quiet while
/// playing.
#[cfg(target_os = "android")]
fn log_state_change(room: i32) {
    static LAST: AtomicU32 = AtomicU32::new(u32::MAX);
    static LAST_ROOM: AtomicI32 = AtomicI32::new(i32::MIN);
    let group = u32::from(current_context());
    let room_changed = LAST_ROOM.swap(room, Ordering::Relaxed) != room;
    if LAST.swap(group, Ordering::Relaxed) == group {
        // The group did not change, but knowing which room we are in is what makes it possible to
        // say "this screen needs no controls" about a specific one.
        if room_changed {
            eprintln!("[overlay] sala {} -> grupo {}", room, group);
        }
        return
    }
    let (window_w, window_h) = ramen::platform::android::window_size();
    let active = (0..CONTROLS.len()).filter(|index| is_active(*index)).count();
    eprintln!("[overlay] sala {} -> grupo {} | ventana {}x{} | {} de {} activos", room, group, window_w, window_h, active, CONTROLS.len());
    // Every control, one per line, so a report of "buttons missing" can be checked against what the
    // overlay actually decided: which key, where it was put, how big it is, and whether it counted
    // as visible. Normalised coordinates are fractions of the window, so they can be compared
    // between devices of any size.
    for (index, control) in CONTROLS.iter().enumerate() {
        // The shape actually in use, not the table's: a control that is moved to follow the game (the
        // picker's arrows) has to be reported where it really is.
        let (at, size) = match effective_shape(index) {
            Shape::Circle { cx, cy, r } => (format!("{:.3},{:.3}", cx, cy), format!("r={:.3}", r)),
            Shape::Rect { cx, cy, w, h } => (format!("{:.3},{:.3}", cx, cy), format!("{:.3}x{:.3}", w, h)),
        };
        let name = match control.label {
            Label::None => "",
            Label::Arrow(_) => "flecha",
            Label::Text(text) => text,
        };
        eprintln!(
            "[overlay]   {:>2}. {:<6} {:<12} en ({}) {} -> {}",
            index,
            name,
            format!("{:?}", control.key),
            at,
            size,
            if is_active(index) { "visibles" } else { "ocultos" }
        );
    }
}

/// Nothing to report where there is no overlay.
#[cfg(not(target_os = "android"))]
fn log_state_change(_room: i32) {}

/// Which group of controls the current game state needs.
pub fn current_context() -> u16 {
    // A dialogue box takes over the overlay: only the buttons that go with it are shown, so the text
    // and the portrait stay readable.
    if dialogue_open() {
        return DIALOG
    }
    // The Pokémon box is entirely point-and-click, so nothing is needed next to it: no controls at
    // all. MENU is the context no control belongs to, which is exactly that.
    if box_open() {
        return MENU
    }
    // The system keyboard is up. It owns the bottom of the screen and the controls would sit under
    // it, so the whole overlay steps aside - buttons and joystick - until it is gone. Typing needs
    // no buttons, and the game's own dialogs are advanced with the keyboard's own key.
    if text_entry_inset() > 0 {
        return MENU
    }
    // The game's yes/no questions are answered by touching their Sí/No boxes: the buttons would
    // only cover them. Confirmed on the device: the exit-game and the escape question both report
    // 9673, and 9674 is the bigger variant. Read straight from the current frame (not the sticky
    // state) so the buttons come back as soon as the question closes instead of after a grace.
    if matches!(modal_id(), QUESTION_MODAL | 9674) {
        return MENU
    }
    // The game's option lists are navigated with the joystick and the entries are touched directly.
    if modal_id() == LIST_MODAL {
        return MENU_NAV
    }
    match ROOM.load(Ordering::Relaxed) {
        // IntroROOM, InicioROOM, CargarPartida, MultijugadorMenu, CreditosInicioScreen, OptionsROOM:
        // these screens are fully clickable (once the game's own click boxes are fixed, see
        // `crate::patches`), so they need no on-screen controls at all.
        //
        // 528 is the Oak laboratory's introduction and 527 the unknown place that follows it: both
        // are cutscenes the player watches before they can take a step, so nothing of the map's
        // overlay belongs over them. Their dialogues still get their own button, handled above.
        // Confirmed on the device - the two report as rooms 528 and 527.
        22 | 30 | 43 | 461 | 465 | 466 | 527 | 528 => MENU,
        // The screen where a new game asks you to pick your character, just after the title menu. It
        // has controls of its own (the two arrows) instead of the map's. Its room cannot be named
        // from the game's data - the names are not readable - so this is the room it reports.
        //
        // A modal of its own - the preferences it asks about next, drawn as a list over the same
        // screen - is walked with the movement keys and answered with Z and X, so it gets those and
        // the joystick. A dialogue here is handled above and stays on the dialogue's two buttons; the
        // name the game types in is handled by the keyboard, so it gets nothing.
        CHARACTER_ROOM => {
            if text_entry_open() {
                MENU
            } else if matches!(modal_id(), PREFERENCES_MODAL | DIFFICULTY_MODAL) {
                CHOICE
            } else {
                CHARACTER
            }
        },
        // BattleROOM, BattleROOMAqua, BattleROOMLava.
        47 | 191 | 709 => BATTLE,
        // The full-screen menus: each is a room of its own, entered from the map or from a battle.
        room if screen_of(room).is_some() => screen_of(room).unwrap_or(WORLD),
        // The Safari Zone's catching room. Confirmed on the device: it reports room 715 while the
        // ball is being thrown there. It keeps the map's buttons bar the stick and the attacks.
        715 => SAFARI,
        // Before the first frame we don't know the room yet; show nothing rather than flashing the
        // whole overlay for one frame.
        -1 => MENU,
        // A shop, or the screen that stores or takes an item at the PC, is not a room but an object
        // the game puts over the map; it is walked the same way as the menus.
        _ if shop_open() => SCREEN,
        _ => WORLD,
    }
}

/// The controls one of the game's full-screen menus needs, by the room it is in. `None` for any other
/// room. The rooms are the ones the game names `PokemonMenu`, `PkmnHabMenu`, `PkmnMovMenu`,
/// `PkmnInfoMenu`, `PokedexMenu`, `Pokedex2Menu`, `MapaMenu`, `OpcionesMenu`, `MochilaMenu`,
/// `PersonajeMenu`, `HuevoMenu`, `MovilMenu`, `Pokedex3Menu`, `XpodMenu`, `LogrosMenu`,
/// `TorneoEliminatoriasMenu`, `ComandosMenu`, `MisionesMenu` and `PokedexLugarMenu`.
///
/// The keys behind each screen are the ones its own key script reads (`MenuMochilaKeyActions`,
/// `MainMenuPokemonKeyActions`, `ControlMapaKeyActions`...): Z and X and the arrows everywhere, and
/// C where it sorts the list (the bag, the team, the Pokédex, the missions) or moves the map's cursor.
/// The other keys those scripts read are shortcuts to entries of the list that Z opens.
pub fn screen_of(room: i32) -> Option<u16> {
    match room {
        // PokemonMenu, MochilaMenu, PokedexMenu, Pokedex2Menu, Pokedex3Menu, MapaMenu, MisionesMenu.
        4 | 32 | 17 | 18 | 266 | 21 | 516 => Some(SCREEN_C),
        // PkmnHabMenu, PkmnMovMenu, PkmnInfoMenu, OpcionesMenu, PersonajeMenu, HuevoMenu, MovilMenu,
        // XpodMenu, LogrosMenu, TorneoEliminatoriasMenu, ComandosMenu, PokedexLugarMenu.
        5 | 6 | 7 | 31 | 44 | 117 | 212 | 410 | 436 | 449 | 514 | 525 => Some(SCREEN),
        _ => None,
    }
}

/// The ids of the game's objects that are a shop (`obj_compra`, `obj_venta`, `obj_venta_decoracion`)
/// or the screens that exchange items (`obj_canjea_obj`, `obj_canjea_tutor`, `obj_canjea_mun`) or
/// keep them at the PC (`obj_guardarObjetoPC`, `obj_sacarObjetoPC`). The game creates one of them
/// over the map when it opens and destroys it when it closes.
pub const SHOP_OBJECTS: [i32; 8] = [180, 181, 1282, 1181, 1182, 1288, 446, 447];

static SHOP_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_shop_open(open: bool) {
    if SHOP_OPEN.swap(open, Ordering::Relaxed) != open {
        eprintln!("[overlay] tienda {}", if open { "abierta" } else { "cerrada" });
    }
}

pub fn shop_open() -> bool {
    SHOP_OPEN.load(Ordering::Relaxed)
}

/// True while the player is on a pad. The on-screen controls then step aside, because somebody
/// holding a controller does not want buttons under their thumbs; the first touch on the screen
/// brings them back.
static PAD_ACTIVE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_pad_active(active: bool) {
    PAD_ACTIVE.store(active, Ordering::Relaxed);
}

pub fn pad_active() -> bool {
    PAD_ACTIVE.load(Ordering::Relaxed)
}

/// True while one of the game's information windows is up. The whole overlay steps aside: its
/// buttons would be drawn over the page and would still send keys to the game behind it.
static INFO_WINDOW_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_info_window(open: bool) {
    INFO_WINDOW_OPEN.store(open, Ordering::Relaxed);
}

pub fn info_window_open() -> bool {
    INFO_WINDOW_OPEN.load(Ordering::Relaxed)
}

pub fn is_active(index: usize) -> bool {
    if pad_active() || info_window_open() || type_chart_open() || contexts_of(index) & current_context() == 0 {
        return false
    }
    if !placement(CONTROLS[index].name).visible {
        return false
    }
    // The picker's trick button opens the whole roster once, and then has done its job: it is taken
    // away rather than left over the screen for the rest of the game. It comes back with the next new
    // game (see [set_room]).
    if CONTROLS[index].name == COMBO_BUTTON_NAME && COMBO_USED.load(Ordering::Relaxed) {
        return false
    }
    // The picker's own controls belong to the moment the game is showing the characters to choose
    // from. With any of its modals up - the name dialog, the question it asks to check the data, a
    // summary - nothing of the picker belongs on top: those screens are gone through with the game's
    // own keyboard and buttons.
    if contexts_of(index) & CHARACTER != 0 {
        if modal_open() {
            return false
        }
        // The two arrows travel with the characters, so they only belong where those are being chosen:
        // one (the player) or two (the rival). The game's own trick puts up a screen with the whole
        // roster at once, drawn as a grid; the boxes churn from frame to frame there and the arrows
        // would hop about, so they sit that one out. The rest of the controls stay put: they do not
        // follow anything.
        let candidates = picker_boxes().len();
        if matches!(label_of(index), Label::Arrow(_)) && !(1..=PICKER_MAX_CHARACTERS).contains(&candidates) {
            return false
        }
    }
    true
}

/// The joystick drives the arrow keys, which is how the game's menus (the ones that still show
/// controls) and the overworld are navigated.
pub fn joystick_active() -> bool {
    !pad_active()
        && !info_window_open()
        && joystick_visible()
        && current_context() & (WORLD | BATTLE | CHOICE | MENU_NAV | SCREEN | SCREEN_C) != 0
}

/// Returns the index of the control containing the point, if any. The point is normalised, but the
/// test is done in pixels so the touchable area matches what is drawn on a screen that is not square.
pub fn hit(nx: f32, ny: f32, ww: f32, wh: f32) -> Option<usize> {
    CONTROLS
        .iter()
        .enumerate()
        .position(|(index, control)| is_active(index) && control_contains(index, control.name, nx, ny, ww, wh))
}

/// Like the test `hit` makes for one control, but against a copy of it scaled by `margin`.
pub fn hit_with_margin(index: usize, nx: f32, ny: f32, ww: f32, wh: f32, margin: f32) -> bool {
    let shape = match window_shape(index) {
        Shape::Circle { cx, cy, r } => Shape::Circle { cx, cy, r: r * margin },
        Shape::Rect { cx, cy, w, h } => Shape::Rect { cx, cy, w: w * margin, h: h * margin },
    };
    if let Some(aspect) = art_aspect(CONTROLS[index].name) {
        let (cx, cy, half) = match shape {
            Shape::Circle { cx, cy, r } => (cx, cy, r * ww),
            Shape::Rect { cx, cy, w, h } => (cx, cy, (w * ww + h * wh) / 4.0),
        };
        return (nx - cx).abs() * ww <= half * aspect && (ny - cy).abs() * wh <= half
    }
    contains(shape, nx, ny, ww, wh)
}

/// Whether a point is inside a control. A control with artwork is tested against the rectangle its
/// picture was actually drawn into, so a wide label is as touchable as it is painted instead of only
/// inside the little circle its key is defined with. The rest keep their own shape's test.
fn control_contains(index: usize, name: &str, nx: f32, ny: f32, ww: f32, wh: f32) -> bool {
    let shape = window_shape(index);
    if let Some(aspect) = art_aspect(name) {
        let (cx, cy, half) = match shape {
            Shape::Circle { cx, cy, r } => (cx, cy, r * ww),
            Shape::Rect { cx, cy, w, h } => (cx, cy, (w * ww + h * wh) / 4.0),
        };
        // The renderer sizes every picture by its height and lets its width follow its own aspect.
        return (nx - cx).abs() * ww <= half * aspect && (ny - cy).abs() * wh <= half
    }
    contains(shape, nx, ny, ww, wh)
}

/// How opaque the controls that have their own artwork are drawn (1 solid, lower lets the game show
/// through). Read from the launcher's `controls.conf` at startup. Controls drawn as plain shapes and
/// the joystick are not affected.
static SPRITE_OPACITY: Mutex<f32> = Mutex::new(1.0);

pub fn set_sprite_opacity(value: f32) {
    *SPRITE_OPACITY.lock().unwrap() = value.clamp(0.05, 1.0);
}

pub fn sprite_opacity() -> f32 {
    *SPRITE_OPACITY.lock().unwrap()
}

/// The shape each control's artwork was painted in (width over height), as the renderer packed it.
/// Empty until the skin is loaded, and only used to widen the touch area of a picture that is not as
/// tall as it is wide.
static ART_ASPECTS: Mutex<Vec<(&'static str, f32)>> = Mutex::new(Vec::new());

pub fn set_art_aspect(name: &'static str, aspect: f32) {
    let mut aspects = ART_ASPECTS.lock().unwrap();
    aspects.retain(|(known, _)| *known != name);
    aspects.push((name, aspect));
}

fn art_aspect(name: &str) -> Option<f32> {
    if native_style() && !keeps_art(name) {
        return None
    }
    ART_ASPECTS.lock().unwrap().iter().find(|(known, _)| *known == name).map(|(_, aspect)| *aspect)
}

static PRESSED: AtomicU32 = AtomicU32::new(0);

pub fn set_pressed(index: usize, pressed: bool) {
    if pressed {
        PRESSED.fetch_or(1 << index, Ordering::Relaxed);
    } else {
        PRESSED.fetch_and(!(1 << index), Ordering::Relaxed);
    }
}

pub fn is_pressed(index: usize) -> bool {
    PRESSED.load(Ordering::Relaxed) & (1 << index) != 0
}

/// Controls that are pressed but no longer valid, so the caller can release their keys instead of
/// leaving one stuck down when the game changes state.
pub fn stale_pressed() -> Vec<usize> {
    (0..CONTROLS.len()).filter(|&index| is_pressed(index) && !is_active(index)).collect()
}

// ---------------------------------------------------------------------------------------------
// Movement joystick. It presses the arrow keys depending on which way the stick is pushed.

pub const JOYSTICK_CX: f32 = 0.135;
pub const JOYSTICK_CY: f32 = 0.700;
/// Radius as a fraction of the window *height*, so the base stays round on screen.
pub const JOYSTICK_R: f32 = 0.200;
/// Default dead zone, as a fraction of the radius, before a direction registers.
pub const JOYSTICK_DEAD: f32 = 0.35;

// Settings written by the launcher into `controls.conf`. Stored as f32 bit patterns in atomics so
// the input thread and the renderer can read them without taking a lock on every touch.
static SCALE_BITS: AtomicU32 = AtomicU32::new(0x3f80_0000); // 1.0
static DEAD_BITS: AtomicU32 = AtomicU32::new(0x3eb3_3333); // 0.35
static FLOATING: AtomicBool = AtomicBool::new(false);
/// Where a floating stick is anchored while a finger is on it (normalised); `None` = at rest.
static FLOAT_CENTER: Mutex<Option<(f32, f32)>> = Mutex::new(None);

/// Scale of the on-screen buttons (1 = as designed).
pub fn button_scale() -> f32 {
    f32::from_bits(SCALE_BITS.load(Ordering::Relaxed))
}

pub fn set_button_scale(value: f32) {
    SCALE_BITS.store(value.clamp(0.6, 1.6).to_bits(), Ordering::Relaxed);
}

/// Dead zone of the stick, as a fraction of its radius.
pub fn joystick_dead() -> f32 {
    f32::from_bits(DEAD_BITS.load(Ordering::Relaxed))
}

pub fn set_joystick_dead(value: f32) {
    DEAD_BITS.store(value.clamp(0.05, 0.8).to_bits(), Ordering::Relaxed);
}

/// A floating stick appears wherever the thumb lands in the lower-left of the screen, instead of
/// being fixed in one place.
pub fn joystick_floating() -> bool {
    FLOATING.load(Ordering::Relaxed)
}

pub fn set_joystick_floating(value: bool) {
    FLOATING.store(value, Ordering::Relaxed);
    if !value {
        *FLOAT_CENTER.lock().unwrap() = None;
    }
}

/// The joystick's centre, in normalised coordinates.
pub fn joystick_center() -> (f32, f32) {
    if let Some(center) = *FLOAT_CENTER.lock().unwrap() {
        return center
    }
    let place = placement(JOYSTICK_NAME);
    if place.moved {
        return (place.cx, place.cy)
    }
    (JOYSTICK_CX, JOYSTICK_CY)
}

/// Moves the floating stick's anchor (normalised coordinates).
pub fn set_joystick_center(cx: f32, cy: f32) {
    *FLOAT_CENTER.lock().unwrap() = Some((cx, cy));
}

/// Puts a floating stick back at rest.
pub fn reset_joystick_center() {
    *FLOAT_CENTER.lock().unwrap() = None;
}

/// Radius as a fraction of the window height.
pub fn joystick_radius() -> f32 {
    JOYSTICK_R * button_scale().clamp(0.8, 1.3) * placement(JOYSTICK_NAME).scale
}

pub fn joystick_visible() -> bool {
    placement(JOYSTICK_NAME).visible
}

/// Whether a touch at the point starts the stick. A fixed stick is the ring itself; a floating
/// one takes any free touch in the lower-left, which is where the left thumb rests.
pub fn joystick_hit(nx: f32, ny: f32, ww: f32, wh: f32) -> bool {
    if !joystick_active() {
        return false
    }
    if joystick_floating() {
        return nx <= 0.42 && ny >= 0.30
    }
    let (cx, cy) = joystick_center();
    let r = joystick_radius() * wh;
    let dx = (nx - cx) * ww;
    let dy = (ny - cy) * wh;
    dx * dx + dy * dy <= r * r
}

/// `[up, down, left, right]` to hold for a stick at (nx, ny), given the bits already held.
pub fn joystick_dirs(nx: f32, ny: f32, ww: f32, wh: f32, held: u32) -> [bool; 4] {
    let (cx, cy) = joystick_center();
    let r = joystick_radius() * wh;
    let dx = (nx - cx) * ww;
    let dy = (ny - cy) * wh;
    stick_dirs_held(dx / r, dy / r, held)
}

/// Which directions a stick pushed by (dx, dy) is asking for: up, down, left, right.
pub fn stick_dirs(dx: f32, dy: f32) -> [bool; 4] {
    stick_dirs_held(dx, dy, 0)
}

/// Same, knowing which directions are already held (bit 0 up, 1 down, 2 left, 3 right).
///
/// The stick gives eight directions: a diagonal when both axes are clearly engaged, otherwise one axis at a time: when
/// both are past the dead zone the stronger one wins, and the axis already held keeps priority
/// until the other is clearly stronger. That stops a thumb drifting across a diagonal from
/// zig-zagging the character. A direction that is held stays on until the thumb comes back well
/// inside the dead zone, so a stick resting near the edge of it does not flicker. A physical pad's
/// stick goes through here too, so both feel the same.
pub fn stick_dirs_held(dx: f32, dy: f32, held: u32) -> [bool; 4] {
    let dead = joystick_dead();
    let release = dead * 0.7;
    let limit = |bit: u32| if held & bit != 0 { release } else { dead };
    let up = dy < -limit(1);
    let down = dy > limit(2);
    let left = dx < -limit(4);
    let right = dx > limit(8);
    let vertical = up || down;
    let horizontal = left || right;
    if vertical && horizontal {
        // Eight directions everywhere: both axes stay on while the weaker one is at least ~45% of
        // the stronger (a 24-degree margin either side of the diagonal), and once a diagonal is
        // held it takes only ~30% to keep it, so the thumb can wobble without the direction
        // flickering. Between those, the stronger axis alone wins.
        {
            let both_held = held & 0b0011 != 0 && held & 0b1100 != 0;
            let (major, minor) = if dy.abs() >= dx.abs() { (dy.abs(), dx.abs()) } else { (dx.abs(), dy.abs()) };
            let ratio = if both_held { 0.30 } else { 0.45 };
            if minor >= major * ratio {
                return [up, down, left, right]
            }
        }
        let held_vertical = held & 0b0011 != 0;
        let held_horizontal = held & 0b1100 != 0;
        // The axis that was already held gets a 25% head start.
        let v = dy.abs() * if held_vertical && !held_horizontal { 1.25 } else { 1.0 };
        let h = dx.abs() * if held_horizontal && !held_vertical { 1.25 } else { 1.0 };
        if v >= h {
            return [up, down, false, false]
        }
        return [false, false, left, right]
    }
    [up, down, left, right]
}

/// The stick's offset from its centre, in units of its radius (for drawing the knob).
static JOYSTICK_OFFSET: Mutex<(f32, f32)> = Mutex::new((0.0, 0.0));

pub fn set_joystick_offset(dx: f32, dy: f32) {
    *JOYSTICK_OFFSET.lock().unwrap() = (dx, dy);
}

pub fn joystick_offset() -> (f32, f32) {
    *JOYSTICK_OFFSET.lock().unwrap()
}

/// Point-in-shape, in pixels so it matches what is drawn.
pub fn contains(shape: Shape, nx: f32, ny: f32, ww: f32, wh: f32) -> bool {
    match shape {
        Shape::Circle { cx, cy, r } => {
            let (dx, dy) = ((nx - cx) * ww, (ny - cy) * wh);
            let radius = r * ww;
            dx * dx + dy * dy <= radius * radius
        },
        Shape::Rect { cx, cy, w, h } => (nx - cx).abs() * ww <= w * ww / 2.0 && (ny - cy).abs() * wh <= h * wh / 2.0,
    }
}

// ---------------------------------------------------------------------------------------------
// Party data. The game keeps it in `global.pkmn1[0..5]` (each slot's species); `game.rs` reads it
// and drops it here, so the overlay can draw each Pokémon's own sprite in its hexagon.

/// Name of the button that opens the party wheel. It presses no key of its own. It deliberately has
/// no `pkmn.png` of its own: the honeycomb artwork belongs to the wheel, not to the button.
pub const PARTY_NAME: &str = "pkmn";

pub const PICKER_SLOTS: usize = 6;

/// The key each slot presses: the game's 1..6.
pub const PICKER_KEYS: [Key; PICKER_SLOTS] =
    [Key::Alpha1, Key::Alpha2, Key::Alpha3, Key::Alpha4, Key::Alpha5, Key::Alpha6];

static PARTY_SPECIES: Mutex<Option<[i32; PICKER_SLOTS]>> = Mutex::new(None);
static PARTY_VERSION: AtomicU64 = AtomicU64::new(0);

pub fn set_party(species: [i32; PICKER_SLOTS]) {
    let changed = PARTY_SPECIES.lock().unwrap().as_ref() != Some(&species);
    *PARTY_SPECIES.lock().unwrap() = Some(species);
    if changed {
        // The renderer watches this to know when the GIFs under the slots changed.
        PARTY_VERSION.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn party_species() -> [i32; PICKER_SLOTS] {
    PARTY_SPECIES.lock().unwrap().unwrap_or([0; PICKER_SLOTS])
}

pub fn party_version() -> u64 {
    PARTY_VERSION.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------------------------------------
// Party wheel. Holding the selector button opens the team around the middle of the screen, like the
// weapon wheel in a certain game: slide towards the one you want and let go. Each step sends the key
// the game uses to pick, so its own screen highlights the same one as you move.

static WHEEL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static WHEEL_SEL: AtomicI32 = AtomicI32::new(-1);

pub fn wheel_open() -> bool {
    WHEEL.load(Ordering::Relaxed)
}

pub fn wheel_selected() -> i32 {
    WHEEL_SEL.load(Ordering::Relaxed)
}

pub fn set_wheel_open(open: bool) {
    WHEEL.store(open, Ordering::Relaxed);
    if !open {
        WHEEL_SEL.store(-1, Ordering::Relaxed);
    }
}

pub fn set_wheel_selected(index: i32) {
    WHEEL_SEL.store(index, Ordering::Relaxed);
}

/// The wheel's centre, in pixels: the middle of the screen, since the honeycomb needs the room.
pub fn wheel_center(ww: f32, wh: f32) -> (f32, f32) {
    (ww * 0.5, wh * 0.5)
}

/// How far out the slots sit, in pixels: the honeycomb is three of these wide.
pub fn wheel_radius(ww: f32, wh: f32) -> f32 {
    ww.min(wh) * 0.21
}

/// Where each of the honeycomb's six slots sits, taken from the shape of the artwork (79x74). A
/// honeycomb of seven hexagons is 2(1+√3) radii wide and 5 radii tall, so its hexagons have a
/// circumradius of 79/2/(1+√3) ≈ 14.5: each side slot sits √3 radii out and each diagonal
/// (√3/2, 1.5) radii out. Horizontal offsets are fractions of the width, vertical of the height,
/// which is how the renderer scales it.
const WHEEL_OFFSETS: [(f32, f32); PICKER_SLOTS] = [
    (-0.159, -0.293), // up-left
    (0.159, -0.293),  // up-right
    (-0.317, 0.0),    // left
    (0.317, 0.0),     // right
    (-0.159, 0.293),  // down-left
    (0.159, 0.293),   // down-right
];

/// How far a slot's centre sits from the selector's, as a fraction of the artwork's half-width. The
/// renderer sizes the artwork with this so its hexagons land exactly on the slots.
pub const SELECTOR_SLOT_DISTANCE: f32 = 0.634;

/// The artwork is very slightly wider than it is tall; the renderer keeps that when scaling it.
pub const SELECTOR_ASPECT: f32 = 74.0 / 79.0;

/// The centre of one of the wheel's slots, in pixels.
pub fn wheel_item_center(index: usize, ww: f32, wh: f32) -> (f32, f32) {
    let (cx, cy) = wheel_center(ww, wh);
    let half = wheel_radius(ww, wh) / SELECTOR_SLOT_DISTANCE;
    let (fx, fy) = WHEEL_OFFSETS[index];
    // The artwork is scaled by its width, so its vertical distances have to follow the same squeeze.
    (cx + fx * 2.0 * half, cy + fy * 2.0 * half * SELECTOR_ASPECT)
}

/// Which slot the finger is pointing at, once it is far enough from the middle.
pub fn wheel_item_at(nx: f32, ny: f32, ww: f32, wh: f32) -> Option<usize> {
    let (cx, cy) = wheel_center(ww, wh);
    let half = wheel_radius(ww, wh) / SELECTOR_SLOT_DISTANCE;
    let (fx, fy) = ((nx * ww - cx) / (2.0 * half), (ny * wh - cy) / (2.0 * half * SELECTOR_ASPECT));
    if fx * fx + fy * fy < 0.10 {
        return None
    }
    let mut best = None;
    let mut closest = f32::MAX;
    for (index, (ox, oy)) in WHEEL_OFFSETS.iter().enumerate() {
        let apart = (fx - ox).powi(2) + (fy - oy).powi(2);
        if apart < closest {
            closest = apart;
            best = Some(index);
        }
    }
    best
}

/// How long a finger has to stay on a Pokémon of the box before it counts as the right button of a
/// mouse. The game opens its own menu for that (Datos, Mover, Soltar, Quitar objeto...), and a screen
/// that only knows the left button had no way to reach it.
pub const BOX_HOLD: std::time::Duration = std::time::Duration::from_millis(380);

/// A finger that has landed in the box and has not yet decided what it is: a tap (a left click), a
/// drag (also a left click, from where it started) or a hold (a right click). Nothing is sent to the
/// game until it decides, which for a tap costs nothing the player can feel.
#[derive(Clone, Copy, Debug)]
pub struct BoxHold {
    pub id: i32,
    pub x: f32,
    pub y: f32,
    pub since: std::time::Instant,
    /// The right click has gone out; the rest of this touch is swallowed.
    pub fired: bool,
}

impl BoxHold {
    pub fn new(id: i32, x: f32, y: f32, now: std::time::Instant) -> Self {
        BoxHold { id, x, y, since: now, fired: false }
    }

    /// Whether the finger has been still long enough for the right click.
    pub fn due(&self, now: std::time::Instant) -> bool {
        !self.fired && now.saturating_duration_since(self.since) >= BOX_HOLD
    }

    /// Whether the finger has gone further than a hand's tremor from where it landed. [slop] is in the
    /// same unit as the coordinates (pixels).
    pub fn drifted(&self, x: f32, y: f32, slop: f32) -> bool {
        let (dx, dy) = (x - self.x, y - self.y);
        dx * dx + dy * dy > slop * slop
    }
}

/// The distance, in pixels, a finger may wander during a hold: about 3 % of the shorter side.
pub fn box_hold_slop(width: f32, height: f32) -> f32 {
    (width.min(height) * 0.03).max(10.0)
}

// --- Movement keys the game is listening to -------------------------------------------------------

/// Every key the game reads is a variable of its own, and a save file carries them all: loading a
/// game made on a PC with other keys (WASD, say) replaces the arrows the joystick presses. The
/// joystick is bound to the action, not to a key: while it holds a direction, the game's variables
/// for that direction are set to the arrow for as long as it is held, and put back afterwards.
///
/// The variables that name the key for a direction, one family per screen of the game. The second
/// player's keys are not here: they are somebody else's.
pub const DIRECTION_FAMILIES: [&str; 15] = [
    "key_ex_",
    "key_bt_mov_",
    "key_menu_mov_",
    "key_menump_mov_",
    "key_caja_mov_",
    "key_eliminatorias_mov_",
    "key_entrenador_mov_",
    "key_movil_mov_",
    "key_obj_mov_",
    "key_opciones_mov_",
    "key_p1_mov_",
    "key_pkdex_mov_",
    "key_pkmn_mov_",
    "key_prestamo_mov_",
    "key_vuelo_mov_",
];

/// How the game spells each direction in those names, in the order up, down, left, right.
pub const DIRECTION_WORDS: [&str; 4] = ["arriba", "abajo", "izquierda", "derecha"];

/// The arrow keys the joystick presses, in the same order.
pub const ARROWS: [Key; 4] = [Key::UpArrow, Key::DownArrow, Key::LeftArrow, Key::RightArrow];

/// The game's code for each of those arrows (Windows virtual-key codes).
pub const ARROW_CODES: [i32; 4] = [38, 40, 37, 39];

/// Which arrows are down right now, however they got there.
static DIRECTIONS_HELD: [AtomicBool; 4] =
    [AtomicBool::new(false), AtomicBool::new(false), AtomicBool::new(false), AtomicBool::new(false)];

/// Which direction an arrow key is, if it is one.
pub fn arrow_direction(key: Key) -> Option<usize> {
    ARROWS.iter().position(|&arrow| arrow == key)
}

/// Notes that an arrow went down or up. Anything else is ignored.
pub fn note_key(key: Key, down: bool) {
    if let Some(dir) = arrow_direction(key) {
        DIRECTIONS_HELD[dir].store(down, Ordering::Relaxed);
    }
}

/// The directions held now: up, down, left, right.
pub fn directions_held() -> [bool; 4] {
    std::array::from_fn(|dir| DIRECTIONS_HELD[dir].load(Ordering::Relaxed))
}

/// What to do with one of the game's direction variables, given what it holds and whether its
/// direction is being held. `saved` is what the variable held before it was taken over, if it was.
#[derive(Debug, PartialEq)]
pub enum Takeover {
    /// Leave it alone.
    Nothing,
    /// Set it to the arrow, remembering what it held.
    Take { original: i32 },
    /// Put back what it held.
    Restore { original: i32 },
    /// The game changed it behind our back (a load, an assignment): forget what was saved.
    Forget,
}

pub fn takeover(current: i32, arrow: i32, want: bool, saved: Option<i32>) -> Takeover {
    match (saved, want) {
        (Some(_), true) if current == arrow => Takeover::Nothing,
        (Some(original), false) if current == arrow => Takeover::Restore { original },
        (Some(_), _) => Takeover::Forget,
        (None, true) if current != arrow => Takeover::Take { original: current },
        (None, _) => Takeover::Nothing,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One test, because the held directions are global state shared by every test of the process.
    #[test]
    fn the_joystick_takes_over_the_games_direction_keys_while_held() {
        // Arrows are noted, other keys are not.
        note_key(Key::LeftArrow, true);
        note_key(Key::Z, true);
        assert_eq!(directions_held(), [false, false, true, false]);
        note_key(Key::LeftArrow, false);
        assert_eq!(directions_held(), [false; 4]);

        let left = ARROW_CODES[2];
        let a = 65;
        // A PC save with A for left: taken while the stick is held, put back after.
        assert_eq!(takeover(a, left, true, None), Takeover::Take { original: a });
        assert_eq!(takeover(left, left, true, Some(a)), Takeover::Nothing, "already taken");
        assert_eq!(takeover(left, left, false, Some(a)), Takeover::Restore { original: a });
        assert_eq!(takeover(a, left, false, None), Takeover::Nothing, "idle: not touched");
        // A game that kept its arrows is never touched.
        assert_eq!(takeover(left, left, true, None), Takeover::Nothing);
        // The game rewrote it while it was held (a save loaded, a key assigned): do not undo that.
        assert_eq!(takeover(87, left, true, Some(a)), Takeover::Forget);
        assert_eq!(takeover(87, left, false, Some(a)), Takeover::Forget);
        // Unassigned keys (-1) are taken too, so the joystick still moves a player with no key bound.
        assert_eq!(takeover(-1, left, true, None), Takeover::Take { original: -1 });
    }

    /// One test, because the layout is global state shared by every test of the process.
    #[test]
    fn custom_buttons_can_wear_an_icon() {
        for name in [
            "pause", "back", "ball", "save", "load", "forward", "up", "down", "left", "right", "star", "heart", "plus", "menu", "check", "chart",
        ] {
            assert!(icon_named(name).is_some(), "{name}");
        }
        assert!(icon_named("nope").is_none());
        assert!(icon_named(" Star ").is_some(), "names are trimmed and not case sensitive");

        load_layout(
            "custom1=0.5,0.5,1,1,Z,~star,2\ncustom2=0.2,0.2,1,1,X,ab,2\ncustom3=0.3,0.3,1,1,C,~nope,2\ncustom4=0.4,0.4,1,1,V,abcdef,2\n",
        );
        let (one, two, three, four) = (
            index_of("custom1").unwrap(),
            index_of("custom2").unwrap(),
            index_of("custom3").unwrap(),
            index_of("custom4").unwrap(),
        );
        assert!(matches!(icon_at(one), Some(Icon::Star)));
        assert!(matches!(label_of(one), Label::Text("")), "an icon button has no text on top of its picture");
        assert!(icon_at(two).is_none());
        assert!(matches!(label_of(two), Label::Text("AB")));
        assert!(icon_at(three).is_none(), "an unknown icon falls back to text");
        assert!(matches!(label_of(three), Label::Text("NOP")));
        assert!(matches!(label_of(four), Label::Text("ABC")), "text is cut to three letters");
        // The game's own buttons keep the icons they always had.
        assert!(matches!(icon_at(index_of("escape").unwrap()), Some(Icon::Back)));
        assert!(icon_at(index_of("z").unwrap()).is_none());

        load_layout("");
        assert!(icon_at(one).is_none());
    }

    #[test]
    fn custom_keys_say_how_they_act() {
        assert_eq!(parse_custom_token("z"), Some((Key::Z, CustomMode::Press)));
        assert_eq!(parse_custom_token("Z:turbo"), Some((Key::Z, CustomMode::Turbo)));
        assert_eq!(parse_custom_token("x:LOCK"), Some((Key::X, CustomMode::Lock)));
        assert_eq!(parse_custom_token("F5:lock"), Some((Key::F5, CustomMode::Lock)));
        assert_eq!(parse_custom_token("@speed"), Some((Key::Space, CustomMode::Action(SysAction::Speed))));
        assert_eq!(parse_custom_token("@types"), Some((Key::Space, CustomMode::Action(SysAction::TypeChart))));
        // Anything else is refused rather than guessed at.
        assert_eq!(parse_custom_token("Z:"), None);
        assert_eq!(parse_custom_token("Z:hold"), None);
        assert_eq!(parse_custom_token("@nothing"), None);
        assert_eq!(parse_custom_token("@"), None);
        assert_eq!(parse_custom_token("Q:turbo:lock"), None);
    }

    #[test]
    fn a_layout_carries_the_mode_of_each_custom_button() {
        load_layout(
            "custom1=0.5,0.5,1,1,X:lock,~run,2\ncustom2=0.5,0.5,1,1,Z:turbo,TUR,2\ncustom3=0.5,0.5,1,1,@types,TIP,4\n\
             custom4=0.5,0.5,1,1,Z:fast,BAD,2\ncustom5=0.5,0.5,1,1,Z,PLN,2\n",
        );
        let at = |slot: usize| CUSTOM.lock().unwrap()[slot - 1];
        assert_eq!(at(1).map(|d| (d.key, d.mode)), Some((Key::X, CustomMode::Lock)));
        assert_eq!(at(2).map(|d| (d.key, d.mode)), Some((Key::Z, CustomMode::Turbo)));
        assert_eq!(at(3).map(|d| d.mode), Some(CustomMode::Action(SysAction::TypeChart)));
        assert!(at(4).is_none(), "an unknown way of acting makes no button");
        assert_eq!(at(5).map(|d| d.mode), Some(CustomMode::Press));
        let index = index_of("custom3").unwrap();
        assert_eq!(sys_action(index), Some(SysAction::TypeChart));
        assert_eq!(sys_action(index_of("custom5").unwrap()), None);
        assert_eq!(custom_mode(index_of("z").unwrap()), None);
        assert!(lock_valid(index_of("custom1").unwrap(), Key::X));
        assert!(!lock_valid(index_of("custom1").unwrap(), Key::Z));
        assert!(!lock_valid(index_of("custom2").unwrap(), Key::Z));
        load_layout("");
    }

    #[test]
    fn the_table_of_types_follows_its_screen() {
        assert!(!type_chart_open());
        open_type_chart();
        assert!(type_chart_open());
        // Nothing opened a screen in this test, so whatever the game is on now is what it was opened over.
        type_chart_follow();
        assert!(type_chart_open());
        close_type_chart();
        assert!(!type_chart_open());
    }

    /// Which of the built-in buttons a screen shows, by name.
    fn shown_on(context: u16) -> Vec<&'static str> {
        CONTROLS
            .iter()
            .enumerate()
            .filter(|(index, _)| contexts_of(*index) & context != 0)
            .map(|(_, control)| control.name)
            .collect()
    }

    #[test]
    fn the_menus_show_only_the_buttons_they_need() {
        // The bag, the team, the map: the stick, Z, X and C. Nothing of the overworld.
        for room in [32, 4, 21, 17, 516] {
            assert_eq!(screen_of(room), Some(SCREEN_C), "room {room}");
        }
        assert_eq!(shown_on(SCREEN_C), ["z", "x", "c"]);
        // The sub-screens and the plain menus have no use for C.
        for room in [5, 6, 7, 31, 44, 212, 436] {
            assert_eq!(screen_of(room), Some(SCREEN), "room {room}");
        }
        assert_eq!(shown_on(SCREEN), ["z", "x"]);
        // The overworld, a battle, a title screen and the Safari are not menus.
        for room in [0, 2, 47, 191, 715, 22, 465, 8] {
            assert_eq!(screen_of(room), None, "room {room}");
        }
    }

    #[test]
    fn system_actions_are_found_by_name() {
        assert_eq!(sys_action_named(SYS_QSAVE), Some(SysAction::QuickSave));
        assert_eq!(sys_action_named("z"), None);
    }

    #[test]
    fn buttons_wear_the_picture_of_their_job_in_each_state() {
        set_function_icons(true);
        // The main four are actions out of battle and attacks in it, where they keep their letters.
        assert!(matches!(icon_for("v", WORLD), Some(Icon::Bike)));
        assert!(icon_for("v", BATTLE).is_none());
        // Z and X stay Z and X everywhere: they are the buttons the player presses most.
        assert!(icon_for("z", WORLD).is_none());
        assert!(icon_for("x", WORLD).is_none());
        assert!(matches!(icon_for("m", WORLD), Some(Icon::Map)));
        assert!(matches!(icon_for("m", BATTLE), Some(Icon::Mega)));
        assert!(matches!(icon_for("e", WORLD), Some(Icon::Smile)));
        assert!(matches!(icon_for("e", SAFARI), Some(Icon::Exit)));
        assert!(matches!(icon_for("t", BATTLE), Some(Icon::Clock)));
        assert!(matches!(icon_for("o", WORLD), Some(Icon::Bag)));
        assert!(icon_for("alpha1", WORLD).is_none(), "numbers stay numbers");
        // Switched off, only the icons the buttons always had remain.
        set_function_icons(false);
        assert!(icon_for("o", WORLD).is_none());
        assert!(matches!(icon_for("return", WORLD), Some(Icon::Menu)));
        assert!(matches!(icon_for("b", BATTLE), Some(Icon::Ball)));
        set_function_icons(true);
    }

    #[test]
    fn a_hold_is_due_only_after_the_time_and_only_once() {
        use std::time::{Duration, Instant};
        let t0 = Instant::now();
        let mut hold = BoxHold::new(1, 100.0, 100.0, t0);
        assert!(!hold.due(t0));
        assert!(!hold.due(t0 + BOX_HOLD - Duration::from_millis(1)));
        assert!(hold.due(t0 + BOX_HOLD));
        hold.fired = true;
        assert!(!hold.due(t0 + BOX_HOLD * 3));
    }

    #[test]
    fn a_hold_tolerates_a_tremor_but_not_a_drag() {
        let hold = BoxHold::new(1, 100.0, 100.0, std::time::Instant::now());
        let slop = box_hold_slop(2400.0, 1080.0);
        assert!((slop - 32.4).abs() < 0.1);
        assert!(!hold.drifted(110.0, 105.0, slop));
        assert!(hold.drifted(100.0, 140.0, slop));
        assert!(hold.drifted(60.0, 100.0, slop));
        // A very small window still allows some tremor.
        assert!(box_hold_slop(100.0, 100.0) >= 10.0);
    }
}
