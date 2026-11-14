import SwingControl from "../ui/SwingControl.jsx";
import { LANES } from "../data/lanes.js";
import { EQ_BANDS, EQ_BAND_COUNT, EQ_MIN_DB, EQ_MAX_DB } from "../data/eq.js";
import { TUNE_MIN_CENTS, TUNE_MAX_CENTS, END_BASE_FREQ, centsToRate, freqToNoteName } from "../data/tuning.js";
import * as sh from "../ui/styles.js";
import { t } from "../i18n/index.js";
import { SpeakerIcon, EqIcon, TuneIcon } from "../ui/icons.jsx";

// Band labels come from the shared spec the engine also reads (data/eq.js), so
// the sliders can't drift from the DSP chain. EQ_FLAT is a render-safe default.
const EQ_BAND_LABELS = EQ_BANDS.map(band => band.label);
const EQ_FLAT = Array.from({ length: EQ_BAND_COUNT }, () => 0);

// Signed gain readout for the EQ bands: "+3 dB" / "0 dB" / "−3 dB" — the
// explicit sign makes the cut/boost direction legible at a glance.
function fmtDb(db) {
  if (db === 0) return "0 dB";
  return (db > 0 ? "+" : "−") + Math.abs(db) + " dB";
}

/**
 * MixerSheet — master fader, feel/swing, then one fader + mute per lane with
 * the mridanga ends' EQ and tuning panels.
 *
 * Opened from Settings: mixing is set once and rarely touched mid-kirtan, so
 * it no longer takes room on Home. It owns no state; it reads and writes the
 * transport it is handed.
 */
function MixerSheet({ transport, onClose }) {
  const {
    volume, changeVolume, endVolumes, changeEndVolume, mutedEnds, toggleMute, swing, setSwing,
    eqBands = { dayan: EQ_FLAT, bayan: EQ_FLAT },
    eqOpen = { dayan: false, bayan: false },
    changeEqBand, toggleEqPanel,
    eqTune = { dayan: 0, bayan: 0 }, changeEqTune,
    tuneOpen = { dayan: false, bayan: false }, toggleTunePanel,
  } = transport;
  return (
    <div style={sh.sheetBackdrop} onClick={() => onClose()}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("mixer.title")}
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("mixer.title")}</h2>
          <button onClick={() => onClose()} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <div style={st.mixRows}>
          <div style={st.mixRow}>
            <span style={st.mixLabel}>{t("mixer.master")}</span>
            <input className="kc-range" type="range" min={0} max={100} value={Math.round(volume * 100)}
              onChange={(e) => changeVolume(Number(e.target.value) / 100)}
              style={{ "--fill": volume * 100 + "%", flex: 1 }} aria-label={t("mixer.masterVolume")} />
            {/* Three spacers: the mute, EQ and tuning columns close every
                lane row, so the master fader keeps its right edge aligned
                with theirs. */}
            <span style={{ width: 44 }} aria-hidden="true" />
            <span style={{ width: 44 }} aria-hidden="true" />
            <span style={{ width: 44 }} aria-hidden="true" />
          </div>
          {LANES.map(l => {
            const v = endVolumes[l.id] ?? 1;
            const muted = !!mutedEnds[l.id];
            // EQ and tuning belong to the two mridanga ends only — the
            // frozen contract has no kartal keys (eqOpen/tuneOpen[kartal]
            // would be undefined), so the Kartal row keeps just its fader +
            // mute, plus spacers that hold the fader column aligned with
            // the three-button rows.
            const isEqEnd = l.id === "dayan" || l.id === "bayan";
            const open = !!eqOpen[l.id];
            const tuning = !!tuneOpen[l.id];
            const bands = eqBands[l.id] ?? EQ_FLAT;
            const cents = eqTune[l.id] ?? 0;
            return (
              <div key={l.id} style={st.mixLane}>
                <div style={st.mixRow}>
                  <span style={{ ...st.mixLabel, color: l.color }}>{l.label}</span>
                  <input className="kc-range" type="range" min={0} max={100} value={Math.round(v * 100)}
                    onChange={(e) => changeEndVolume(l.id, Number(e.target.value) / 100)}
                    style={{ "--fill": v * 100 + "%", flex: 1, opacity: muted ? 0.35 : 1 }}
                    aria-label={t("mixer.volume", { lane: l.label })} />
                  <button onClick={() => toggleMute(l.id)} aria-pressed={muted}
                    aria-label={t(muted ? "mixer.unmute" : "mixer.mute", { lane: l.label })}
                    style={{ ...st.muteBtn, background: muted ? "var(--head-sunken)" : "transparent",
                      color: muted ? "var(--clay)" : "var(--syahi-soft)" }}>
                    <SpeakerIcon muted={muted} />
                  </button>
                  {isEqEnd ? (
                    // The optional call + disabled gate are render-safety only:
                    // the transport supplies toggleEqPanel, and they degrade an
                    // absent handler to an inert button instead of a dead click.
                    <button onClick={() => toggleEqPanel?.(l.id)} disabled={!toggleEqPanel}
                      aria-pressed={open} aria-expanded={open} aria-label={t("mixer.eq", { lane: l.label })}
                      style={{ ...st.muteBtn, background: open ? "var(--head-sunken)" : "transparent",
                        color: open ? l.color : "var(--syahi-soft)",
                        opacity: toggleEqPanel ? 1 : 0.35, cursor: toggleEqPanel ? "pointer" : "not-allowed" }}>
                      <EqIcon />
                    </button>
                  ) : (
                    <span style={{ width: 44 }} aria-hidden="true" />
                  )}
                  {isEqEnd ? (
                    // Same render-safety posture as the EQ button beside it.
                    <button onClick={() => toggleTunePanel?.(l.id)} disabled={!toggleTunePanel}
                      aria-pressed={tuning} aria-expanded={tuning} aria-label={t("mixer.tuning", { lane: l.label })}
                      style={{ ...st.muteBtn, background: tuning ? "var(--head-sunken)" : "transparent",
                        color: tuning ? l.color : "var(--syahi-soft)",
                        opacity: toggleTunePanel ? 1 : 0.35, cursor: toggleTunePanel ? "pointer" : "not-allowed" }}>
                      <TuneIcon />
                    </button>
                  ) : (
                    <span style={{ width: 44 }} aria-hidden="true" />
                  )}
                </div>
                {isEqEnd && open && (
                  <div style={st.eqPanel} role="group" aria-label={t("mixer.equalizer", { lane: l.label })}>
                    {EQ_BAND_LABELS.map((bandLabel, i) => {
                      const db = bands[i] ?? 0;
                      return (
                        <div key={bandLabel} style={st.mixRow}>
                          <span style={{ ...st.mixLabel, textTransform: "none" }}>{bandLabel}</span>
                          <input className="kc-range" type="range" min={EQ_MIN_DB} max={EQ_MAX_DB} step={1} value={db}
                            onChange={(e) => changeEqBand?.(l.id, i, Number(e.target.value))}
                            disabled={!changeEqBand}
                            style={{ "--fill": ((db - EQ_MIN_DB) / (EQ_MAX_DB - EQ_MIN_DB)) * 100 + "%", "--accent-action": l.color, flex: 1,
                              opacity: changeEqBand ? 1 : 0.5, cursor: changeEqBand ? "pointer" : "not-allowed" }}
                            aria-label={t("mixer.bandGain", { lane: l.label, band: bandLabel })} />
                          <span style={st.eqDb}>{fmtDb(db)}</span>
                          <span style={{ width: 44 }} aria-hidden="true" />
                        </div>
                      );
                    })}
                  </div>
                )}
                {isEqEnd && tuning && (
                  // Tuning is its own surface from the EQ panel: a separate
                  // button, a separate disclosure. The readout names the
                  // pitch actually sounding — the measured base freq
                  // (data/tuning.js) shifted by the user's cents — so it
                  // doubles as the "what note am I on" display.
                  <div style={st.eqPanel} role="group" aria-label={t("mixer.tuning", { lane: l.label })}>
                    <div style={st.mixRow}>
                      <span style={st.mixLabel}>{t("mixer.tune")}</span>
                      <input className="kc-range" type="range" min={TUNE_MIN_CENTS} max={TUNE_MAX_CENTS} step={1} value={cents}
                        onChange={(e) => changeEqTune?.(l.id, Number(e.target.value))}
                        disabled={!changeEqTune}
                        style={{ "--fill": ((cents - TUNE_MIN_CENTS) / (TUNE_MAX_CENTS - TUNE_MIN_CENTS)) * 100 + "%", "--accent-action": l.color, flex: 1,
                          opacity: changeEqTune ? 1 : 0.5, cursor: changeEqTune ? "pointer" : "not-allowed" }}
                        aria-label={t("mixer.tuneCents", { lane: l.label })} />
                      <span style={st.tuneNote}>{freqToNoteName((END_BASE_FREQ[l.id] ?? 440) * centsToRate(cents))}</span>
                      <span style={{ width: 44 }} aria-hidden="true" />
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <p style={{ ...sh.emptyHint, marginTop: "var(--space-4)" }}>
          {t("mixer.balancedHint")}
        </p>
        <div style={{ marginTop: "var(--space-4)" }}>
          <SwingControl value={swing} onChange={setSwing} hint={t("swing.mixerHint")} />
        </div>
      </div>
    </div>
  );
}

const st = {
  mixRows: { display: "flex", flexDirection: "column", gap: "var(--space-4)", marginTop: "var(--space-4)" },
  mixRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  mixLabel: { flexShrink: 0, width: 64, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  muteBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", cursor: "pointer", display: "grid", placeItems: "center" },
  mixLane: { display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  eqPanel: { display: "flex", flexDirection: "column", gap: 2 },
  eqDb: { flexShrink: 0, width: 44, textAlign: "right", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-body-xs)", fontWeight: 600, color: "var(--syahi-soft)" },
  tuneNote: { flexShrink: 0, width: 68, textAlign: "right", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-body-xs)", fontWeight: 600, color: "var(--syahi-soft)" },
};

export default MixerSheet;
