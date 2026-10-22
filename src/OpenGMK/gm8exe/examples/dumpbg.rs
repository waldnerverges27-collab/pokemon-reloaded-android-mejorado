//! Lista los fondos de un juego GM8 y, si se pide, vuelca los que contengan un filtro como BGRA crudo.
//! Uso: dumpbg <juego.exe> [filtro dir]
fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("uso: dumpbg <juego.exe> [filtro dir]");
    let filter = args.next();
    let dir = args.next();
    let mut exe = std::fs::read(&path).expect("no se pudo leer el exe");
    let assets = gm8exe::reader::from_exe(&mut exe[..], None::<fn(&str)>, false, true).expect("exe invalido");
    if let Some(dir) = &dir {
        std::fs::create_dir_all(dir).unwrap();
    }
    for bg in assets.backgrounds.iter().flatten() {
        let name = String::from_utf8_lossy(&bg.name.0).into_owned();
        match (&filter, &dir, &bg.data) {
            (Some(f), Some(dir), Some(data)) if name.to_lowercase().contains(&f.to_lowercase()) => {
                std::fs::write(format!("{dir}/{name}_{}x{}.bgra", bg.width, bg.height), &**data).unwrap();
                println!("{name}\t{}x{}", bg.width, bg.height);
            },
            (None, _, _) | (_, None, _) => println!("{name}\t{}x{}", bg.width, bg.height),
            _ => {},
        }
    }
}
