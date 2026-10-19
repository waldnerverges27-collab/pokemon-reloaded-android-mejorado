//! The native look of the on-screen controls: smooth translucent discs with a soft shadow, a thin
//! ring, strokes for the labels and a press animation, instead of the pixel-art sprites.
//!
//! Everything is built from triangles with a colour per vertex, so the edges are feathered by
//! fading the alpha over about a pixel (the overlay is not multisampled, and a hard polygon edge
//! shows its steps on a phone screen). The output is the overlay's vertex format: x, y in clip
//! space, r, g, b, a, and a texture coordinate that points at its 1x1 white texture.

use crate::touch_controls::Icon;

type Color = [f32; 4];

/// Segments of a full circle. 48 keeps a 150 px button round to well under a pixel.
const SEG: usize = 48;

fn vertex(out: &mut Vec<f32>, ww: f32, wh: f32, x: f32, y: f32, c: Color) {
    out.extend_from_slice(&[x / ww * 2.0 - 1.0, 1.0 - y / wh * 2.0, c[0], c[1], c[2], c[3], 0.0, 0.0]);
}

fn mix(a: Color, b: Color, t: f32) -> Color {
    let t = t.clamp(0.0, 1.0);
    [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t]
}

fn scaled(c: Color, alpha: f32) -> Color {
    [c[0], c[1], c[2], c[3] * alpha]
}

struct Canvas<'a> {
    out: &'a mut Vec<f32>,
    ww: f32,
    wh: f32,
}

impl Canvas<'_> {
    fn tri(&mut self, a: (f32, f32, Color), b: (f32, f32, Color), c: (f32, f32, Color)) {
        for (x, y, col) in [a, b, c] {
            vertex(self.out, self.ww, self.wh, x, y, col);
        }
    }

    /// A disc whose colour runs from `center` in the middle to `edge` at the rim.
    fn disc(&mut self, cx: f32, cy: f32, r: f32, center: Color, edge: Color) {
        for i in 0..SEG {
            let a0 = i as f32 / SEG as f32 * std::f32::consts::TAU;
            let a1 = (i + 1) as f32 / SEG as f32 * std::f32::consts::TAU;
            self.tri(
                (cx, cy, center),
                (cx + r * a0.cos(), cy + r * a0.sin(), edge),
                (cx + r * a1.cos(), cy + r * a1.sin(), edge),
            );
        }
    }

    /// A band between two radii, with a colour at each.
    fn band(&mut self, cx: f32, cy: f32, r_in: f32, r_out: f32, inner: Color, outer: Color) {
        for i in 0..SEG {
            let a0 = i as f32 / SEG as f32 * std::f32::consts::TAU;
            let a1 = (i + 1) as f32 / SEG as f32 * std::f32::consts::TAU;
            let (c0, s0, c1, s1) = (a0.cos(), a0.sin(), a1.cos(), a1.sin());
            let p = |r: f32, c: f32, s: f32| (cx + r * c, cy + r * s);
            let (i0, i1) = (p(r_in, c0, s0), p(r_in, c1, s1));
            let (o0, o1) = (p(r_out, c0, s0), p(r_out, c1, s1));
            self.tri((i0.0, i0.1, inner), (o0.0, o0.1, outer), (i1.0, i1.1, inner));
            self.tri((i1.0, i1.1, inner), (o0.0, o0.1, outer), (o1.0, o1.1, outer));
        }
    }

    /// A disc with a one-pixel feathered rim, so it does not stair-step.
    fn smooth_disc(&mut self, cx: f32, cy: f32, r: f32, center: Color, edge: Color) {
        self.disc(cx, cy, r, center, edge);
        self.band(cx, cy, r, r + 1.2, edge, [edge[0], edge[1], edge[2], 0.0]);
    }

    /// A ring `width` thick that ends at radius `r`, feathered on both sides.
    fn ring(&mut self, cx: f32, cy: f32, r: f32, width: f32, col: Color) {
        let clear = [col[0], col[1], col[2], 0.0];
        self.band(cx, cy, r - width, r, col, col);
        self.band(cx, cy, r, r + 1.2, col, clear);
        self.band(cx, cy, (r - width - 1.2).max(0.0), r - width, clear, col);
    }

    /// A line with round ends.
    fn line(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, width: f32, col: Color) {
        let (dx, dy) = (x1 - x0, y1 - y0);
        let len = (dx * dx + dy * dy).sqrt();
        let half = width / 2.0;
        if len > 0.001 {
            let (nx, ny) = (-dy / len * half, dx / len * half);
            let (a, b) = ((x0 + nx, y0 + ny, col), (x0 - nx, y0 - ny, col));
            let (c, d) = ((x1 + nx, y1 + ny, col), (x1 - nx, y1 - ny, col));
            self.tri(a, b, c);
            self.tri(b, d, c);
        }
        for (x, y) in [(x0, y0), (x1, y1)] {
            for i in 0..12 {
                let a0 = i as f32 / 12.0 * std::f32::consts::TAU;
                let a1 = (i + 1) as f32 / 12.0 * std::f32::consts::TAU;
                self.tri((x, y, col), (x + half * a0.cos(), y + half * a0.sin(), col), (x + half * a1.cos(), y + half * a1.sin(), col));
            }
        }
    }

    fn polyline(&mut self, points: &[(f32, f32)], width: f32, col: Color) {
        for pair in points.windows(2) {
            self.line(pair[0].0, pair[0].1, pair[1].0, pair[1].1, width, col);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stroke font. Each glyph is a few polylines in a unit box (x right, y down, 0..1 both ways). Curves
// are approximated with a handful of points: at the sizes a button label is drawn at, that reads as
// smooth, and it scales without the blockiness of the bitmap font the pixel-art style used.

fn glyph(ch: char) -> &'static [&'static [(f32, f32)]] {
    const O: &[(f32, f32)] = &[
        (0.5, 0.0), (0.15, 0.1), (0.0, 0.35), (0.0, 0.65), (0.15, 0.9), (0.5, 1.0), (0.85, 0.9), (1.0, 0.65),
        (1.0, 0.35), (0.85, 0.1), (0.5, 0.0),
    ];
    const P: &[(f32, f32)] = &[(0.0, 1.0), (0.0, 0.0), (0.65, 0.0), (0.92, 0.12), (0.95, 0.32), (0.8, 0.48), (0.6, 0.52), (0.0, 0.52)];
    match ch.to_ascii_uppercase() {
        'A' => &[&[(0.0, 1.0), (0.5, 0.0), (1.0, 1.0)], &[(0.2, 0.68), (0.8, 0.68)]],
        'B' => &[
            &[(0.0, 1.0), (0.0, 0.0), (0.65, 0.0), (0.9, 0.12), (0.9, 0.38), (0.65, 0.5), (0.0, 0.5)],
            &[(0.65, 0.5), (0.95, 0.62), (0.95, 0.88), (0.68, 1.0), (0.0, 1.0)],
        ],
        'C' => &[&[(1.0, 0.15), (0.8, 0.03), (0.5, 0.0), (0.2, 0.08), (0.0, 0.3), (0.0, 0.7), (0.2, 0.92), (0.5, 1.0), (0.8, 0.97), (1.0, 0.85)]],
        'D' => &[&[(0.0, 0.0), (0.0, 1.0), (0.5, 1.0), (0.85, 0.85), (1.0, 0.5), (0.85, 0.15), (0.5, 0.0), (0.0, 0.0)]],
        'E' => &[&[(1.0, 0.0), (0.0, 0.0), (0.0, 1.0), (1.0, 1.0)], &[(0.0, 0.5), (0.75, 0.5)]],
        'F' => &[&[(1.0, 0.0), (0.0, 0.0), (0.0, 1.0)], &[(0.0, 0.5), (0.75, 0.5)]],
        'G' => &[&[(1.0, 0.15), (0.8, 0.03), (0.5, 0.0), (0.2, 0.08), (0.0, 0.3), (0.0, 0.7), (0.2, 0.92), (0.5, 1.0), (0.85, 0.92), (1.0, 0.7), (1.0, 0.5), (0.55, 0.5)]],
        'H' => &[&[(0.0, 0.0), (0.0, 1.0)], &[(1.0, 0.0), (1.0, 1.0)], &[(0.0, 0.5), (1.0, 0.5)]],
        'I' => &[&[(0.5, 0.0), (0.5, 1.0)], &[(0.2, 0.0), (0.8, 0.0)], &[(0.2, 1.0), (0.8, 1.0)]],
        'J' => &[&[(1.0, 0.0), (1.0, 0.75), (0.8, 0.95), (0.5, 1.0), (0.2, 0.95), (0.0, 0.75)]],
        'K' => &[&[(0.0, 0.0), (0.0, 1.0)], &[(1.0, 0.0), (0.0, 0.55)], &[(0.25, 0.4), (1.0, 1.0)]],
        'L' => &[&[(0.0, 0.0), (0.0, 1.0), (1.0, 1.0)]],
        'M' => &[&[(0.0, 1.0), (0.0, 0.0), (0.5, 0.6), (1.0, 0.0), (1.0, 1.0)]],
        'N' => &[&[(0.0, 1.0), (0.0, 0.0), (1.0, 1.0), (1.0, 0.0)]],
        'O' | '0' => &[O],
        'P' => &[P],
        'Q' => &[O, &[(0.6, 0.7), (1.0, 1.05)]],
        'R' => &[P, &[(0.5, 0.52), (1.0, 1.0)]],
        'S' => &[&[(1.0, 0.15), (0.75, 0.02), (0.4, 0.0), (0.1, 0.1), (0.05, 0.3), (0.3, 0.47), (0.7, 0.55), (0.95, 0.7), (0.92, 0.9), (0.6, 1.0), (0.25, 0.98), (0.0, 0.85)]],
        'T' => &[&[(0.0, 0.0), (1.0, 0.0)], &[(0.5, 0.0), (0.5, 1.0)]],
        'U' => &[&[(0.0, 0.0), (0.0, 0.7), (0.15, 0.92), (0.5, 1.0), (0.85, 0.92), (1.0, 0.7), (1.0, 0.0)]],
        'V' => &[&[(0.0, 0.0), (0.5, 1.0), (1.0, 0.0)]],
        'W' => &[&[(0.0, 0.0), (0.25, 1.0), (0.5, 0.4), (0.75, 1.0), (1.0, 0.0)]],
        'X' => &[&[(0.0, 0.0), (1.0, 1.0)], &[(1.0, 0.0), (0.0, 1.0)]],
        'Y' => &[&[(0.0, 0.0), (0.5, 0.5), (1.0, 0.0)], &[(0.5, 0.5), (0.5, 1.0)]],
        'Z' => &[&[(0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 1.0)]],
        '1' => &[&[(0.2, 0.2), (0.55, 0.0), (0.55, 1.0)]],
        '2' => &[&[(0.0, 0.2), (0.2, 0.05), (0.5, 0.0), (0.8, 0.05), (1.0, 0.25), (0.9, 0.5), (0.0, 1.0), (1.0, 1.0)]],
        '3' => &[&[(0.0, 0.1), (0.5, 0.0), (0.9, 0.12), (0.95, 0.3), (0.7, 0.47), (0.35, 0.5)], &[(0.7, 0.47), (1.0, 0.62), (0.98, 0.88), (0.55, 1.0), (0.0, 0.9)]],
        '4' => &[&[(0.75, 1.0), (0.75, 0.0), (0.0, 0.7), (1.0, 0.7)]],
        '5' => &[&[(0.95, 0.0), (0.1, 0.0), (0.05, 0.45), (0.5, 0.4), (0.9, 0.5), (1.0, 0.72), (0.85, 0.93), (0.5, 1.0), (0.0, 0.9)]],
        '6' => &[&[(0.9, 0.05), (0.5, 0.0), (0.15, 0.2), (0.0, 0.6), (0.1, 0.9), (0.5, 1.0), (0.9, 0.9), (1.0, 0.7), (0.85, 0.5), (0.5, 0.45), (0.1, 0.6)]],
        '7' => &[&[(0.0, 0.0), (1.0, 0.0), (0.35, 1.0)]],
        '8' => &[&[(0.5, 0.5), (0.15, 0.35), (0.15, 0.12), (0.5, 0.0), (0.85, 0.12), (0.85, 0.35), (0.5, 0.5), (0.05, 0.7), (0.05, 0.9), (0.5, 1.0), (0.95, 0.9), (0.95, 0.7), (0.5, 0.5)]],
        '9' => &[&[(0.1, 0.95), (0.5, 1.0), (0.85, 0.8), (1.0, 0.4), (0.9, 0.1), (0.5, 0.0), (0.1, 0.1), (0.0, 0.3), (0.15, 0.5), (0.5, 0.55), (0.9, 0.4)]],
        '+' => &[&[(0.5, 0.25), (0.5, 0.75)], &[(0.2, 0.5), (0.8, 0.5)]],
        _ => &[],
    }
}

/// Draws `text` centred at (cx, cy), `height` pixels tall.
fn text(c: &mut Canvas, label: &str, cx: f32, cy: f32, height: f32, col: Color) {
    let chars: Vec<char> = label.chars().collect();
    if chars.is_empty() {
        return
    }
    let glyph_w = height * 0.62;
    let gap = height * 0.30;
    let total = glyph_w * chars.len() as f32 + gap * (chars.len() as f32 - 1.0);
    let width = (height * 0.15).max(1.6);
    let mut x = cx - total / 2.0;
    for ch in chars {
        for stroke in glyph(ch) {
            let points: Vec<(f32, f32)> = stroke.iter().map(|&(u, v)| (x + u * glyph_w, cy - height / 2.0 + v * height)).collect();
            c.polyline(&points, width, col);
        }
        x += glyph_w + gap;
    }
}

fn icon(c: &mut Canvas, kind: Icon, cx: f32, cy: f32, size: f32, col: Color) {
    let width = (size * 0.2).max(1.8);
    match kind {
        Icon::Pause => {
            for dx in [-0.22, 0.22] {
                c.line(cx + dx * size, cy - 0.36 * size, cx + dx * size, cy + 0.36 * size, width, col);
            }
        },
        Icon::Back => {
            c.line(cx - 0.4 * size, cy, cx + 0.4 * size, cy, width, col);
            c.polyline(&[(cx - 0.05 * size, cy - 0.36 * size), (cx - 0.42 * size, cy), (cx - 0.05 * size, cy + 0.36 * size)], width, col);
        },
        Icon::Ball => {
            c.ring(cx, cy, size * 0.5, width * 0.8, col);
            c.line(cx - 0.5 * size, cy, cx + 0.5 * size, cy, width * 0.8, col);
            c.smooth_disc(cx, cy, size * 0.17, col, col);
        },
    }
}

/// What a button looks like.
pub struct Button<'a> {
    pub cx: f32,
    pub cy: f32,
    pub r: f32,
    /// Accent colour, used for the label at rest and for the glow when pressed.
    pub accent: [f32; 3],
    /// Whether the label takes the accent colour at rest (the four main buttons) or stays white.
    pub tinted: bool,
    /// 0 = at rest, 1 = fully pressed; moves smoothly between them.
    pub press: f32,
    pub opacity: f32,
    pub label: &'a str,
    pub icon: Option<Icon>,
}

pub fn draw_button(out: &mut Vec<f32>, ww: f32, wh: f32, b: &Button) {
    let mut c = Canvas { out, ww, wh };
    let p = b.press.clamp(0.0, 1.0);
    // Pressing shrinks the button a little, like a physical key going down.
    let r = b.r * (1.0 - 0.09 * p);
    let (cx, cy) = (b.cx, b.cy);
    let a = b.opacity;
    let accent = [b.accent[0], b.accent[1], b.accent[2], 1.0];

    // Soft drop shadow under the button, fading out as it is pressed.
    let shadow = [0.0, 0.0, 0.0, 0.28 * (1.0 - 0.7 * p) * a];
    c.disc(cx, cy + r * 0.07, r * 0.96, shadow, shadow);
    c.band(cx, cy + r * 0.07, r * 0.96, r * 1.22, shadow, [0.0, 0.0, 0.0, 0.0]);

    // Body: a dark glass disc, a touch lighter towards the rim, filling with the accent when pressed.
    let rest_center = [0.07, 0.08, 0.12, 0.40];
    let rest_edge = [0.16, 0.18, 0.24, 0.52];
    let down_center = [accent[0] * 0.55, accent[1] * 0.55, accent[2] * 0.55, 0.70];
    let down_edge = [accent[0] * 0.80, accent[1] * 0.80, accent[2] * 0.80, 0.82];
    c.smooth_disc(cx, cy, r, scaled(mix(rest_center, down_center, p), a), scaled(mix(rest_edge, down_edge, p), a));

    // Glow around a pressed button.
    if p > 0.02 {
        c.band(cx, cy, r, r * 1.28, [accent[0], accent[1], accent[2], 0.38 * p * a], [accent[0], accent[1], accent[2], 0.0]);
    }

    // Ring.
    let ring_rest = [1.0, 1.0, 1.0, 0.50];
    let ring_down = [mix(accent, [1.0, 1.0, 1.0, 1.0], 0.55)[0], mix(accent, [1.0, 1.0, 1.0, 1.0], 0.55)[1], mix(accent, [1.0, 1.0, 1.0, 1.0], 0.55)[2], 1.0];
    c.ring(cx, cy, r, (r * 0.045).max(1.8), scaled(mix(ring_rest, ring_down, p), a));

    // Label.
    let rest_ink = if b.tinted { [accent[0], accent[1], accent[2], 0.95] } else { [1.0, 1.0, 1.0, 0.92] };
    let ink = scaled(mix(rest_ink, [1.0, 1.0, 1.0, 1.0], p), a);
    if let Some(kind) = b.icon {
        icon(&mut c, kind, cx, cy, r * 0.62, ink);
    } else {
        let n = b.label.chars().count().max(1) as f32;
        // One letter is big; a longer word shrinks so it stays inside the disc.
        let height = (r * 0.62).min(r * 1.5 / (0.92 * n - 0.3));
        text(&mut c, b.label, cx, cy, height, ink);
    }
}

/// The movement stick: a translucent base with the four directions marked, and a knob that follows
/// the thumb.
pub fn draw_stick(out: &mut Vec<f32>, ww: f32, wh: f32, cx: f32, cy: f32, r: f32, knob: (f32, f32), engaged: bool, opacity: f32) {
    let mut c = Canvas { out, ww, wh };
    let a = opacity;
    let shadow = [0.0, 0.0, 0.0, 0.22 * a];
    c.disc(cx, cy + r * 0.04, r * 0.98, shadow, shadow);
    c.band(cx, cy + r * 0.04, r * 0.98, r * 1.12, shadow, [0.0, 0.0, 0.0, 0.0]);
    c.smooth_disc(cx, cy, r, [0.07, 0.08, 0.12, 0.26 * a], [0.16, 0.18, 0.24, 0.42 * a]);
    c.ring(cx, cy, r, (r * 0.025).max(2.0), [1.0, 1.0, 1.0, 0.38 * a]);
    // Direction marks.
    let chev = [1.0, 1.0, 1.0, 0.30 * a];
    let (d, s) = (r * 0.74, r * 0.10);
    let w = (r * 0.03).max(2.0);
    c.polyline(&[(cx - s, cy - d + s), (cx, cy - d), (cx + s, cy - d + s)], w, chev);
    c.polyline(&[(cx - s, cy + d - s), (cx, cy + d), (cx + s, cy + d - s)], w, chev);
    c.polyline(&[(cx - d + s, cy - s), (cx - d, cy), (cx - d + s, cy + s)], w, chev);
    c.polyline(&[(cx + d - s, cy - s), (cx + d, cy), (cx + d - s, cy + s)], w, chev);
    // Knob.
    let (kx, ky) = (cx + knob.0 * r * 0.58, cy + knob.1 * r * 0.58);
    let kr = r * 0.40;
    let lift = if engaged { 0.20 } else { 0.0 };
    c.disc(kx, ky + kr * 0.10, kr * 0.98, [0.0, 0.0, 0.0, 0.25 * a], [0.0, 0.0, 0.0, 0.25 * a]);
    c.band(kx, ky + kr * 0.10, kr * 0.98, kr * 1.2, [0.0, 0.0, 0.0, 0.25 * a], [0.0, 0.0, 0.0, 0.0]);
    c.smooth_disc(kx, ky, kr, [0.85, 0.88, 0.95, (0.50 + lift) * a], [0.55, 0.60, 0.72, (0.45 + lift) * a]);
    c.ring(kx, ky, kr, (kr * 0.07).max(2.0), [1.0, 1.0, 1.0, (0.65 + lift) * a]);
}
