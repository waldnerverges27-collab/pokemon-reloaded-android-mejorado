//! Cuenta la velocidad (room_speed) de cada room de un juego GM8, y el tamano de la vista/room.
//! Uso: cargo run --release -p gm8exe --example roomspeed -- <juego.exe>
use std::collections::BTreeMap;

fn main() {
    let path = std::env::args().nth(1).expect("uso: roomspeed <juego.exe>");
    let mut exe = std::fs::read(&path).expect("no se pudo leer el exe");
    let assets = gm8exe::reader::from_exe(&mut exe[..], None::<fn(&str)>, false, true).expect("exe invalido");
    let mut by_speed: BTreeMap<u32, Vec<String>> = BTreeMap::new();
    let mut total = 0;
    for r in assets.rooms.iter().flatten() {
        total += 1;
        by_speed.entry(r.speed).or_default().push(String::from_utf8_lossy(&r.name.0).into_owned());
    }
    println!("rooms: {total}");
    for (speed, rooms) in &by_speed {
        println!("room_speed {speed}: {} rooms, p.ej. {}", rooms.len(), rooms.iter().take(4).cloned().collect::<Vec<_>>().join(", "));
    }
    if let Some(first) = assets.rooms.iter().flatten().next() {
        println!("primera room: {} {}x{} speed {}", String::from_utf8_lossy(&first.name.0), first.width, first.height, first.speed);
    }
    println!("vsync: {}  freq: {}", assets.settings.vsync, assets.settings.frequency);
}
