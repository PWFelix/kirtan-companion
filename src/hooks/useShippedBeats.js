import { useState, useEffect, useCallback, useRef, useMemo } from "react";
import { isSupabaseConfigured } from "../storage/supabaseClient.js";
import { fetchShippedRows } from "../storage/shippedBeatsClient.js";
import { readShippedCache, writeShippedCache } from "../storage/shippedBeatsCache.js";
import { validShippedSet, rowsToBeats, revisionOf } from "../data/shippedBeats.js";

/**
 * useShippedBeats — the built-in beat set, as the server holds it.
 *
 * It owns the fallback ladder and nothing else:
 *
 *     valid remote  →  valid cache  →  (the caller's) compiled BEATS
 *
 * The compiled rung is NOT here. This hook resolves to `beats: null` when it
 * has nothing better, and useBeatLibrary — which is what merges built-ins with
 * the user's own beats — decides what "nothing better" means, so the fallback
 * stays in the one place that already knew about it.
 *
 * NON-BLOCKING BY DESIGN. The fetch happens once on mount and nothing awaits
 * it: the compiled set is already on screen, and this replaces it when the
 * answer arrives. That is what lets a failed or slow fetch be invisible — no
 * spinner, no blank Beats screen, no throw into a render. Running offline, on
 * an unconfigured project, or against an empty table are all supported states,
 * not errors, and the only trace they leave is a console warning.
 *
 * THE SAME FUNCTION answers both the mount fetch and the manual "Check for
 * beat updates" affordance (`check`). They differ only in what the caller does
 * with the result: the mount logs it, the button shows it. A result is a
 * small descriptor rather than a throw, because "couldn't reach the server" is
 * the expected answer often enough that it must not need a try/catch at every
 * call site.
 */
export function useShippedBeats() {
  // Seeded from disk in a lazy initialiser, exactly as useTransport hydrates
  // the EQ prefs: the read is synchronous, a few kilobytes, and it means the
  // first paint can already show last session's set instead of flashing the
  // compiled one. StrictMode double-invokes initialisers, and a pure read is
  // unaffected.
  const [shipped, setShipped] = useState(readShippedCache);   // { rows, revision } | null

  // The revision now in effect, mirrored out of state so `check` can compare
  // against it without re-creating itself (and so the mount fetch and a manual
  // one can't disagree about what "up to date" means).
  const revisionRef = useRef(shipped?.revision ?? "");

  // StrictMode mounts, unmounts and remounts in dev, so the first fetch can
  // resolve after its hook is gone. The ref keeps that from writing state on
  // an unmounted component — the same concern useTransport's listener cleanup
  // documents. Set true in the effect body, not just at declaration, because
  // the remount reuses the ref the first cleanup already cleared.
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  const beats = useMemo(() => (shipped ? rowsToBeats(shipped.rows) : null), [shipped]);

  const check = useCallback(async () => {
    let raw;
    try {
      raw = await fetchShippedRows();
    } catch (err) {
      return { kind: "failed", message: err.message };
    }

    // A set that doesn't validate is not a partial update, it's no update:
    // whatever is on screen stays, and the reason is reported rather than
    // guessed at. See validShippedSet for why this is all-or-nothing.
    const rows = validShippedSet(raw);
    if (!rows) {
      return {
        kind: "failed",
        message: "The server's beat set couldn't be read, so the built-in beats are unchanged.",
      };
    }

    const revision = revisionOf(rows);
    // Same revision as the set already in effect: no write, and no new array
    // identity, so nothing downstream re-renders and the cache isn't rewritten
    // on every launch. `updated_at` is trigger-maintained, so the revision
    // moves only when a row was written — which is the right trigger for a
    // re-read, though not proof a beat differs (a no-op write moves it too),
    // and that is the caller's wording problem, not this one's.
    if (revision === revisionRef.current) return { kind: "current", count: rows.length };

    revisionRef.current = revision;
    writeShippedCache(rows);
    if (aliveRef.current) setShipped({ rows, revision });
    return { kind: "updated", count: rows.length };
  }, []);

  useEffect(() => {
    // An unconfigured build has no client to ask, so it never fetches: it runs
    // on the compiled set, or on whatever a configured build on this origin
    // last left in the cache. The same "the cloud features aren't offered"
    // degradation the community and auth code does.
    if (!isSupabaseConfigured) return undefined;
    let cancelled = false;
    check().then((result) => {
      // A launch-time failure is worth a line in the console and nothing
      // more: the user is looking at a working library either way.
      if (!cancelled && result.kind === "failed") {
        console.warn("[shipped] built-in beat set not refreshed:", result.message);
      }
    });
    return () => { cancelled = true; };
  }, [check]);

  return { beats, check };
}
