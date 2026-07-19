import { useMemo, useEffect, useRef } from "react";
import { LANES } from "./data/lanes.js";
import { BOLS } from "./data/bols.js";
import { generateGuidedLabels } from "./data/stepLabels.js";

/**
 * BeatStrip — the straight-line beat visualizer
 * ---------------------------------------------
 * Renders a beat as stacked instrument LANES (rows) of cells, left to
 * right in time. Replaces the circular chakra everywhere: full-size on
 * Home, miniature on the Beats page, and (with onCellTap) inside the
 * editor's overview/zoom.
 *
 * Motion — two channels, both syahi-coloured (see index.css tokens):
 *   1. A thin PLAYHEAD LINE glides continuously across the strip, driven
 *      by engine phase via requestAnimationFrame — always in sync with
 *      the audio, self-correcting, no CSS-animation drift.
 *   2. The sounding CELL's mark flips to syahi and swells (CSS
 *      transition off a data-active toggle, same pattern as the old
 *      chakra: React never re-renders on a step tick).
 *
 * Long beats: cells have a minimum touch-legible width; when they don't
 * fit, the strip scrolls horizontally, auto-follows the playhead during
 * playback, and shows a thin loop-position bar underneath. Standard
 * 8-cell beats always fit unscrolled.
 *
 * Props:
 *   beat         - { name, steps, cellsPerGroup?, beatsPerBar?, dayan[], bayan[], ... }
 *                  lanes render for every LANES entry the beat has data for
 *   step         - current step index (-1 = stopped)
 *   playing      - whether playback is active
 *   getPhase     - () => bar phase [0,1) — powers the gliding line
 *   mutedEnds    - e.g. { dayan: true } — dims that lane
 *   onToggleMute - optional (laneId) => void; tapping a lane label toggles
 *   onCellTap    - optional (laneId, i) => void — the editor hook
 *   showBols     - show bol syllables under the marks (Settings toggle)
 *   mini         - tiny display-only rendering (Beats page rows)
 *   mode         - "line" today; "tape" (scrolling now-line) reserved for
 *                  a future feature — unknown values fall back to "line"
 */

function BeatStrip({
  beat,
  step = -1,
  playing = false,
  getPhase,
  mutedEnds = {},
  onToggleMute,
  onCellTap,
  showBols = false,
  mini = false,
  mode = "line",
}) {
  const scrollerRef = useRef(null);
  const trackRef = useRef(null);
  const lineRef = useRef(null);
  const loopBarRef = useRef(null);
  const loopWinRef = useRef(null);

  const lanes = LANES.filter((l) => Array.isArray(beat[l.id]));
  const cpg =
    beat.cellsPerGroup ??
    Math.max(1, Math.round(beat.steps / (beat.beatsPerBar ?? 4)));

  // ── Structure (memo'd — rebuilt on beat/display changes, never per step) ──
  const structure = useMemo(() => {
    const labels = generateGuidedLabels(beat.steps, cpg);
    return (
      <div
        className="ks-track"
        ref={trackRef}
        style={{ "--ks-steps": beat.steps }}
      >
        {!mini && (
          <div className="ks-numbers" aria-hidden="true">
            {labels.map((l, i) => (
              <span
                key={i}
                className="ks-num"
                data-strong={l !== "·"}
                data-sam={i === 0}
              >
                {l}
              </span>
            ))}
          </div>
        )}

        {lanes.map((lane) => (
          <div
            key={lane.id}
            className={"ks-lane" + (mutedEnds[lane.id] ? " ks-lanemuted" : "")}
            data-lane={lane.id}
            style={{ "--ks-lanecolor": lane.color }}
            role="img"
            aria-label={`${lane.label} pattern`}
          >
            {beat[lane.id].map((v, i) => (
              <div
                key={i}
                className="ks-cell"
                data-i={i}
                data-sam={i === 0}
                data-active="false"
                role={onCellTap ? "button" : undefined}
                tabIndex={onCellTap ? 0 : undefined}
              >
                <span
                  className={
                    "ks-mark " +
                    (v === "O" ? "ks-open" : v === "X" ? "ks-closed" : "ks-rest")
                  }
                />
                {showBols && !mini && v && (
                  <span className="ks-bol">{BOLS[lane.id]?.[v] ?? v}</span>
                )}
              </div>
            ))}
          </div>
        ))}

        {/* The gliding playhead — positioned by phase as a % of track width,
            so no pixel measurements are needed. */}
        <div className="ks-playhead" ref={lineRef} hidden={!playing} />
      </div>
    );
    // mutedEnds/showBols/mini change rarely; rebuilding then is fine.
    // `step` is deliberately NOT a dependency (see header).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [beat, cpg, mutedEnds, showBols, mini, onCellTap, playing]);

  // ── Active-cell lighting: flip data-active imperatively per step ──
  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    track.querySelectorAll(".ks-lane").forEach((laneEl) => {
      const muted = laneEl.classList.contains("ks-lanemuted");
      laneEl.querySelectorAll(".ks-cell").forEach((cell) => {
        cell.dataset.active =
          playing && Number(cell.dataset.i) === step && !muted
            ? "true"
            : "false";
      });
    });
  }, [step, playing, structure]);

  // ── Playhead glide + auto-follow, one rAF loop while playing ──
  useEffect(() => {
    if (!playing || !getPhase || mini) return;
    let raf;
    const tick = () => {
      const line = lineRef.current;
      const scroller = scrollerRef.current;
      if (line && scroller) {
        const phase = getPhase() || 0;
        line.style.left = phase * 100 + "%";
        // Keep the line in view when the strip overflows.
        const total = scroller.scrollWidth;
        const vis = scroller.clientWidth;
        if (total > vis + 1) {
          const x = phase * total;
          scroller.scrollLeft = Math.max(0, Math.min(total - vis, x - vis * 0.4));
        }
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, getPhase, mini]);

  // ── Loop-position bar: only visible when the strip actually scrolls ──
  useEffect(() => {
    const scroller = scrollerRef.current;
    const bar = loopBarRef.current;
    const win = loopWinRef.current;
    if (!scroller || !bar || !win) return;
    const update = () => {
      const total = scroller.scrollWidth;
      const vis = scroller.clientWidth;
      const overflow = total > vis + 1;
      bar.dataset.show = overflow ? "true" : "false";
      if (overflow) {
        win.style.width = (vis / total) * 100 + "%";
        win.style.left = (scroller.scrollLeft / total) * 100 + "%";
      }
    };
    update();
    scroller.addEventListener("scroll", update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(scroller);
    return () => {
      scroller.removeEventListener("scroll", update);
      ro.disconnect();
    };
  }, [structure, mini]);

  // ── Delegated interaction (labels + cells), chakra-style: handlers stay
  //    fresh without ever forcing the memo'd structure to rebuild. ──
  const handleClick = (e) => {
    const label = e.target.closest?.("[data-mutelane]");
    if (label && onToggleMute) {
      onToggleMute(label.dataset.mutelane);
      return;
    }
    if (!onCellTap) return;
    const cell = e.target.closest?.(".ks-cell");
    if (cell) {
      const laneEl = cell.closest("[data-lane]");
      if (laneEl) onCellTap(laneEl.dataset.lane, Number(cell.dataset.i));
    }
  };
  const handleKey = (e) => {
    if (e.key !== " " && e.key !== "Enter") return;
    if (!onCellTap) return;
    const cell = e.target.closest?.(".ks-cell");
    if (cell) {
      e.preventDefault();
      const laneEl = cell.closest("[data-lane]");
      if (laneEl) onCellTap(laneEl.dataset.lane, Number(cell.dataset.i));
    }
  };

  return (
    <div
      className={"ks-strip" + (mini ? " ks-mini" : "")}
      data-mode={mode === "tape" ? "tape" : "line"}
      onClick={handleClick}
      onKeyDown={handleKey}
    >
      {!mini && (
        <div className="ks-labelcol">
          <div className="ks-labelspacer" aria-hidden="true" />
          {lanes.map((l) => (
            <button
              key={l.id}
              type="button"
              className="ks-lanelabel"
              data-mutelane={onToggleMute ? l.id : undefined}
              disabled={!onToggleMute}
              aria-pressed={!!mutedEnds[l.id]}
              aria-label={`${l.label} drum, ${
                mutedEnds[l.id] ? "muted, tap to unmute" : "playing, tap to mute"
              }`}
              style={{ "--ks-lanecolor": l.color }}
            >
              {l.label}
            </button>
          ))}
        </div>
      )}

      <div className="ks-scroller" ref={scrollerRef}>
        {structure}
      </div>

      {!mini && (
        <div className="ks-loopbar" ref={loopBarRef} data-show="false" aria-hidden="true">
          <div className="ks-loopwin" ref={loopWinRef} />
        </div>
      )}
    </div>
  );
}

export default BeatStrip;
