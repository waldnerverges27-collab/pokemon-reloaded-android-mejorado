//! Vuelca el GML de scripts y eventos de un juego GM8 a un fichero de texto, para poder buscar en el.
//! Uso: cargo run --release -p gm8exe --example dumpgml -- <juego.exe> <salida.txt>
use std::io::Write;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("uso: dumpgml <juego.exe> <salida.txt>");
    let out = args.next().expect("uso: dumpgml <juego.exe> <salida.txt>");
    let mut exe = std::fs::read(&path).expect("no se pudo leer el exe");
    let assets = gm8exe::reader::from_exe(&mut exe[..], None::<fn(&str)>, false, true).expect("exe invalido");
    let mut file = std::io::BufWriter::new(std::fs::File::create(out).unwrap());
    let text = |s: &gm8exe::asset::PascalString| String::from_utf8_lossy(&s.0).into_owned();
    for s in assets.scripts.iter().flatten() {
        writeln!(file, "### SCRIPT {}\n{}\n", text(&s.name), text(&s.source)).unwrap();
    }
    for o in assets.objects.iter().flatten() {
        for (kind, events) in o.events.iter().enumerate() {
            for (sub, actions) in events {
                for a in actions {
                    for i in 0..a.param_count {
                        let t = text(&a.param_strings[i]);
                        if !t.is_empty() {
                            writeln!(file, "### OBJECT {} event {}/{} action {}\n{}\n", text(&o.name), kind, sub, a.id, t).unwrap();
                        }
                    }
                }
            }
        }
    }
}
