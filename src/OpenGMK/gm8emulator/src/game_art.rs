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
    (Icon::Save, "spr_save", 0),
    (Icon::Box, "spr_regalo", 0),
    (Icon::Team, "IconosPokemon", 1),
    (Icon::Person, "spr_MedallaKanto", 3),
    (Icon::Magnifier, "spr_buscarPokedex", 0),
    (Icon::Clock, "spr_clock", 0),
];

/// Pictures that are not sprites but part of a background of the game, cut out of it: (icon,
/// background, left, top, width, height, the backdrop colours to make transparent). The backpack is
/// painted on the bag screen and exists nowhere else.
const CUTOUTS: &[(Icon, &str, u32, u32, u32, u32, &[[u8; 3]])] =
    &[(Icon::Bag, "MenuMochila", 24, 40, 60, 60, &[[65, 176, 160], [53, 145, 131]])];

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
    (Icon::Chart, include_bytes!("art/chart.png")),
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

/// Whether a background of the game has a picture cut out of it.
pub fn wants_background(name: &[u8]) -> bool {
    CUTOUTS.iter().any(|c| c.1.as_bytes() == name)
}

/// Cuts the wanted pictures out of a background (`data` is its raw BGRA pixels).
pub fn store_background(name: &[u8], width: u32, height: u32, data: &[u8]) {
    for &(icon, bg, left, top, w, h, keys) in CUTOUTS {
        if bg.as_bytes() != name || data.len() != (width * height * 4) as usize || left + w > width || top + h > height {
            continue
        }
        let mut rgba = Vec::with_capacity((w * h * 4) as usize);
        for y in top..top + h {
            for x in left..left + w {
                let i = ((y * width + x) * 4) as usize;
                let (b, g, r) = (data[i], data[i + 1], data[i + 2]);
                let backdrop = keys.iter().any(|k| *k == [r, g, b]);
                rgba.extend_from_slice(&[r, g, b, if backdrop { 0 } else { 255 }]);
            }
        }
        store(icon, w, h, &rgba);
    }
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
    let picture = finish(picture);
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

/// Makes a picture borrowed from the game read well over a button: bigger ones are halved so every
/// pixel ends up a whole number of screen pixels, the colours get a little more life, and a picture
/// whose edge is light gets the thin dark outline the game's own art has.
fn finish(mut p: Picture) -> Picture {
    while p.width.max(p.height) > 40 {
        p = halve(&p);
    }
    for px in p.rgba.chunks_exact_mut(4) {
        if px[3] == 0 {
            continue
        }
        let mean = (u32::from(px[0]) + u32::from(px[1]) + u32::from(px[2])) as f32 / 3.0;
        for c in &mut px[..3] {
            *c = (mean + (f32::from(*c) - mean) * 1.18).clamp(0.0, 255.0) as u8;
        }
    }
    let (w, h) = (p.width as usize, p.height as usize);
    let opaque = |x: isize, y: isize| x >= 0 && y >= 0 && (x as usize) < w && (y as usize) < h && p.rgba[(y as usize * w + x as usize) * 4 + 3] > 8;
    let (mut sum, mut count) = (0.0f32, 0.0f32);
    for y in 0..h as isize {
        for x in 0..w as isize {
            if opaque(x, y) && [(1, 0), (-1, 0), (0, 1), (0, -1)].iter().any(|&(dx, dy)| !opaque(x + dx, y + dy)) {
                let i = (y as usize * w + x as usize) * 4;
                sum += 0.3 * f32::from(p.rgba[i]) + 0.59 * f32::from(p.rgba[i + 1]) + 0.11 * f32::from(p.rgba[i + 2]);
                count += 1.0;
            }
        }
    }
    if count == 0.0 || sum / count < 70.0 {
        return p
    }
    // One transparent pixel all round, then the outline in the pixels that touch the picture.
    let (nw, nh) = (w + 2, h + 2);
    let mut out = vec![0u8; nw * nh * 4];
    for y in 0..h {
        for x in 0..w {
            let (src, dst) = ((y * w + x) * 4, ((y + 1) * nw + x + 1) * 4);
            out[dst..dst + 4].copy_from_slice(&p.rgba[src..src + 4]);
        }
    }
    for y in 0..nh as isize {
        for x in 0..nw as isize {
            if opaque(x - 1, y - 1) {
                continue
            }
            if [(1, 0), (-1, 0), (0, 1), (0, -1)].iter().any(|&(dx, dy)| opaque(x - 1 + dx, y - 1 + dy)) {
                let i = (y as usize * nw + x as usize) * 4;
                out[i..i + 4].copy_from_slice(&[34, 30, 52, 255]);
            }
        }
    }
    Picture { icon: p.icon, width: nw as u32, height: nh as u32, rgba: out }
}

/// A picture at half the size, each pixel the average of four (weighted by how opaque they are).
fn halve(p: &Picture) -> Picture {
    let (w, h) = (p.width as usize, p.height as usize);
    let (nw, nh) = ((w + 1) / 2, (h + 1) / 2);
    let mut rgba = vec![0u8; nw * nh * 4];
    for y in 0..nh {
        for x in 0..nw {
            let (mut acc, mut alpha, mut n) = ([0f32; 3], 0f32, 0f32);
            for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
                let (sx, sy) = (x * 2 + dx, y * 2 + dy);
                n += 1.0;
                if sx < w && sy < h {
                    let i = (sy * w + sx) * 4;
                    let a = f32::from(p.rgba[i + 3]);
                    for c in 0..3 {
                        acc[c] += f32::from(p.rgba[i + c]) * a;
                    }
                    alpha += a;
                }
            }
            let i = (y * nw + x) * 4;
            if alpha > 0.0 {
                for c in 0..3 {
                    rgba[i + c] = (acc[c] / alpha) as u8;
                }
                // A pixel that is mostly empty stays empty, so the outline does not thicken.
                rgba[i + 3] = if alpha / n >= 128.0 { 255 } else { 0 };
            }
        }
    }
    Picture { icon: p.icon, width: nw as u32, height: nh as u32, rgba }
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
        assert_eq!((acts[0].width, acts[0].height), (1, 1), "the game's own picture wins");
    }

    #[test]
    fn a_light_edge_gets_an_outline_and_a_dark_one_does_not() {
        // 6x6 light square: it ends up 8x8 with a dark ring.
        let light: Vec<u8> = (0..36).flat_map(|_| [240, 240, 240, 255]).collect();
        let p = finish(Picture { icon: Icon::Bike, width: 6, height: 6, rgba: light });
        assert_eq!((p.width, p.height), (8, 8));
        assert_eq!(&p.rgba[..4], &[0, 0, 0, 0], "the corners stay empty");
        assert_eq!(&p.rgba[4..8], &[34, 30, 52, 255], "the pixel above the square is the outline");
        // A square that already has a dark border is left as it is.
        let dark: Vec<u8> = (0..36).flat_map(|_| [30, 30, 40, 255]).collect();
        let p = finish(Picture { icon: Icon::Bike, width: 6, height: 6, rgba: dark });
        assert_eq!((p.width, p.height), (6, 6));
    }

    #[test]
    fn big_pictures_are_halved_and_cutouts_drop_their_backdrop() {
        let p = Picture { icon: Icon::Bag, width: 56, height: 56, rgba: vec![200; 56 * 56 * 4] };
        assert_eq!(finish(p).width.max(1), 28 + 2, "halved once, then outlined");
        // A background of 8x8 BGRA pixels with the teal backdrop everywhere except one red pixel.
        let mut bg = Vec::new();
        for i in 0..64 {
            bg.extend_from_slice(if i == 9 { &[10, 20, 200, 255] } else { &[160, 176, 65, 255] });
        }
        assert!(wants_background(b"MenuMochila"));
        // The cut-out rectangle is bigger than this background, so nothing is stored.
        let before = version();
        store_background(b"MenuMochila", 8, 8, &bg);
        assert_eq!(version(), before);
    }

    #[test]
    fn every_source_is_looked_up_by_its_sprite() {
        assert_eq!(wanted(b"Spr_Objeto").len(), 6);
        assert_eq!(wanted(b"spr_save"), vec![(Icon::Save, 0)]);
        assert_eq!(wanted(b"spr_clock"), vec![(Icon::Clock, 0)]);
        assert!(wanted(b"nada").is_empty());
    }
}
