//! Lista los sprites de un juego GM8 y, si se pide, vuelca los fotogramas como RGBA crudo.
//! Uso:
//!   cargo run --release -p gm8exe --example dumpsprites -- <juego.exe>                  (lista)
//!   cargo run --release -p gm8exe --example dumpsprites -- <juego.exe> <filtro> <dir>   (vuelca los que contengan <filtro>)
//! Cada volcado es `<dir>/<nombre>_<fotograma>_<ancho>x<alto>.rgba`.
fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("uso: dumpsprites <juego.exe> [filtro dir]");
    let filter = args.next();
    let dir = args.next();
    let mut exe = std::fs::read(&path).expect("no se pudo leer el exe");
    let assets = gm8exe::reader::from_exe(&mut exe[..], None::<fn(&str)>, false, true).expect("exe invalido");
    if let Some(dir) = &dir {
        std::fs::create_dir_all(dir).unwrap();
    }
    for sprite in assets.sprites.iter().flatten() {
        let name = String::from_utf8_lossy(&sprite.name.0).into_owned();
        let (w, h) = sprite.frames.first().map(|f| (f.width, f.height)).unwrap_or((0, 0));
        match (&filter, &dir) {
            (Some(f), Some(dir)) => {
                if name.to_lowercase().contains(&f.to_lowercase()) {
                    for (i, frame) in sprite.frames.iter().enumerate() {
                        let file = format!("{dir}/{name}_{i}_{}x{}.rgba", frame.width, frame.height);
                        std::fs::write(file, &*frame.data).unwrap();
                    }
                    println!("{name}: {} fotogramas {w}x{h}", sprite.frames.len());
                }
            },
            _ => println!("{name}\t{}\t{w}x{h}", sprite.frames.len()),
        }
    }
}
