import * as sh from "./styles.js";

/**
 * MigrationSheet — "bring your on-device beats into your account?"
 *
 * Shown once, right after a first sign-in, when the account is empty but the
 * device has guest beats (see useCloudMigration). Two outcomes: Move them up,
 * or Not now. After a successful move it flips to a short confirmation so the
 * user sees where their work went, rather than the sheet just vanishing.
 *
 * It's dumb on purpose — every decision lives in the hook; this only renders
 * the counts and the buttons.
 */
function MigrationSheet({ counts, done, busy, error, onMigrate, onDismiss, onAcknowledge }) {
  const line = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const parts = [];
  if (counts.beats) parts.push(line(counts.beats, "beat", "beats"));
  if (counts.categories) parts.push(line(counts.categories, "playlist", "playlists"));
  const what = parts.join(" and ");

  if (done) {
    const dParts = [];
    if (done.beats) dParts.push(line(done.beats, "beat", "beats"));
    if (done.categories) dParts.push(line(done.categories, "playlist", "playlists"));
    return (
      <div style={sh.sheetBackdrop} onClick={onAcknowledge}>
        <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Beats moved"
          onClick={(e) => e.stopPropagation()}>
          <div style={sh.sheetHead}>
            <h2 style={sh.sheetName}>Moved to your account</h2>
          </div>
          <p style={st.body}>
            {dParts.join(" and ")} {done.beats + done.categories === 1 ? "is" : "are"} now
            saved to your account and will follow you to any device you sign in on.
          </p>
          <button onClick={onAcknowledge} style={st.primary}>Done</button>
        </div>
      </div>
    );
  }

  return (
    <div style={sh.sheetBackdrop} onClick={busy ? undefined : onDismiss}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Bring your beats to your account"
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>Bring your beats along?</h2>
        </div>
        <p style={st.body}>
          You have {what} saved on this device. Move {counts.beats + counts.categories === 1 ? "it" : "them"} into
          your account so {counts.beats + counts.categories === 1 ? "it stays" : "they stay"} in the cloud and appear
          on every device you sign in on.
        </p>
        {error && <p role="alert" style={st.error}>{error}</p>}
        <div style={st.actions}>
          <button onClick={onDismiss} disabled={busy} style={st.secondary}>Not now</button>
          <button onClick={onMigrate} disabled={busy}
            style={{ ...st.primary, width: "auto", flex: 2, marginTop: 0, opacity: busy ? 0.6 : 1 }}>
            {busy ? "Moving…" : "Move to my account"}
          </button>
        </div>
      </div>
    </div>
  );
}

const st = {
  body: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", lineHeight: 1.55 },
  error: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--danger)", lineHeight: 1.5 },
  actions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-5)" },
  secondary: { flex: 1, minHeight: 50, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  primary: { width: "100%", minHeight: 50, marginTop: "var(--space-5)", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", cursor: "pointer" },
};

export default MigrationSheet;
