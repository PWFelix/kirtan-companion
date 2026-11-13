/**
 * Wordmark — the live "Kirtanero" lockup.
 * ----------------------------------------------
 * Replaces the static SVG file: an <img>-loaded SVG cannot fetch its
 * fonts (browsers block external resources inside image SVGs), so the
 * old file always rendered in fallbacks. Built as live markup it uses
 * the page's real Fraunces bold, and takes its colours straight from
 * the design tokens — clay wordmark, strap Devanagari + tilak — so it
 * re-tints with any future theme.
 *
 * Structure: कीर्तन (kīrtana) centred above, then the name, with a
 * Vaishnava tilak standing in as its "i". Size everything with the
 * --wm-size variable (1em = the wordmark line height).
 *
 * With `expandFromInitials` (splash only) everything after the K is
 * wrapped in a collapsing group (.kc-wm-grow) so the lockup opens as
 * "K" and unfurls to the full name — see the .kc-wm-anim rules in
 * index.css for how and when.
 */

function Tilak() {
  return (
    <svg className="kc-wm-tilak" viewBox="0 0 12 42" aria-hidden="true">
      <path d="M 1.5 2 L 1.5 19 A 4.5 4.5 0 0 0 10.5 19 L 10.5 2"
        fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      <path d="M 3 26 A 3 3 0 0 1 9 26 Q 8 32 6 36 Q 4 32 3 26 Z" fill="currentColor" />
    </svg>
  );
}

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
            K<span className="kc-wm-grow"><span className="kc-wm-grow-in"><Tilak />rtanero</span></span>
          </>
        ) : (
          <>K<Tilak />rtanero</>
        )}
      </div>
    </div>
  );
}

export default Wordmark;
