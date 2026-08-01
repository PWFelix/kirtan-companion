import { useState } from "react";
import { arrayMove } from "@dnd-kit/sortable";
import { BEATS } from "../data/beats.js";

const SAVED_KEY = "kirtan-custom-beats";
const CATS_KEY = "kirtan-user-categories";
const ACTIVE_CAT_KEY = "kirtan-active-category";

const read = (key, fallback) => {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
  catch { return fallback; }
};
const write = (key, value) => {
  try { localStorage.setItem(key, JSON.stringify(value)); }
  catch { /* storage full or blocked — non-fatal, the session still works */ }
};

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
 * Kept apart from useTransport because none of it touches the engine: this
 * is pure data plus localStorage. The two meet only in App, which holds the
 * one piece of state that spans them — which beat is loaded.
 */
export function useBeatLibrary() {
  const [customBeats, setCustomBeats] = useState(() => read(SAVED_KEY, []));
  const [categories, setCategories] = useState(() => read(CATS_KEY, []));
  const [activeCat, setActiveCatState] = useState(
    () => localStorage.getItem(ACTIVE_CAT_KEY) || "builtin"
  );

  const allBeats = [...BEATS, ...customBeats];
  const isCustomBeat = (id) => customBeats.some(b => b.id === id);

  function setActiveCat(catId) {
    setActiveCatState(catId);
    try { localStorage.setItem(ACTIVE_CAT_KEY, catId); } catch { /* non-fatal */ }
  }

  function saveCategories(updated) {
    setCategories(updated);
    write(CATS_KEY, updated);
  }
  function saveBeats(updated) {
    setCustomBeats(updated);
    write(SAVED_KEY, updated);
  }

  /** The ordered beats of a category ("builtin" | "custom" | user cat id). */
  function categoryBeats(catId) {
    if (catId === "builtin") return BEATS;
    if (catId === "custom") return customBeats;
    const c = categories.find(c => c.id === catId);
    if (!c) return allBeats;
    return c.beatIds.map(id => allBeats.find(b => b.id === id)).filter(Boolean);
  }
  function catName(catId) {
    if (catId === "builtin") return "Built in";
    if (catId === "custom") return "Your beats";
    return categories.find(c => c.id === catId)?.name ?? "Beats";
  }

  // Upsert by id: editing a custom beat overwrites its entry, a new beat
  // (or a fork of a built-in) appends.
  function saveBeat(newBeat) {
    const exists = customBeats.some(b => b.id === newBeat.id);
    saveBeats(exists
      ? customBeats.map(b => (b.id === newBeat.id ? newBeat : b))
      : [...customBeats, newBeat]);
  }

  // Deleting a beat also drops it from any progression referencing it, so a
  // category can never point at a beat that no longer exists.
  function deleteBeat(id) {
    saveBeats(customBeats.filter(b => b.id !== id));
    if (categories.some(c => c.beatIds.includes(id))) {
      saveCategories(categories.map(c => ({ ...c, beatIds: c.beatIds.filter(x => x !== id) })));
    }
  }

  /** Creates the category and returns its id (callers land the user in it). */
  function createCategory(name) {
    const cat = { id: "cat_" + Date.now(), name, beatIds: [] };
    saveCategories([...categories, cat]);
    return cat.id;
  }
  function deleteCategory(catId) {
    saveCategories(categories.filter(c => c.id !== catId));
    if (activeCat === catId) setActiveCat("builtin");
  }
  function toggleBeatInCategory(catId, id) {
    saveCategories(categories.map(c => c.id !== catId ? c : {
      ...c,
      beatIds: c.beatIds.includes(id) ? c.beatIds.filter(x => x !== id) : [...c.beatIds, id],
    }));
  }
  // Reorder a progression from a dnd-kit drag end (arrayMove keeps it pure).
  function reorderCategory(catId, activeId, overId) {
    if (activeId === overId) return;
    saveCategories(categories.map(c => {
      if (c.id !== catId) return c;
      const from = c.beatIds.indexOf(activeId);
      const to = c.beatIds.indexOf(overId);
      if (from === -1 || to === -1) return c;
      return { ...c, beatIds: arrayMove(c.beatIds, from, to) };
    }));
  }

  return {
    customBeats, allBeats, isCustomBeat,
    categories, activeCat, setActiveCat, categoryBeats, catName,
    saveBeat, deleteBeat,
    createCategory, deleteCategory, toggleBeatInCategory, reorderCategory,
  };
}
