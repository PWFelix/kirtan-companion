import { useState, useRef, useEffect } from "react";
import { readLocalLibrary, hasLocalContent } from "../storage/migrateToCloud.js";

/**
 * useCloudMigration — offer to move on-device beats into a fresh account.
 *
 * THE MOMENT it watches for: signed in, the cloud library has finished loading
 * and is EMPTY, and this device has beats saved locally. That combination is
 * almost always "someone tried the app as a guest, made a few beats, then made
 * an account" — the one case where local work would otherwise seem to vanish
 * behind the empty cloud library.
 *
 * WHY "cloud is empty" is the whole guard, and there's no persisted flag: an
 * account that already has beats is an established one; quietly merging a
 * device's guest beats into it is a thornier, riskier UX we don't want. Empty
 * account + local beats is unambiguous. After a migrate, the library is no
 * longer empty, so the guard naturally stops re-offering — no bookkeeping.
 *
 * "Not now" dismisses for the session only (a ref), so it won't nag on every
 * render but a reload will ask once more while the beats are still un-synced.
 */
export function useCloudMigration({ auth, library }) {
  const [pending, setPending] = useState(false);   // show the prompt
  const [local, setLocal] = useState(null);         // the device library to move
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(null);           // { beats, categories } after a move
  // The user we've already offered (or declined) migration for. Keyed by id so
  // a DIFFERENT account signing in on the same device gets its own offer, while
  // the same user is never nagged twice in a session. Refs persist across the
  // app's life (App never unmounts); a page reload starts fresh.
  const checkedForRef = useRef(null);

  const cloudEmpty =
    library.customBeats.length === 0 && library.categories.length === 0;

  useEffect(() => {
    const uid = auth.user?.id;
    if (!uid || library.loading) return;
    if (checkedForRef.current === uid) return;
    checkedForRef.current = uid;
    let cancelled = false;
    (async () => {
      const lib = await readLocalLibrary();
      if (cancelled) return;
      // setState here is AFTER an await, so it isn't a synchronous cascade.
      // Only an empty account with local beats gets the offer.
      if (cloudEmpty && hasLocalContent(lib)) {
        setLocal(lib);
        setPending(true);
      }
    })();
    return () => { cancelled = true; };
  }, [auth.user?.id, library.loading, cloudEmpty]);

  async function migrate() {
    if (!local) return;
    setBusy(true);
    setError("");
    try {
      const result = await library.importLibrary(local);
      if (result.error) {
        setError("Some beats couldn't be moved. Check your connection and try again.");
      } else {
        // Keep the sheet up on a success screen — the guard already won't
        // re-offer (the cloud library is no longer empty).
        setDone(result);
      }
    } catch (err) {
      setError(err.message || "That didn't work. Try again.");
    } finally {
      setBusy(false);
    }
  }

  // "Not now" — checkedForRef is already set to this user, so it won't re-offer
  // until a page reload.
  function dismiss() {
    setPending(false);
  }

  // Close the success screen.
  function acknowledge() {
    setPending(false);
    setDone(null);
  }

  const counts = local
    ? { beats: local.beats.length, categories: local.categories.length }
    : { beats: 0, categories: 0 };

  return { pending, counts, migrate, dismiss, acknowledge, busy, error, done };
}
