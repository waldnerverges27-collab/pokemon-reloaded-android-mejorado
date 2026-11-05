//! Which of the game's abilities (Cut, Surf, Strength...) the game is asking for at the player's
//! feet right now, so the touch overlay can show that one button and no other.
//!
//! The game decides this itself, scattered through its scripts: the bush, the rock and the boulder
//! each watch for the player standing next to them and facing them; the player's own script looks for
//! water, a diving spot, a climbable rock. Nothing here runs the game's code - it reads the same
//! things the scripts read (where the instances are, which way the player faces, the badges, the
//! party's moves) and asks the same questions, so a button shows when the key behind it would act.
//!
//! The ids are the game's own object ids (`obj_arbusto` 105, `obj_roca` 106, `obj_roca_redonda` 301,
//! `obj_Oscuro1` 145, `obj_agua1` 74, `obj_lava` 406, `obj_Prota` 14, `obj_agua_buceable` 476...).

#![allow(dead_code)]

use super::Game;
use crate::{
    gml::Value,
    math::Real,
    touch_controls::{self as tc, Ability},
};

const PLAYER: i32 = tc::PLAYER_OBJECT;
/// The object that holds the game's flags (badges and the like).
const FLAGS: i32 = 18;
const BUSH: i32 = 105;
const ROCK: i32 = 106;
const BOULDER: i32 = 301;
const DARKNESS: i32 = 145;
const WATER: i32 = 74;
const LAVA: i32 = 406;
const DIVE_SPOT: i32 = 476;
const DIVE_EXIT: i32 = 502;
const DIVE_BLOCK: i32 = 1897;
const WHIRLPOOL: i32 = 432;
const SECRET_DOOR: i32 = 522;
const CLIMBABLE: i32 = 521;
const RIDER: i32 = 142;
/// The waterfalls a surfer can climb.
const WATERFALLS: [i32; 8] = [284, 1613, 1644, 1670, 1817, 1825, 1826, 833];
/// Things that, when they exist, stop the player from acting: a conversation or a cutscene.
const BLOCKERS: [i32; 2] = [273, 308];

/// What the game is asking for.
#[derive(Default, Clone, Copy, Debug)]
pub struct Readout {
    /// Bits of [tc::ABILITIES].
    pub wanted: u32,
    /// The abilities' list (`8`) has something to open.
    pub menu: bool,
    /// The key items' list (`N`) has something to open.
    pub items: bool,
}

/// The places (`global.lugar`) the game lets the player dig out of: its own script `7496` lists them
/// (caves and the like; two of them only after a point of the story, left out here).
const DIG_PLACES: &[i32] = &[
    45, 55, 56, 57, 79, 80, 118, 119, 120, 122, 121, 126, 127, 128, 138, 171, 172, 173, 170, 501, 539, 177, 195, 152,
    192, 193, 196, 197, 198, 199, 248, 249, 250, 251, 252, 258, 259, 260, 282, 283, 284, 288, 289, 322, 323, 331, 370,
    371, 357, 358, 359, 361, 362, 364, 363, 365, 366, 374, 375, 376, 387, 388, 389, 390, 391, 392, 393, 398, 399, 401,
    400, 402, 403, 419, 420, 421, 423, 424, 425, 429, 430, 201, 463, 464, 517, 520, 519, 518, 521, 522, 523, 524, 587,
    590, 606, 607, 608, 617, 618, 619, 620, 621, 641, 660, 704, 661, 662, 703, 668, 667, 666, 700, 701, 702, 691, 692,
    693, 699, 694, 695, 708, 580, 581, 582, 583, 603, 604, 605, 663, 664, 665, 696, 697, 698,
];
/// Places of the outdoors (`global.room_out`) where digging does not work.
const NO_DIG_OUTS: [i32; 5] = [234, 372, 585, 609, 589];

impl Game {
    /// The first live instance of an object (or of one of its children).
    fn first_of(&self, object: i32) -> Option<usize> {
        let list = &self.room.instance_list;
        let mut iter = list.iter_by_identity(object);
        iter.next(list)
    }

    fn exists(&self, object: i32) -> bool {
        self.room.instance_list.count(object) != 0
    }

    /// Every live instance of an object.
    fn all_of(&self, object: i32) -> Vec<usize> {
        let list = &self.room.instance_list;
        let mut iter = list.iter_by_identity(object);
        let mut found = Vec::new();
        while let Some(handle) = iter.next(list) {
            found.push(handle);
        }
        found
    }

    /// A number in a variable of an instance (`index` 0 for a plain variable); 0 if it is not there.
    fn field(&self, handle: usize, name: &[u8], index: u32) -> f64 {
        let Some(id) = self.compiler.find_field_id(name) else { return 0.0 };
        let instance = self.room.instance_list.get(handle);
        let fields = instance.fields.borrow();
        match fields.get(&id).and_then(|field| field.get(index)) {
            Some(Value::Real(real)) => real.into_inner(),
            _ => 0.0,
        }
    }

    /// A number in a global of the game, `fallback` if it is not there.
    fn global(&self, name: &[u8], index: u32, fallback: i32) -> i32 {
        let Some(id) = self.compiler.find_field_id(name) else { return fallback };
        match self.globals.fields.get(&id).and_then(|field| field.get(index)) {
            Some(Value::Real(real)) => real.into_inner() as i32,
            _ => fallback,
        }
    }

    /// Distance between the boxes of two instances, as the game's `distance_to_object` measures it.
    fn box_distance(&self, a: usize, b: usize) -> f64 {
        for handle in [a, b] {
            let sprite = self.get_instance_mask_sprite(handle);
            self.room.instance_list.get(handle).update_bbox(sprite);
        }
        let (a, b) = (self.room.instance_list.get(a), self.room.instance_list.get(b));
        let gap = |a_low: i32, a_high: i32, b_low: i32, b_high: i32| {
            if a_low > b_high {
                a_low - b_high
            } else if b_low > a_high {
                b_low - a_high
            } else {
                0
            }
        };
        let dx = gap(a.bbox_left.get(), a.bbox_right.get(), b.bbox_left.get(), b.bbox_right.get());
        let dy = gap(a.bbox_top.get(), a.bbox_bottom.get(), b.bbox_top.get(), b.bbox_bottom.get());
        f64::from(dx).hypot(f64::from(dy))
    }

    /// `place_meeting`: whether `who`, moved by (`dx`, `dy`), would touch an instance of `object`.
    fn meets_at(&self, who: usize, dx: f64, dy: f64, object: i32) -> bool {
        let instance = self.room.instance_list.get(who);
        let (x, y) = (instance.x.get(), instance.y.get());
        instance.x.set(x + Real::from(dx));
        instance.y.set(y + Real::from(dy));
        instance.bbox_is_stale.set(true);
        let hit = self.find_instance_with(object, |handle| self.check_collision(who, handle)).is_some();
        instance.x.set(x);
        instance.y.set(y);
        instance.bbox_is_stale.set(true);
        hit
    }

    /// `position_meeting`: whether the point is inside an instance of `object`.
    fn point_in(&self, x: f64, y: f64, object: i32) -> bool {
        self.find_instance_with(object, |handle| self.check_collision_point(handle, Real::from(x), Real::from(y), true))
            .is_some()
    }

    /// The instance of `object` nearest to a point, by the distance to its origin (`instance_nearest`).
    fn nearest_to(&self, x: f64, y: f64, object: i32) -> Option<usize> {
        let mut best: Option<(usize, f64)> = None;
        for handle in self.all_of(object) {
            let instance = self.room.instance_list.get(handle);
            let (dx, dy) = (instance.x.get().into_inner() - x, instance.y.get().into_inner() - y);
            let distance = dx * dx + dy * dy;
            if best.map_or(true, |(_, nearest)| distance < nearest) {
                best = Some((handle, distance));
            }
        }
        best.map(|(handle, _)| handle)
    }

    /// Whether the player is next to `target` and facing it, the test the bush, the rock and the
    /// boulder each make of the player: within `across` of the target's middle on the other axis, on
    /// the right side of it for the way the player faces, and no more than 10 away. (`mira` is the
    /// direction the player faces, as a number-pad digit: 8 up, 2 down, 3/6/9 right, 1/4/7 left.)
    fn faces(&self, player: usize, target: usize, centre: (f64, f64), across: f64) -> bool {
        let facing = self.field(player, b"mira", 0) as i32;
        let me = self.room.instance_list.get(player);
        let (px, py) = (me.x.get().into_inner(), me.y.get().into_inner());
        let near = self.box_distance(player, target) < 10.0;
        let (cx, cy) = centre;
        near && match facing {
            8 => (px - cx).abs() < across && py > cy,
            2 => (px - cx).abs() < across && py < cy,
            3 | 6 | 9 => (py - cy).abs() < across && px < cx,
            1 | 4 | 7 => (py - cy).abs() < across && px > cx,
            _ => false,
        }
    }

    fn any_faced(&self, player: usize, object: i32, across: f64, offset: f64) -> bool {
        self.all_of(object).into_iter().any(|target| {
            let instance = self.room.instance_list.get(target);
            let centre = (instance.x.get().into_inner() + offset, instance.y.get().into_inner() + offset);
            // A map has hundreds of bushes; only the ones at arm's length are worth measuring.
            let me = self.room.instance_list.get(player);
            if (me.x.get().into_inner() - centre.0).abs() > 40.0 || (me.y.get().into_inner() - centre.1).abs() > 40.0 {
                return false
            }
            self.faces(player, target, centre, across)
        })
    }

    /// Whether a badge of the game is won (`(18).MEDALLA_STAR[n]`).
    fn badge(&self, flags: usize, n: u32) -> bool {
        self.field(flags, b"MEDALLA_STAR", n) != 0.0
    }

    /// The abilities the party's six Pokémon can use, as the game's own bits: what is already in the
    /// mask the game keeps for each (`global.info[i,11]`) and what their four attacks give.
    fn party_ability_bits(&self) -> u32 {
        let mut bits = 0u32;
        for slot in 0..6u32 {
            if self.global(b"pkmn1", slot, 0) <= 0 {
                continue
            }
            let kept = self.global(b"info", slot * 32000 + 11, 0).max(0) as u32;
            bits |= kept & ((1 << 17) - 1);
            for attack in 1..=4u32 {
                if let Some(bit) = tc::attack_ability_bit(self.global(b"attack1", slot * 32000 + attack, 0)) {
                    bits |= 1 << bit;
                }
            }
        }
        bits
    }

    /// Whether any water the player could start surfing from is within reach (`ProtaSurf`).
    fn water_in_reach(&self, player: usize, object: i32, ignore: Option<i32>) -> bool {
        let me = self.room.instance_list.get(player);
        let (px, py) = (me.x.get().into_inner(), me.y.get().into_inner());
        if self.meets_at(player, 0.0, -8.0, 1715) || self.meets_at(player, 0.0, -16.0, 1715) {
            return false
        }
        [-8.0, -2.0, -14.0].into_iter().any(|dx| {
            let Some(water) = self.nearest_to(px + dx, py - 8.0, object) else { return false };
            let tile = self.room.instance_list.get(water);
            if ignore == Some(tile.object_index.get()) {
                return false
            }
            let (wx, wy) = (tile.x.get().into_inner(), tile.y.get().into_inner());
            self.box_distance(player, water) <= 7.0
                && !self.point_in(px, py, 354)
                && !self.point_in(px, py, 358)
                && !self.point_in(wx, wy, 1)
                && !self.meets_at(player, wx - px, wy - py, 75)
                && !self.meets_at(player, wx - px, wy - py, 1293)
        })
    }

    /// Reads what the game is asking for. Cheap: it looks at a handful of objects, and nothing at all
    /// when the player is not on the map.
    pub fn ability_readout(&self) -> Readout {
        let (Some(player), Some(flags)) = (self.first_of(PLAYER), self.first_of(FLAGS)) else {
            return Readout::default()
        };
        // The same things stop the player from every action: a conversation, a cutscene, a wait.
        if BLOCKERS.iter().any(|&blocker| self.exists(blocker)) || self.global(b"wait", 0, 0) != 0 {
            return Readout::default()
        }
        let room = self.room.id;
        let outdoors = self.global(b"room_out", 0, -1) == -1;
        let surfing = self.global(b"surfer", 0, 0) != 0;
        let riding = self.exists(RIDER);
        let party = self.party_ability_bits();
        let can = |bit: u32| party & (1 << bit) != 0;
        let mut wanted = Vec::new();

        // Cut, Rock Smash and Strength: the bush, the rock and the boulder in front of the player.
        if !riding {
            if self.badge(flags, 2) && can(0) && self.any_faced(player, BUSH, 12.0, 0.0) {
                wanted.push(Ability::Cut);
            }
            if self.badge(flags, 1) && can(5) && self.any_faced(player, ROCK, 6.0, 0.0) {
                wanted.push(Ability::RockSmash);
            }
            if self.badge(flags, 5)
                && can(3)
                && self.field(flags, b"usafuerza", 0) == 0.0
                && self.any_faced(player, BOULDER, 10.0, 8.0)
            {
                wanted.push(Ability::Strength);
            }
        }
        // Flash: a dark room that has not been lit yet.
        if self.badge(flags, 3)
            && can(4)
            && self.exists(DARKNESS)
            && self.field(flags, b"usadestello", 0) == 0.0
            && room != 76
            && room != 201
        {
            wanted.push(Ability::Flash);
        }
        if !surfing && !riding {
            // Surf: water the player is standing beside.
            if self.badge(flags, 6) && can(2) && self.water_in_reach(player, WATER, Some(LAVA)) {
                wanted.push(Ability::Surf);
            }
            // The lava's own wave.
            if self.badge(flags, 8) && can(16) && self.exists(LAVA) && self.water_in_reach(player, LAVA, None) {
                wanted.push(Ability::Lava);
            }
            // Dig: out of a cave.
            let place = self.global(b"lugar", 0, 0);
            let out = self.global(b"room_out", 0, -1);
            if self.badge(flags, 1)
                && can(12)
                && !outdoors
                && place > 0
                && DIG_PLACES.contains(&place)
                && !NO_DIG_OUTS.contains(&out)
            {
                wanted.push(Ability::Dig);
            }
            // Climb: a rock to climb right in front.
            if self.badge(flags, 7) && can(13) && self.exists(CLIMBABLE) && self.field(player, b"TREPARROCA", 0) as i32 == -1 {
                let facing = self.field(player, b"mira", 0) as i32;
                if (facing == 2 && self.meets_at(player, 0.0, 8.0, CLIMBABLE))
                    || (facing == 8 && self.meets_at(player, 0.0, -8.0, CLIMBABLE))
                {
                    wanted.push(Ability::Climb);
                }
            }
        }
        // Dive: the diving spot under the player, or the way back up.
        if self.badge(flags, 7) && can(6) {
            let blocked = self.first_of(DIVE_BLOCK).is_some_and(|block| self.box_distance(player, block) < 60.0);
            let exit = self.exists(DIVE_EXIT)
                && self
                    .nearest_to(
                        self.room.instance_list.get(player).x.get().into_inner(),
                        self.room.instance_list.get(player).y.get().into_inner(),
                        DIVE_EXIT,
                    )
                    .is_some_and(|spot| self.box_distance(player, spot) < 10.0);
            if !blocked && (self.meets_at(player, 0.0, 0.0, DIVE_SPOT) || exit) {
                wanted.push(Ability::Dive);
            }
        }
        // Waterfall: surfing, next to a waterfall.
        if surfing && riding && self.badge(flags, 8) && can(9) {
            let on_lava = self.first_of(RIDER).is_some_and(|rider| self.field(rider, b"LAVA", 0) != 0.0);
            let near = WATERFALLS
                .iter()
                .flat_map(|&object| self.all_of(object))
                .any(|fall| self.box_distance(player, fall) <= 16.0);
            if !on_lava && near {
                wanted.push(Ability::Waterfall);
            }
        }
        // Whirlpool and the secret base's door: the object nearest to the player, if it is close.
        let me = self.room.instance_list.get(player);
        let (px, py) = (me.x.get().into_inner(), me.y.get().into_inner());
        if self.badge(flags, 6) {
            let close = self.nearest_to(px, py, WHIRLPOOL).is_some_and(|handle| {
                let there = self.room.instance_list.get(handle);
                (there.x.get().into_inner() - px).hypot(there.y.get().into_inner() - py) < 32.0
            });
            if close {
                wanted.push(Ability::Whirl);
            }
        }
        if self.badge(flags, 4) {
            let close = self.nearest_to(px, py, SECRET_DOOR).is_some_and(|handle| {
                let there = self.room.instance_list.get(handle);
                (there.x.get().into_inner() + 8.0 - px).hypot(there.y.get().into_inner() + 8.0 - py) < 20.0
            });
            if close {
                wanted.push(Ability::Secret);
            }
        }

        let has = |name: &[u8]| self.field(flags, name, 0) != 0.0;
        Readout {
            wanted: tc::ability_mask(&wanted),
            // The list of abilities answers only on the outdoor map, and once the first badge is won.
            menu: outdoors && self.badge(flags, 1),
            items: ["movil", "mapa1", "notebook", "bici", "cania", "xpod_star", "mega_aro"].iter().any(|name| has(name.as_bytes())),
        }
    }

    /// Sets the alarm a button asked for (see [tc::request_alarm]), the way the game's own list of
    /// abilities does.
    pub fn run_ability_request(&mut self) {
        let Some((object, alarm, nearest)) = tc::take_alarm_request() else { return };
        let Some(player) = self.first_of(PLAYER) else { return };
        let target = if nearest {
            let me = self.room.instance_list.get(player);
            let (px, py) = (me.x.get().into_inner(), me.y.get().into_inner());
            self.nearest_to(px, py, object)
        } else {
            self.first_of(object)
        };
        if let Some(target) = target {
            self.room.instance_list.get(target).alarms.borrow_mut().insert(alarm, 1);
        }
    }
}
