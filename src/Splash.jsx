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
      {/* Wordmark centre-locked to the wheel's centre (both at 50%).
          The entrance animation lives on an INNER element so its
          transform never fights the positioning transform. */}
      <div style={sp.markPos}>
        <div className="kc-splash-rise1">
          <Wordmark style={{ "--wm-size": "clamp(30px, 8.5vw, 44px)" }} />
        </div>
      </div>
      {/* Begin bar: top edge on the two-thirds line. */}
      <div style={sp.beginPos}>
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
  },
  markPos: {
    position: "absolute", left: 0, right: 0, top: "50%",
    transform: "translateY(-50%)",
    display: "flex", justifyContent: "center",
    padding: "0 var(--space-5)",
  },
  beginPos: {
    position: "absolute", left: 0, right: 0, top: "66%",
    display: "flex", justifyContent: "center",
    padding: "0 calc(var(--space-5) + env(safe-area-inset-right)) 0 calc(var(--space-5) + env(safe-area-inset-left))",
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
