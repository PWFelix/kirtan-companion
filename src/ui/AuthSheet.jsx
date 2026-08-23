import { useState } from "react";
import * as sh from "./styles.js";

/**
 * AuthSheet — the sign-in / sign-up bottom sheet.
 *
 * One sheet, two modes (a toggle, not two components): email + password, an
 * optional name on sign-up, and a Google button. It knows nothing about
 * Supabase — every call goes through the `auth` hook and comes back as a
 * plain `{ error }` string to show. The parent closes it when a session
 * appears (see App), so there's no success path to handle here beyond the
 * "check your email" notice a confirmation-on project needs.
 *
 * It lives in ui/ rather than a screen because signing in isn't a beats
 * concern — the Beats page is just the first place that happens to need it.
 */
function AuthSheet({ auth, onClose }) {
  const [mode, setMode] = useState("in");      // "in" | "up"
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const signingUp = mode === "up";

  async function submit(e) {
    e?.preventDefault();
    setError("");
    setNotice("");
    setBusy(true);
    const { error } = signingUp
      ? await auth.signUpWithPassword(email.trim(), password, name.trim())
      : await auth.signInWithPassword(email.trim(), password);
    setBusy(false);
    if (error) { setError(error); return; }
    // A signed-in session closes the sheet from App. Sign-up on a project with
    // email confirmation ON returns no session — say so instead of hanging.
    if (signingUp) setNotice("Account created. If a confirmation email arrives, open it, then sign in.");
  }

  async function google() {
    setError("");
    setBusy(true);
    const { error } = await auth.signInWithGoogle();
    // On success the browser redirects, so we only get here on failure.
    setBusy(false);
    if (error) setError(error);
  }

  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <form style={sh.sheet} role="dialog" aria-modal="true" aria-label="Sign in"
        onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{signingUp ? "Create account" : "Sign in"}</h2>
          <button type="button" onClick={onClose} aria-label="Close" style={sh.sheetClose}>×</button>
        </div>

        <p style={sh.emptyHint}>
          An account keeps your beats and playlists in the cloud, on every device
          you sign in on, and lets you share to and copy from the community library.
        </p>

        <div style={st.fields}>
          {signingUp && (
            <input type="text" value={name} onChange={(e) => setName(e.target.value)}
              placeholder="Your name (shown on beats you share)" autoComplete="name"
              aria-label="Your name" style={st.input} />
          )}
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
            placeholder="Email" autoComplete="email" required
            aria-label="Email" style={st.input} />
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            placeholder="Password" autoComplete={signingUp ? "new-password" : "current-password"}
            required minLength={6} aria-label="Password" style={st.input} />
        </div>

        {error && <p role="alert" style={st.error}>{error}</p>}
        {notice && <p style={st.notice}>{notice}</p>}

        <button type="submit" disabled={busy} style={{ ...st.primary, opacity: busy ? 0.6 : 1 }}>
          {busy ? "…" : signingUp ? "Create account" : "Sign in"}
        </button>

        <div style={st.divider}><span style={st.dividerText}>or</span></div>

        <button type="button" onClick={google} disabled={busy} style={st.google}>
          Continue with Google
        </button>

        <button type="button" onClick={() => { setMode(signingUp ? "in" : "up"); setError(""); setNotice(""); }}
          style={st.toggle}>
          {signingUp ? "Already have an account? Sign in" : "New here? Create an account"}
        </button>
      </form>
    </div>
  );
}

const st = {
  fields: { display: "flex", flexDirection: "column", gap: "var(--space-3)", marginTop: "var(--space-4)" },
  input: { width: "100%", padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },
  error: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--danger)", lineHeight: 1.5 },
  notice: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", lineHeight: 1.5 },
  primary: { width: "100%", minHeight: 50, marginTop: "var(--space-4)", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", cursor: "pointer" },
  divider: { display: "flex", alignItems: "center", justifyContent: "center", margin: "var(--space-4) 0", borderTop: "var(--rule-hairline)", position: "relative" },
  dividerText: { position: "relative", top: "-0.7em", padding: "0 12px", background: "var(--head)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)" },
  google: { width: "100%", minHeight: 50, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  toggle: { width: "100%", marginTop: "var(--space-4)", border: "none", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
};

export default AuthSheet;
