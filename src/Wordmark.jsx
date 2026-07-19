/**
 * Wordmark — the live "Kirtan Companion" lockup.
 * ----------------------------------------------
 * Replaces the static SVG file: an <img>-loaded SVG cannot fetch its
 * fonts (browsers block external resources inside image SVGs), so the
 * old file always rendered in fallbacks. Built as live markup it uses
 * the page's real Fraunces bold, and takes its colours straight from
 * the design tokens — clay wordmark, strap Devanagari + tilak — so it
 * re-tints with any future theme.
 *
 * Structure: the Devanagari कीर्तन centred above, then the wordmark in
 * which the Vaishnava tilak stands in as the "i" of "Companion" (same
 * trick as the original logo). Size everything with the --wm-size
 * variable (1em = the wordmark line height).
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

function Wordmark({ style }) {
  return (
    <div className="kc-wordmark" style={style} role="img" aria-label="Kirtan Companion">
      <div className="kc-wm-deva" aria-hidden="true">कीर्तन</div>
      <div className="kc-wm-line" aria-hidden="true">
        Kirtan&nbsp;Compan<Tilak />on
      </div>
    </div>
  );
}

export default Wordmark;
