/**
 * Wordmark — the live "Kirtanero" lockup.
 * ----------------------------------------------
 * Replaces the static SVG file: an <img>-loaded SVG cannot fetch its
 * fonts (browsers block external resources inside image SVGs), so the
 * old file always rendered in fallbacks. Built as live markup it uses
 * the page's real Fraunces bold, and takes its colours straight from
 * the design tokens — clay wordmark, strap Devanagari — so it
 * re-tints with any future theme.
 *
 * Structure: कीर्तन (kīrtana) centred above, then the name in plain
 * letters. Size everything with the
 * --wm-size variable (1em = the wordmark line height).
 *
 * With `expandFromInitials` (splash only) everything after the K is
 * wrapped in a collapsing group (.kc-wm-grow) so the lockup opens as
 * "K" and unfurls to the full name — see the .kc-wm-anim rules in
 * index.css for how and when.
 */

function Wordmark({ style, expandFromInitials = false }) {
  return (
    <div
      className={"kc-wordmark" + (expandFromInitials ? " kc-wm-anim" : "")}
      style={style} role="img" aria-label="Kirtanero"
    >
      <div className="kc-wm-deva" aria-hidden="true">कीर्तन</div>
      <div className="kc-wm-line" aria-hidden="true">
        {expandFromInitials ? (
          <>
            K<span className="kc-wm-grow"><span className="kc-wm-grow-in">irtanero</span></span>
          </>
        ) : (
          <>Kirtanero</>
        )}
      </div>
    </div>
  );
}

export default Wordmark;
