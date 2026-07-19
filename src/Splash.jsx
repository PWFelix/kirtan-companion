/**
 * Splash — the opening page and the audio unlock.
 * -----------------------------------------------
 * Browsers refuse to play sound until the user taps the page, so the
 * one mandatory tap becomes the brand moment: a huge faint clay dharma
 * wheel behind the left-justified wordmark and a single Begin bar.
 * The tap unlocks the engine on its way in (see App.handleBegin).
 * Shown once per page load.
 *
 * The wheel is NOT an <img>: it's a clay-filled div with the PNG's
 * transparency used as a CSS mask — that recolours it to the exact
 * --clay token (and it re-tints itself if the palette ever changes).
 *
 * Future native builds keep this screen but drop the button — native
 * apps have no audio-unlock requirement.
 */

const WHEEL_MASK = "url(/images/dharma-wheel.png)";

function Splash({ onBegin, ready }) {
  return (
    <div className="kc-screen" style={sp.screen}>
      <div style={sp.wheel} aria-hidden="true" />
      <div style={sp.content}>
        <img src="/images/kirtan-companion-stacked1.svg" alt="Kirtan Companion" style={sp.mark} />
        <button onClick={onBegin} style={sp.begin}>
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
  // The wheel fills most of the screen behind everything: a clay div
  // stencilled through the PNG's alpha, very faint, centred, slightly
  // oversize so it bleeds past the edges.
  wheel: {
    position: "absolute", left: "50%", top: "50%",
    transform: "translate(-50%, -50%)",
    width: "110vmin", aspectRatio: "1",
    background: "var(--clay)", opacity: 0.1,
    WebkitMaskImage: WHEEL_MASK, maskImage: WHEEL_MASK,
    WebkitMaskSize: "contain", maskSize: "contain",
    WebkitMaskRepeat: "no-repeat", maskRepeat: "no-repeat",
    WebkitMaskPosition: "center", maskPosition: "center",
    pointerEvents: "none",
  },
  // Left-justified block (the wordmark is drawn left-anchored), raised
  // a little above true centre.
  content: {
    position: "relative",
    width: "100%", maxWidth: 430, margin: "0 auto",
    display: "flex", flexDirection: "column", alignItems: "flex-start",
    gap: "var(--space-6)",
    transform: "translateY(-6vh)",
  },
  mark: { width: "min(80vw, 360px)", height: "auto" },
  begin: {
    width: "100%", maxWidth: 300, minHeight: 56, borderRadius: 16,
    border: "none", background: "var(--clay)", color: "var(--on-clay)",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)",
    fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase",
    cursor: "pointer",
  },
};

export default Splash;
