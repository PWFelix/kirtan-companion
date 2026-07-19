/**
 * Splash — the opening page and the audio unlock.
 * -----------------------------------------------
 * Browsers refuse to play sound until the user taps the page, so the
 * one mandatory tap becomes the brand moment: dharma wheel + wordmark,
 * a single Begin bar, and the tap unlocks the engine on its way in
 * (see App.handleBegin). Shown once per page load.
 *
 * Future native builds keep this screen but drop the button — native
 * apps have no audio-unlock requirement.
 */

function Splash({ onBegin, ready }) {
  return (
    <div className="kc-screen" style={sp.screen}>
      <div style={sp.hero}>
        <img src="/images/dharma-wheel.png" alt="" aria-hidden="true" style={sp.wheel} />
        <img src="/images/kirtan-companion-stacked1.svg" alt="Kirtan Companion" style={sp.mark} />
      </div>
      <button onClick={onBegin} style={sp.begin}>
        {ready ? "Begin" : "Begin · loading sounds…"}
      </button>
    </div>
  );
}

const sp = {
  screen: {
    width: "100%", minHeight: "100dvh", margin: "0 auto",
    display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
    gap: "var(--space-8)",
    padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-6) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))",
  },
  hero: { display: "flex", flexDirection: "column", alignItems: "center", gap: "var(--space-6)" },
  // The wheel is the hero — sized by the smaller viewport side so it
  // works in both orientations without overwhelming either.
  wheel: { width: "clamp(150px, 38vmin, 260px)", height: "auto", opacity: 0.85 },
  mark: { width: "min(78vw, 340px)", height: "auto" },
  begin: {
    width: "100%", maxWidth: 320, minHeight: 56, borderRadius: 16,
    border: "none", background: "var(--clay)", color: "var(--on-clay)",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)",
    fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase",
    cursor: "pointer",
  },
};

export default Splash;
