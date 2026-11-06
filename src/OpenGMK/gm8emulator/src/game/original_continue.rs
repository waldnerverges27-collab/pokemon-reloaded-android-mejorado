//! Continue an original Pokémon Reloaded save without showing the intro and the two menus.
//!
//! This is not a savestate. After the ordinary constructors have run, it enters the original main
//! menu and loader rooms, then invokes `LoaderControlSlotActivate` in the real `LoaderControl`
//! context. That is the same routine the game runs after the player confirms a slot, and it calls
//! `LoadFileSav` with all of the game's own validation and migration logic.

use super::{Game, SceneChange};
use crate::gml::{ev, Context};
use std::{
    path::PathBuf,
    sync::atomic::{AtomicI32, Ordering},
};

static REQUESTED_SLOT: AtomicI32 = AtomicI32::new(-1);

fn parse_request(text: &str) -> Option<i32> {
    text.lines().find_map(|line| {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            return None
        }
        let (name, value) = line.split_once('=')?;
        if name.trim() != "slot" {
            return None
        }
        value.trim().parse::<i32>().ok().filter(|slot| (1..=99).contains(slot))
    })
}

/// Applies the one-shot file written by the launcher. Missing or empty means an ordinary start.
pub fn configure(text: &str) {
    let slot = parse_request(text);
    REQUESTED_SLOT.store(slot.unwrap_or(-1), Ordering::SeqCst);
    if slot.is_none() && text.lines().any(|line| {
        let line = line.trim();
        !line.is_empty() && !line.starts_with('#')
    }) {
        eprintln!("[continuar] solicitud invalida; arranque normal");
    }
}

pub fn requested() -> bool {
    (1..=99).contains(&REQUESTED_SLOT.load(Ordering::SeqCst))
}

fn take_request() -> Option<i32> {
    let slot = REQUESTED_SLOT.swap(-1, Ordering::SeqCst);
    (1..=99).contains(&slot).then_some(slot)
}

fn object_id(game: &Game, name: &[u8]) -> Option<i32> {
    game.assets.objects.iter().position(|object| {
        object.as_ref().is_some_and(|object| {
            let have: &[u8] = object.name.as_ref();
            have == name
        })
    }).map(|id| id as i32)
}

fn script_exists(game: &Game, name: &[u8]) -> bool {
    game.assets.scripts.iter().flatten().any(|script| {
        let have: &[u8] = script.name.as_ref();
        have == name
    })
}

fn room_containing(game: &Game, object_id: i32) -> Option<i32> {
    game.assets.rooms.iter().position(|room| {
        room.as_ref().is_some_and(|room| room.instances.iter().any(|instance| instance.object == object_id))
    }).map(|id| id as i32)
}

fn refuse(reason: &str) -> Result<(), Box<dyn std::error::Error>> {
    eprintln!("[continuar] {reason}; arranque normal");
    crate::android_main::toast("No se pudo preparar la carga directa; se abrira el inicio normal");
    Ok(())
}

/// Runs before the first frame. Any failed preflight leaves the initial room untouched.
pub fn run(game: &mut Game) -> Result<(), Box<dyn std::error::Error>> {
    let Some(slot) = take_request() else { return Ok(()) };
    let save = PathBuf::from(format!(
        "/data/data/{}/files/game/data/partidas/reloaded{slot}.savc",
        crate::android_main::PACKAGE_DIR,
    ));
    if !save.is_file() {
        return refuse(&format!("no existe la partida original de la ranura {slot}"))
    }

    let Some(menu_object) = object_id(game, b"InicioControl") else {
        return refuse("este juego no tiene InicioControl")
    };
    let Some(loader_object) = object_id(game, b"LoaderControl") else {
        return refuse("este juego no tiene LoaderControl")
    };
    let Some(menu_room) = room_containing(game, menu_object) else {
        return refuse("no se encontro la sala del menu original")
    };
    let Some(loader_room) = room_containing(game, loader_object) else {
        return refuse("no se encontro la sala del cargador original")
    };
    if !script_exists(game, b"LoaderControlSlotActivate") || !script_exists(game, b"LoadFileSav") {
        return refuse("faltan las rutinas originales de carga")
    }

    // Compile before changing rooms: an unsupported game still gets its completely normal start.
    let source = format!("GOING={slot};LoaderControlSlotActivate();");
    let instructions = match game.compiler.compile(source.as_bytes()) {
        Ok(instructions) => instructions,
        Err(error) => return refuse(&format!("no se pudo preparar el cargador: {error}")),
    };

    let initial_room = game.room.id;
    eprintln!(
        "[continuar] ranura {slot}: constructores -> menu {menu_room} -> cargador {loader_room}"
    );
    // Run all Create events synchronously, but do not even draw the intermediate menu frames.
    // Restore the renderer flags before LoadFileSav so the destination room behaves normally.
    let auto_draw = std::mem::replace(&mut game.auto_draw, false);
    let transition_kind = std::mem::replace(&mut game.transition_kind, 0);
    let rooms_result = game.load_room(menu_room).and_then(|()| game.load_room(loader_room));
    game.auto_draw = auto_draw;
    game.transition_kind = transition_kind;
    rooms_result?;

    let Some(loader) = game.room.instance_list.iter_by_identity(loader_object).next(&game.room.instance_list) else {
        return Err("LoaderControl no aparecio en la sala del cargador".into())
    };
    let mut context = Context::with_single_instance(loader);
    // The original invokes this script from LoaderControl's alarm 2.
    context.event_object = loader_object;
    context.event_type = ev::ALARMS;
    context.event_number = 2;
    game.execute(&instructions, &mut context)?;

    let destination = match game.scene_change.as_ref() {
        Some(SceneChange::Room(room))
            if *room != initial_room && *room != menu_room && *room != loader_room => Some(*room),
        _ => None,
    };
    if let Some(room) = destination {
        eprintln!("[continuar] la carga original de la ranura {slot} termino con destino a sala {room}");
    } else {
        // A rejected/corrupt save keeps the game's own scene change or loader. Never claim success
        // merely because the script ran, and never replace its error with a savestate or a new game.
        eprintln!("[continuar] el juego no confirmo un destino guardado para la ranura {slot}");
        crate::android_main::toast("El juego no confirmo la carga directa; revisa su pantalla de carga");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::parse_request;

    #[test]
    fn only_a_valid_original_slot_is_accepted() {
        assert_eq!(parse_request("slot=3\n"), Some(3));
        assert_eq!(parse_request("# comentario\nslot=99\n"), Some(99));
        assert_eq!(parse_request(""), None);
        assert_eq!(parse_request("slot=0\n"), None);
        assert_eq!(parse_request("slot=100\n"), None);
        assert_eq!(parse_request("startup=qload\n"), None);
        assert_eq!(parse_request("slot=abc\n"), None);
    }
}
