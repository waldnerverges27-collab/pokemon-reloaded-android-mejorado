//! Quick save and quick load for the Android build: one tap saves the whole game to a slot, one tap
//! brings it back. It reuses the savestate machinery the TAS tools are built on, with the parts a
//! phone cannot or should not do taken out:
//!
//! * the sounds and the bundled files stay out of the file (they are static data, and the sounds
//!   alone would make every save tens of megabytes). Music that was looping is started again after
//!   a load, from its beginning;
//! * the keys held right now and the clock are kept as they are, so a load does not leave a
//!   direction stuck down or send the game's timers backwards;
//! * the file is written beside the slot and renamed over it, so a failed save never destroys the
//!   previous one.
//!
//! The launcher reads `slotN.info` to show when each slot was saved.

use super::{
    savestate::{Buffer, SaveState},
    Game, Replay,
};
use crate::{extras, gml, math::Real};
use std::{
    fs,
    io::Read,
    panic::{catch_unwind, AssertUnwindSafe},
    path::PathBuf,
    time::{Instant, SystemTime, UNIX_EPOCH},
};

/// Bumped whenever what goes into the file changes, so an old save is refused instead of misread.
const FORMAT: u32 = 1;
/// No real save decompresses to more than this; a bigger number in the header means a damaged file.
const MAX_STATE_BYTES: u64 = 768 * 1024 * 1024;

fn directory() -> PathBuf {
    PathBuf::from(format!("/data/data/{}/files/quicksaves", crate::android_main::PACKAGE_DIR))
}

fn state_path(slot: u8) -> PathBuf {
    directory().join(format!("slot{}.state", slot))
}

fn info_path(slot: u8) -> PathBuf {
    directory().join(format!("slot{}.info", slot))
}

/// The value of `key` in an info file.
fn info_value(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|line| line.strip_prefix(key).and_then(|rest| rest.strip_prefix('=')).map(|v| v.trim().to_string()))
}

impl Game {
    /// Runs a pending quick save/load request, if the buttons or the pad raised one.
    pub fn handle_quick_request(&mut self) {
        let save = match extras::take_quick_request() {
            Some(save) => save,
            None => return,
        };
        let slot = extras::quick_slot();
        let started = Instant::now();
        let outcome = if save { self.quick_save(slot) } else { self.quick_load(slot) };
        match outcome {
            Ok(()) => {
                eprintln!("[quicksave] {} ranura {} en {} ms", if save { "guardada" } else { "cargada" }, slot, started.elapsed().as_millis());
                crate::android_main::toast(&if save {
                    format!("Guardado rápido en la ranura {}", slot)
                } else {
                    format!("Guardado rápido {} cargado", slot)
                });
            },
            Err(why) => {
                eprintln!("[quicksave] {} ranura {} falló: {}", if save { "guardar" } else { "cargar" }, slot, why);
                crate::android_main::toast(&if save {
                    format!("No se pudo guardar: {}", why)
                } else {
                    format!("No se pudo cargar: {}", why)
                });
            },
        }
        // The copy of the game made along the way is as big as the game's own data.
        crate::android_main::purge_memory(if save { "tras guardar" } else { "tras cargar" });
    }

    fn quick_save(&mut self, slot: u8) -> Result<(), String> {
        fs::create_dir_all(directory()).map_err(|e| format!("no hay carpeta ({})", e))?;
        if self.scene_change.is_some() {
            return Err("el juego está cambiando de sala".into())
        }

        let renderer_state = self.renderer.state();
        let sounds = std::mem::take(&mut self.assets.sounds);
        let files = std::mem::take(&mut self.included_files);
        let built = catch_unwind(AssertUnwindSafe(|| SaveState::from(self, Replay::new(0, 0), renderer_state, true)));
        self.assets.sounds = sounds;
        self.included_files = files;
        let state = built.map_err(|_| "falló al copiar el estado".to_string())?;

        let mut buffer = Buffer::new();
        state.save_to_file(&state_path(slot), &mut buffer).map_err(|e| format!("{:?}", e))?;

        let info = format!(
            "format={}\ngame={}\ntime={}\nroom={}\n",
            FORMAT,
            self.game_id,
            SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0),
            self.room.id,
        );
        // The slot only counts once its description is there, so write it last.
        let tmp = info_path(slot).with_extension("info.tmp");
        fs::write(&tmp, info).and_then(|_| fs::rename(&tmp, info_path(slot))).map_err(|e| format!("{}", e))?;
        Ok(())
    }

    fn quick_load(&mut self, slot: u8) -> Result<(), String> {
        let info = fs::read_to_string(info_path(slot)).map_err(|_| format!("la ranura {} está vacía", slot))?;
        if info_value(&info, "format").and_then(|v| v.parse::<u32>().ok()) != Some(FORMAT) {
            return Err("es de otra versión de la app".into())
        }
        if info_value(&info, "game").and_then(|v| v.parse::<i32>().ok()) != Some(self.game_id) {
            return Err("es de otro juego".into())
        }

        // Check the header before trusting it with an allocation.
        let path = state_path(slot);
        let mut header = [0u8; 8];
        fs::File::open(&path)
            .and_then(|mut f| f.read_exact(&mut header))
            .map_err(|_| "el archivo no se puede leer".to_string())?;
        if u64::from_le_bytes(header) > MAX_STATE_BYTES {
            return Err("el archivo está dañado".into())
        }

        let mut buffer = Buffer::new();
        let state = SaveState::from_file(&path, &mut buffer).map_err(|_| "el archivo está dañado".to_string())?;
        drop(buffer);
        if state.game_id != self.game_id {
            return Err("es de otro juego".into())
        }
        let looping = state.looping_sounds();

        let sounds = std::mem::take(&mut self.assets.sounds);
        let files = std::mem::take(&mut self.included_files);
        let input = self.input.clone();
        let clock = self.clock.clone();
        let loaded = catch_unwind(AssertUnwindSafe(|| state.load_into(self)));
        self.assets.sounds = sounds;
        self.included_files = files;
        self.input = input;
        self.clock = clock;
        let (_, renderer_state) = loaded.map_err(|_| "falló al restaurar el estado".to_string())?;
        self.renderer.set_state(&renderer_state);

        self.audio.stop_all();
        for id in looping {
            let _ = self.sound_loop(&[gml::Value::Real(Real::from(id))]);
        }
        Ok(())
    }
}
