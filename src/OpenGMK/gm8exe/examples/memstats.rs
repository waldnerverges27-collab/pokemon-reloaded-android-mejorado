//! Estadisticas de recursos de un juego GM8: cuanta memoria ocupan sprites, colliders, fondos y sonidos,
//! y cuanto se podria ahorrar recortando bordes transparentes o deduplicando fotogramas iguales.
//! Uso: cargo run --release -p gm8exe --example memstats -- <juego.exe>
use std::{
    collections::{hash_map::DefaultHasher, HashMap},
    hash::{Hash, Hasher},
};

fn mb(b: u64) -> f64 {
    b as f64 / 1_048_576.0
}

fn main() {
    let path = std::env::args().nth(1).expect("uso: memstats <juego.exe>");
    let mut exe = std::fs::read(&path).expect("no se pudo leer el exe");
    let assets = gm8exe::reader::from_exe(&mut exe[..], None::<fn(&str)>, false, true).expect("exe invalido");

    let (mut frames, mut sprite_px, mut trimmed_px, mut transparent_frames) = (0u64, 0u64, 0u64, 0u64);
    let mut unique: HashMap<u64, u64> = HashMap::new(); // hash -> bytes
    let mut dup_frames = 0u64;
    let (mut coll_n, mut coll_bytes, mut per_frame_sprites) = (0u64, 0u64, 0u64);
    let mut biggest: Vec<(u64, String, u32, u32, usize)> = Vec::new();
    let mut sprite_count = 0u64;
    for s in assets.sprites.iter().flatten() {
        sprite_count += 1;
        if s.per_frame_colliders {
            per_frame_sprites += 1;
        }
        let mut sprite_bytes = 0u64;
        let (mut w0, mut h0) = (0, 0);
        for f in &s.frames {
            frames += 1;
            let (w, h) = (f.width as usize, f.height as usize);
            w0 = f.width;
            h0 = f.height;
            let px = (w * h) as u64;
            sprite_px += px;
            sprite_bytes += px * 4;
            // caja de pixeles con alfa != 0
            let (mut x0, mut y0, mut x1, mut y1) = (w, h, 0usize, 0usize);
            let mut any = false;
            for y in 0..h {
                for x in 0..w {
                    if f.data[(y * w + x) * 4 + 3] != 0 {
                        any = true;
                        x0 = x0.min(x);
                        y0 = y0.min(y);
                        x1 = x1.max(x);
                        y1 = y1.max(y);
                    }
                }
            }
            if any {
                trimmed_px += ((x1 - x0 + 1) * (y1 - y0 + 1)) as u64;
            } else {
                transparent_frames += 1;
            }
            let mut hs = DefaultHasher::new();
            (f.width, f.height).hash(&mut hs);
            f.data.hash(&mut hs);
            let k = hs.finish();
            if unique.insert(k, px * 4).is_some() {
                dup_frames += 1;
            }
        }
        biggest.push((sprite_bytes, String::from_utf8_lossy(s.name.0.as_ref()).into_owned(), w0, h0, s.frames.len()));
        for c in &s.colliders {
            coll_n += 1;
            coll_bytes += c.width as u64 * c.height as u64;
        }
    }
    biggest.sort_by(|a, b| b.0.cmp(&a.0));

    let (mut bg_n, mut bg_bytes) = (0u64, 0u64);
    for b in assets.backgrounds.iter().flatten() {
        if let Some(d) = &b.data {
            bg_n += 1;
            bg_bytes += d.len() as u64;
        }
    }
    let mut snd: HashMap<String, (u64, u64)> = HashMap::new();
    for s in assets.sounds.iter().flatten() {
        if let Some(d) = &s.data {
            let e = snd.entry(String::from_utf8_lossy(&s.extension.0).to_lowercase()).or_default();
            e.0 += 1;
            e.1 += d.len() as u64;
        }
    }
    let unique_bytes: u64 = unique.values().sum();

    println!("== SPRITES: {} sprites, {} fotogramas", sprite_count, frames);
    println!("   pixeles RGBA: {:.1} MB ({} px)", mb(sprite_px * 4), sprite_px);
    println!(
        "   recortando bordes transparentes: {:.1} MB  (ahorro {:.0}%)",
        mb(trimmed_px * 4),
        100.0 * (1.0 - trimmed_px as f64 / sprite_px as f64)
    );
    println!("   fotogramas totalmente transparentes: {}", transparent_frames);
    println!(
        "   fotogramas duplicados exactos: {} ({:.1}% ) -> unicos {:.1} MB (ahorro {:.0}%)",
        dup_frames,
        100.0 * dup_frames as f64 / frames as f64,
        mb(unique_bytes),
        100.0 * (1.0 - unique_bytes as f64 / (sprite_px * 4) as f64)
    );
    println!("== COLLIDERS (1 byte/pixel como bool): {} mapas, {:.1} MB  (como bits: {:.1} MB); sprites con mapa por fotograma: {}", coll_n, mb(coll_bytes), mb(coll_bytes / 8), per_frame_sprites);
    println!("== FONDOS: {} con datos, {:.1} MB", bg_n, mb(bg_bytes));
    for (k, (n, b)) in &snd {
        println!("== SONIDOS {:>5}: {} ficheros, {:.1} MB", k, n, mb(*b));
    }
    println!("== 10 sprites mas pesados:");
    for (b, name, w, h, n) in biggest.iter().take(10) {
        println!("   {:>7.2} MB  {}  ({}x{}, {} fotogramas)", mb(*b), name, w, h, n);
    }
}
