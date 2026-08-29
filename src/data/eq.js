/**
 * eq.js
 * -----
 * The pure equaliser facts — no React, no audio, no storage behind them.
 *
 * WHY THIS FILE EXISTS: the per-end EQ contract (epic #1) was hard-coded in
 * three places at once — the engine's filter table, the mixer's slider
 * labels, and persistence's band count — so adding or retuning a band meant
 * editing all three in lockstep, and missing one would silently desync the
 * UI from the DSP chain. Everything that needs to know which bands exist,
 * what they're called, or how far a gain may swing imports it here, so the
 * contract stays mechanically consistent.
 *
 * The design is FROZEN (epic #1): five cascaded bands per mridanga end —
 * low shelf, three peaking, high shelf — covering the drums' body. Band
 * order here is band order everywhere: the engine's filter chains, the
 * mixer's sliders and the persisted bands arrays all index this table.
 */

// Per-band gain bounds in dB — a symmetric cut/boost range. The mixer
// sliders, the transport's clamp, persistence's load-time sanitise and the
// engine's ramp all enforce this one range.
export const EQ_MIN_DB = -12;
export const EQ_MAX_DB = 12;

// The fixed five-filter chain. `label` is the mixer's slider label for the
// band; `Q` applies to the peaking filters only.
export const EQ_BANDS = [
  { type: "lowshelf", frequency: 100, label: "100 Hz" },
  { type: "peaking", frequency: 300, Q: 1, label: "300 Hz" },
  { type: "peaking", frequency: 1000, Q: 1, label: "1 kHz" },
  { type: "peaking", frequency: 3000, Q: 1, label: "3 kHz" },
  { type: "highshelf", frequency: 8000, label: "8 kHz" },
];

export const EQ_BAND_COUNT = EQ_BANDS.length;
