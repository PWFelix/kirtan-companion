/**
 * Splash — the opening page and the audio unlock.
 * -----------------------------------------------
 * Browsers refuse to play sound until the user taps the page, so the
 * one mandatory tap becomes the brand moment: a huge faint clay dharma
 * wheel behind the centred live wordmark and a single Begin bar, with
 * a short entrance animation (wheel settles in, wordmark and button
 * rise; disabled under prefers-reduced-motion). The tap unlocks the
 * engine on its way in (see App.handleBegin). Shown once per page load.
 *
 * The wheel is NOT an <img>: it's a clay-filled div with the PNG's
 * transparency used as a CSS mask (.kc-splash-wheel) — that recolours
 * it to the exact --clay token and it re-tints with any future theme.
 *
 * Future native builds keep this screen but drop the button — native
 * apps have no audio-unlock requirement.
 */
import Wordmark from "./Wordmark.jsx";

function Splash({ onBegin, ready }) {
  return (
    <div className="kc-screen" style={sp.screen}>
      <div className="kc-splash-wheel" aria-hidden="true" />
      <div style={sp.content}>
        <div className="kc-splash-rise1">
          <Wordmark style={{ "--wm-size": "clamp(30px, 8.5vw, 44px)" }} />
        </div>
        <button onClick={onBegin} className="kc-splash-rise2" style={sp.begin}>
          {ready ? "Begin" : "Begin · loading sounds…"}
        </button>
      </div>
    </div>
  );
}

const sp = {
  screen: {
    position: "relative", overflow: "hidden",
    width: "100%", minHeight: "100dvh", margin: "0 auto",
    display: "flex", flexDirection: "column", justifyContent: "center",
    padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-6) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))",
  },
  // Centred lockup, just a touch above true centre.
  content: {
    position: "relative",
    width: "100%", maxWidth: 430, margin: "0 auto",
    display: "flex", flexDirection: "column", alignItems: "center",
    gap: "var(--space-7)",
    transform: "translateY(-2vh)",
  },
  begin: {
    width: "100%", maxWidth: 300, minHeight: 56, borderRadius: 16,
    border: "none", background: "var(--clay)", color: "var(--on-clay)",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)",
    fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase",
    cursor: "pointer",
  },
};

export default Splash;
