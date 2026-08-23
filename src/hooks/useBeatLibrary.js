import { useState, useEffect, useCallback } from "react";
import { arrayMove } from "@dnd-kit/sortable";
import { BEATS } from "../data/beats.js";
import { beatsProvider, storageErrorMessage } from "../storage/index.js";

/**
 * The shipped beats, tagged once at module load.
 *
 * `readOnly` is the ORIGIN MARKER the rest of the app can trust: screens ask
 * for `allBeats` and decide what to offer (edit in place vs fork, delete or
 * not) from the beat itself, without needing to know which list it came out
 * of. Tagged HERE, at import, rather than during render, so the objects keep
 * their identity across re-renders — and by copying rather than mutating,
 * because data/beats.js is read-only source data.
 */
const BUILTIN_BEATS = BEATS.map((b) => ({ ...b, readOnly: true }));

/**
 * A name nobody else in the library is using: "My Beat" → "My Beat (2)".
 *
 * NAMING IS THE LIBRARY'S JOB, not the editor's, because this is the only
 * place that knows what's already taken. It used to live inside importShared
 * — so re-importing your own share link couldn't produce two identical rows
 * — while beats made in the editor got no such treatment, and two beats with
 * one name are genuinely indistinguishable in the list (a row shows the name
 * and "Custom · 8 cells", nothing else). Every way in now goes through here:
 * the editor, a fork of a built-in, and an import.
 *
 * MUTATES `taken`, which is what lets a batch import stay unique against
 * itself and not just against what was already saved.
 */
function uniqueName(wanted, taken) {
  let name = wanted;
  for (let n = 2; taken.has(name); n++) name = `${wanted} (${n})`;
  taken.add(name);
  return name;
}

/**
 * useBeatLibrary — everything the user has saved, and its persistence.
 *
 * Two things live here:
 *  - CUSTOM BEATS: beats built or forked in the editor. The built-in BEATS
 *    are read-only, so every write touches only this list and the shipped
 *    beats can never be corrupted.
 *  - CATEGORIES: ordered lists of beat ids. Each one is a kirtan
 *    PROGRESSION — the order is the order Home's ‹ › moves through them.
 *    Two categories are always present and are not stored: "builtin" and
 *    "custom". The ACTIVE category is the one the loaded beat was chosen
 *    from, and it's what Home cycles within.
 *
 * Kept apart from useTransport because none of it touches the engine. It no
 * longer touches storage directly either: everything durable goes through a
 * BeatsProvider (see src/storage/), which is what makes swapping the store
 * a one-file change. The provider is a parameter purely so a test can pass
 * a fake; nothing in the app supplies it.
 *
 * ── WHY SOME WRITES AWAIT AND SOME DON'T ──
 * CREATES await, because the id is minted by the provider and the caller
 * needs the saved beat back to select it. Everything else is OPTIMISTIC:
 * state changes at once and persistence happens behind it, because a
 * drag-to-reorder that waited for a round trip would feel broken. If an
 * optimistic write fails, the state goes back to what it was and the error
 * surfaces — which is the behaviour this whole layer exists to get right.
 * The old code caught every storage failure and carried on, so a beat saved
 * into a full store appeared in the list and vanished on reload.
 */
export function useBeatLibrary(provider = beatsProvider) {
  const [customBeats, setCustomBeats] = useState([]);
  const [categories, setCategories] = useState([]);
  const [activeCat, setActiveCatState] = useState("builtin");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Reads are async now, so the library starts empty and fills in. App gates
  // the splash's Begin button on `loading`, which means this has always
  // resolved before any screen renders — the empty first paint is never seen.
  useEffect(() => {
    let cancelled = false;
    provider
      .loadAll()
      .then(({ beats, categories: cats, activeCategoryId }) => {
        if (cancelled) return;
        setCustomBeats(Array.isArray(beats) ? beats : []);
        setCategories(Array.isArray(cats) ? cats : []);
        setActiveCatState(activeCategoryId || "builtin");
        setLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        // A failed load is not fatal: the built-in beats are compiled in, so
        // the app still plays. The user just can't see their own work, and
        // needs telling that rather than being shown an empty library.
        console.error("[library] load failed", err);
        setError(storageErrorMessage(err));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [provider]);

  const allBeats = [...BUILTIN_BEATS, ...customBeats];
  const isCustomBeat = (id) => customBeats.some((b) => b.id === id);

  const dismissError = useCallback(() => setError(null), []);

  /**
   * Apply an optimistic change: paint `next`, persist behind it, and put
   * `prev` back if the store refuses. Returns whether it stuck.
   */
  async function optimistic(setter, prev, next, persist) {
    setter(next);
    try {
      await persist();
      return true;
    } catch (err) {
      console.error("[library] write failed", err);
      setter(prev);
      setError(storageErrorMessage(err));
      return false;
    }
  }

  function setActiveCat(catId) {
    setActiveCatState(catId);
    // Not rolled back on failure, unlike everything else here: this is a
    // preference, not the user's work. Snapping the tab back under them
    // would be a worse outcome than the wrong tab being restored next launch.
    provider.setActiveCategory(catId).catch((err) => {
      console.error("[library] active category not saved", err);
    });
  }

  /** The ordered beats of a category ("builtin" | "custom" | user cat id). */
  function categoryBeats(catId) {
    if (catId === "builtin") return BUILTIN_BEATS;
    if (catId === "custom") return customBeats;
    const c = categories.find((c) => c.id === catId);
    if (!c) return allBeats;
    return c.beatIds.map((id) => allBeats.find((b) => b.id === id)).filter(Boolean);
  }
  function catName(catId) {
    if (catId === "builtin") return "Built in";
    if (catId === "custom") return "Your beats";
    return categories.find((c) => c.id === catId)?.name ?? "Beats";
  }

  /**
   * Save from the editor. A beat with no id is new (or a fork of a built-in)
   * and gets one minted; a beat with an id we already hold is edited in
   * place. Resolves with the saved beat so the caller can select it — its
   * id may not have existed until a moment ago — or null if it wasn't kept.
   */
  async function saveBeat(newBeat) {
    const { id, ...fields } = newBeat;
    // Every name in the library EXCEPT this beat's own — otherwise editing a
    // beat without renaming it would suffix it a little further every save
    // ("My Beat (2)", "My Beat (2) (2)"). Built-ins are included, so a custom
    // beat can't shadow "Te Ta" either.
    fields.name = uniqueName(
      fields.name,
      new Set(allBeats.filter((b) => b.id !== id).map((b) => b.name))
    );
    try {
      // An id we don't recognise is treated as new rather than as an error:
      // the alternative is refusing to save the user's work over a
      // bookkeeping mismatch.
      if (id && customBeats.some((b) => b.id === id)) {
        const saved = await provider.updateBeat(id, fields);
        setCustomBeats((list) => list.map((b) => (b.id === id ? saved : b)));
        setError(null);
        return saved;
      }
      const saved = await provider.createBeat(fields);
      setCustomBeats((list) => [...list, saved]);
      setError(null);
      return saved;
    } catch (err) {
      console.error("[library] save failed", err);
      setError(storageErrorMessage(err));
      return null;
    }
  }

  // Deleting a beat also drops it from any progression referencing it, so a
  // category can never point at a beat that no longer exists.
  async function deleteBeat(id) {
    const affected = categories.filter((c) => c.beatIds.includes(id));
    const ok = await optimistic(
      setCustomBeats,
      customBeats,
      customBeats.filter((b) => b.id !== id),
      () => provider.deleteBeat(id)
    );
    if (!ok || affected.length === 0) return;

    const nextCats = categories.map((c) =>
      c.beatIds.includes(id) ? { ...c, beatIds: c.beatIds.filter((x) => x !== id) } : c
    );
    await optimistic(setCategories, categories, nextCats, () =>
      Promise.all(
        affected.map((c) =>
          provider.updateCategory(c.id, {
            beatIds: c.beatIds.filter((x) => x !== id),
          })
        )
      )
    );
  }

  /**
   * Add a decoded share payload to the library — the ONLY way beats from
   * outside this device get in. Resolves with { beats, catId } so the caller
   * can select and navigate to what just arrived.
   *
   * Two things it must do that a loop of saveBeat calls could not:
   *
   *  - MINT FRESH IDS. shareCodec doesn't carry an id at all, precisely so
   *    a stranger's link can't name one of the user's beats and quietly
   *    replace it. The provider mints them on create, which is now the only
   *    place in the app ids come from. The slug in the name is sanitised
   *    (the editor's version isn't) because this name came from outside.
   *  - WRITE ONCE PER LIST. Importing a category touches both the beats and
   *    the categories. Calling createBeat in a loop would read stale state
   *    and drop beats, which is exactly the bug this shape prevents.
   */
  async function importShared(payload) {
    if (!payload || (payload.kind !== "beat" && payload.kind !== "category")) {
      return { beats: [], catId: null };
    }

    // Re-importing your own link shouldn't produce two rows with one name.
    const takenNames = new Set(allBeats.map((b) => b.name));
    const drafts = (payload.kind === "beat" ? [payload.beat] : payload.beats).map(
      (draft) => ({ ...draft, name: uniqueName(draft.name, takenNames) })
    );

    let beats;
    try {
      beats = await provider.createBeats(drafts);
      setCustomBeats((list) => [...list, ...beats]);
      setError(null);
    } catch (err) {
      console.error("[library] import failed", err);
      setError(storageErrorMessage(err));
      return { beats: [], catId: null };
    }

    if (payload.kind !== "category") return { beats, catId: null };

    try {
      const name = uniqueName(payload.name, new Set(categories.map((c) => c.name)));
      const cat = await provider.createCategory({ name, beatIds: beats.map((b) => b.id) });
      setCategories((list) => [...list, cat]);
      return { beats, catId: cat.id };
    } catch (err) {
      // The beats are genuinely saved; only the grouping failed. Keeping
      // them and saying so beats unwinding a successful write — they land
      // in "Your beats", which is where a single shared beat goes anyway.
      console.error("[library] imported beats, but the list wasn't created", err);
      setError(storageErrorMessage(err));
      return { beats, catId: null };
    }
  }

  /** Creates the category and resolves with its id (callers land the user in it). */
  async function createCategory(name) {
    try {
      // Same de-duping every beat gets: a second "Sunday kirtan" becomes
      // "Sunday kirtan (2)" rather than a twin the user can't tell apart. The
      // import path already did this (importShared); doing it here means the
      // New-category sheet and a shared link both go through one rule.
      const unique = uniqueName(name, new Set(categories.map((c) => c.name)));
      const cat = await provider.createCategory({ name: unique, beatIds: [] });
      setCategories((list) => [...list, cat]);
      setError(null);
      return cat.id;
    } catch (err) {
      console.error("[library] category not created", err);
      setError(storageErrorMessage(err));
      return null;
    }
  }

  async function deleteCategory(catId) {
    const ok = await optimistic(
      setCategories,
      categories,
      categories.filter((c) => c.id !== catId),
      () => provider.deleteCategory(catId)
    );
    if (ok && activeCat === catId) setActiveCat("builtin");
  }

  async function toggleBeatInCategory(catId, id) {
    const cat = categories.find((c) => c.id === catId);
    if (!cat) return;
    const beatIds = cat.beatIds.includes(id)
      ? cat.beatIds.filter((x) => x !== id)
      : [...cat.beatIds, id];
    await optimistic(
      setCategories,
      categories,
      categories.map((c) => (c.id === catId ? { ...c, beatIds } : c)),
      () => provider.updateCategory(catId, { beatIds })
    );
  }

  // Reorder a progression from a dnd-kit drag end (arrayMove keeps it pure).
  async function reorderCategory(catId, activeId, overId) {
    if (activeId === overId) return;
    const cat = categories.find((c) => c.id === catId);
    if (!cat) return;
    const from = cat.beatIds.indexOf(activeId);
    const to = cat.beatIds.indexOf(overId);
    if (from === -1 || to === -1) return;
    const beatIds = arrayMove(cat.beatIds, from, to);
    await optimistic(
      setCategories,
      categories,
      categories.map((c) => (c.id === catId ? { ...c, beatIds } : c)),
      () => provider.updateCategory(catId, { beatIds })
    );
  }

  return {
    customBeats, allBeats, isCustomBeat,
    categories, activeCat, setActiveCat, categoryBeats, catName,
    saveBeat, deleteBeat, importShared,
    createCategory, deleteCategory, toggleBeatInCategory, reorderCategory,
    loading, error, dismissError,
  };
}
