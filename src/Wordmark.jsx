/**
 * Wordmark — the live "Mrdanga Hero" lockup.
 * ----------------------------------------------
 * Replaces the static SVG file: an <img>-loaded SVG cannot fetch its
 * fonts (browsers block external resources inside image SVGs), so the
 * old file always rendered in fallbacks. Built as live markup it uses
 * the page's real Fraunces bold, and takes its colours straight from
 * the design tokens — clay wordmark, strap Devanagari + tilak — so it
 * re-tints with any future theme.
 *
 * Structure: मृदङ्गधीर (mṛdaṅga-dhīra) centred above, then the name.
 * Under the old name "Kirtan Companion" the Vaishnava tilak stood in as
 * the "i" of "Companion"; "Mrdanga Hero" has no i, so no tilak is drawn.
 * Size everything with the --wm-size variable (1em = the wordmark line
 * height).
 *
 * With `expandFromInitials` (splash only) the letters after the M and
 * the H are wrapped in collapsing groups (.kc-wm-grow) so the lockup
 * opens as "MH" and unfurls to the full name — see the .kc-wm-anim
 * rules in index.css for how and when.
 */

function Wordmark({ style, expandFromInitials = false }) {
  return (
    <div
      className={"kc-wordmark" + (expandFromInitials ? " kc-wm-anim" : "")}
      style={style} role="img" aria-label="Mrdanga Hero"
    >
      <div className="kc-wm-deva" aria-hidden="true">मृदङ्गधीर</div>
      <div className="kc-wm-line" aria-hidden="true">
        {expandFromInitials ? (
          <>
            M<span className="kc-wm-grow"><span className="kc-wm-grow-in">rdanga&nbsp;</span></span>
            H<span className="kc-wm-grow"><span className="kc-wm-grow-in">ero</span></span>
          </>
        ) : (
          <>Mrdanga&nbsp;Hero</>
        )}
      </div>
    </div>
  );
}

export default Wordmark;
