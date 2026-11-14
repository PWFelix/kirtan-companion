/**
 * useCommunityLibrary.js
 * ----------------------
 * Keeps the library's community beats in step with their originals. Fetches
 * the originals of every community beat and every edited copy (so Share can
 * tell an unchanged copy from a new beat), and exposes each beat's status plus
 * Update. Mirrors Android's CommunityLibrary.kt and iOS's CommunityLibrary.swift.
 *
 * Also runs, once per device, the migration of imports made back when they
 * were editable: one still matching its original stays a community beat; one
 * the user changed becomes their own copy (`forkedFrom`). Web imports never
 * recorded a source, so in practice this only meets beats that came from the
 * apps through a cloud library.
 *
 * Offline or signed out of a broken backend just means `originals` stays null:
 * every status reads "unknown" and nothing is blocked.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { applyUpdate, communityStatus, isCommunity, migrate } from "../data/communityTracking.js";
import { originals as fetchOriginals } from "../storage/communityClient.js";
import { supabase } from "../storage/supabaseClient.js";

const MIGRATED_KEY = "kirtan-community-migrated";

export function useCommunityLibrary(library) {
  const { customBeats, replaceBeat, loading } = library;
  const [originals, setOriginals] = useState(null);
  const [tick, setTick] = useState(0);
  // Set once per session: a failed write tries again next launch, not every render.
  const migrating = useRef(false);

  // Only the ids matter, so a change to a beat's notes doesn't refetch.
  const ids = [...new Set(customBeats.flatMap((b) => [b.sourceItemId, b.forkedFrom]).filter(Boolean))].sort();
  const key = ids.join(",");

  useEffect(() => {
    if (!supabase || loading || !key) return;
    let cancelled = false;
    fetchOriginals(key.split(","))
      .then((found) => { if (!cancelled) setOriginals(found); })
      .catch(() => { /* offline: statuses stay unknown */ });
    return () => { cancelled = true; };
  }, [key, loading, tick]);

  // The migration, once originals are in for every community beat.
  useEffect(() => {
    if (!originals || migrating.current || localStorage.getItem(MIGRATED_KEY)) return;
    migrating.current = true;
    const legacy = customBeats.filter((b) => isCommunity(b) && !Number.isInteger(b.sourceIndex));
    (async () => {
      let ok = true;
      for (const beat of legacy) {
        const verdict = migrate(beat, originals[beat.sourceItemId]);
        if (verdict.kind === "fork") {
          ok = (await replaceBeat({ ...beat, sourceItemId: undefined, sourceIndex: undefined, forkedFrom: beat.sourceItemId })) && ok;
        } else if (verdict.kind === "keep" && verdict.sourceIndex != null) {
          ok = (await replaceBeat({ ...beat, sourceIndex: verdict.sourceIndex })) && ok;
        }
      }
      if (ok) {
        try { localStorage.setItem(MIGRATED_KEY, "1"); } catch { /* try again next launch */ }
      }
    })();
  }, [originals, customBeats, replaceBeat]);

  const statusOf = useCallback((beat) => communityStatus(beat, originals), [originals]);

  /** Bring a community beat up to its original. Resolves true when it stuck. */
  async function update(beat) {
    const status = communityStatus(beat, originals);
    if (status.kind !== "update") return false;
    return replaceBeat(applyUpdate(beat, status.upstream));
  }

  return { originals, statusOf, update, refresh: () => setTick((n) => n + 1) };
}
