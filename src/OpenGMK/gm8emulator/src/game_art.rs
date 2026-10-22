//! Pictures the on-screen buttons borrow from the game itself.
//!
//! The game already has the art of the things its buttons stand for: the bicycle, the fishing rod,
//! the map, the Mega Ring, the clock of the battle timer, the Pokédex magnifier. Instead of drawing
//! a lookalike, the overlay shows those very sprites.
//!
//! Nothing of the game is shipped with the app: when the game's sprites are read at start-up, the few
//! frames wanted here are copied out of memory (cropped to their visible part) and kept for the
//! renderer, which puts them in a small texture of its own. A game without a given sprite simply
//! leaves that button with its drawn icon.

use crate::touch_controls::Icon;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Mutex,
};

/// Which sprite frame stands for which icon: (icon, sprite name, frame). The big item pictures
/// (`Spr_Objeto`, 28x27) are indexed by item number, which is how the frames were found.
pub const SOURCES: &[(Icon, &str, usize)] = &[
    (Icon::Back, "Spr_Objeto", 0),
    (Icon::Ball, "Spr_Objeto", 1),
    (Icon::Hook, "Spr_Objeto", 182),
    (Icon::Bike, "Spr_Objeto", 184),
    (Icon::Map, "Spr_Objeto", 186),
    (Icon::Mega, "Spr_Objeto", 193),
    (Icon::Bolt, "Spr_ObjetoMT", 2),
    (Icon::Bag, "spr_megaSack", 0),
    (Icon::Team, "IconosPokemon", 1),
    (Icon::Person, "spr_MedallaKanto", 3),
    (Icon::Magnifier, "spr_buscarPokedex", 0),
    (Icon::Clock, "spr_clock", 0),
];

/// Pictures of our own, painted in the game's pixel-art style (a dark outline, light on top and left,
/// dark underneath) for the buttons whose job the game has no sprite for. They are only used where
/// the game gave none. The files are in `art/`, 24x24.
const BUILTIN: &[(Icon, &[u8])] = &[
    (Icon::Act, include_bytes!("art/act.png")),
    (Icon::Run, include_bytes!("art/run.png")),
    (Icon::Exit, include_bytes!("art/exit.png")),
    (Icon::Smile, include_bytes!("art/smile.png")),
    (Icon::Chat, include_bytes!("art/chat.png")),
    (Icon::Box, include_bytes!("art/box.png")),
    (Icon::Swap, include_bytes!("art/swap.png")),
    (Icon::Menu, include_bytes!("art/menu.png")),
    (Icon::Save, include_bytes!("art/save.png")),
    (Icon::Load, include_bytes!("art/load.png")),
    (Icon::Forward, include_bytes!("art/forward.png")),
];

/// One picture, cropped to the pixels that are not transparent.
#[derive(Clone)]
pub struct Picture {
    pub icon: Icon,
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

static STORE: Mutex<Vec<Picture>> = Mutex::new(Vec::new());
static VERSION: AtomicU64 = AtomicU64::new(0);

/// The frames of this sprite that are wanted, as (icon, frame).
pub fn wanted(sprite: &[u8]) -> Vec<(Icon, usize)> {
    SOURCES.iter().filter(|(_, name, _)| name.as_bytes() == sprite).map(|&(icon, _, frame)| (icon, frame)).collect()
}

/// Keeps one frame (straight RGBA, `width * height * 4` bytes) as the picture of an icon.
pub fn store(icon: Icon, width: u32, height: u32, data: &[u8]) {
    // The magnifier was drawn on a flat backdrop of its own: that colour is not part of the picture.
    let keyed;
    let data = if icon == Icon::Magnifier && data.len() >= 4 {
        let key = [data[0], data[1], data[2]];
        keyed = data
            .chunks_exact(4)
            .flat_map(|px| if px[..3] == key { [0, 0, 0, 0] } else { [px[0], px[1], px[2], px[3]] })
            .collect::<Vec<u8>>();
        &keyed[..]
    } else {
        data
    };
    let Some(picture) = crop(icon, width, height, data) else { return };
    let mut all = STORE.lock().unwrap_or_else(|e| e.into_inner());
    all.retain(|p| p.icon != icon);
    all.push(picture);
    VERSION.fetch_add(1, Ordering::Release);
}

/// Our own pictures, decoded once.
fn builtin() -> &'static [Picture] {
    static ALL: std::sync::OnceLock<Vec<Picture>> = std::sync::OnceLock::new();
    ALL.get_or_init(|| {
        BUILTIN
            .iter()
            .filter_map(|&(icon, bytes)| {
                let image = image::load_from_memory(bytes).ok()?.to_rgba8();
                crop(icon, image.width(), image.height(), image.as_raw())
            })
            .collect()
    })
}

/// Every picture there is: the ones taken from the game, and ours for the icons it gave none.
pub fn pictures() -> Vec<Picture> {
    let mut all = STORE.lock().unwrap_or_else(|e| e.into_inner()).clone();
    for own in builtin() {
        if !all.iter().any(|p| p.icon == own.icon) {
            all.push(own.clone());
        }
    }
    all
}

/// Changes every time a picture is stored, so the renderer knows when to rebuild its texture.
pub fn version() -> u64 {
    VERSION.load(Ordering::Acquire)
}

/// Cuts away the transparent border, so the picture fills the space it is given. None when the
/// frame is empty or its size does not match its data.
fn crop(icon: Icon, width: u32, height: u32, data: &[u8]) -> Option<Picture> {
    let (w, h) = (width as usize, height as usize);
    if w == 0 || h == 0 || data.len() != w * h * 4 {
        return None
    }
    let (mut x0, mut y0, mut x1, mut y1) = (w, h, 0usize, 0usize);
    for y in 0..h {
        for x in 0..w {
            if data[(y * w + x) * 4 + 3] > 8 {
                x0 = x0.min(x);
                y0 = y0.min(y);
                x1 = x1.max(x + 1);
                y1 = y1.max(y + 1);
            }
        }
    }
    if x1 <= x0 || y1 <= y0 {
        return None
    }
    let (cw, ch) = (x1 - x0, y1 - y0);
    let mut rgba = Vec::with_capacity(cw * ch * 4);
    for y in y0..y1 {
        rgba.extend_from_slice(&data[(y * w + x0) * 4..(y * w + x1) * 4]);
    }
    Some(Picture { icon, width: cw as u32, height: ch as u32, rgba })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crop_keeps_only_the_visible_part() {
        // 4x3 frame with a single opaque pixel at (2, 1).
        let mut data = vec![0u8; 4 * 3 * 4];
        data[(1 * 4 + 2) * 4..(1 * 4 + 2) * 4 + 4].copy_from_slice(&[9, 8, 7, 255]);
        let p = crop(Icon::Bike, 4, 3, &data).unwrap();
        assert_eq!((p.width, p.height), (1, 1));
        assert_eq!(p.rgba, vec![9, 8, 7, 255]);
        assert!(crop(Icon::Bike, 4, 3, &vec![0u8; 48]).is_none(), "an empty frame is no picture");
        assert!(crop(Icon::Bike, 4, 3, &[0u8; 5]).is_none(), "a size that does not match is refused");
    }

    #[test]
    fn our_own_pictures_decode_and_never_replace_the_games() {
        assert_eq!(builtin().len(), BUILTIN.len(), "every embedded picture must decode");
        assert!(builtin().iter().all(|p| p.width <= 24 && p.height <= 24 && p.width >= 12));
        // Before the game gives anything, ours are all there is.
        assert!(pictures().iter().any(|p| p.icon == Icon::Act));
        store(Icon::Act, 1, 1, &[1, 2, 3, 255]);
        let acts: Vec<_> = pictures().into_iter().filter(|p| p.icon == Icon::Act).collect();
        assert_eq!(acts.len(), 1);
        assert_eq!(acts[0].rgba, vec![1, 2, 3, 255], "the game's own picture wins");
    }

    #[test]
    fn every_source_is_looked_up_by_its_sprite() {
        assert_eq!(wanted(b"Spr_Objeto").len(), 6);
        assert_eq!(wanted(b"spr_clock"), vec![(Icon::Clock, 0)]);
        assert!(wanted(b"nada").is_empty());
    }
}
