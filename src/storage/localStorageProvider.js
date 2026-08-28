/**
 * localStorageProvider.js
 * -----------------------
 * The BeatsProvider backed by this device's localStorage.
 *
 * THIS FILE, migrate.js AND eqPrefs.js ARE THE ONLY PLACES IN THE APP THAT
 * MAY CALL THE localStorage API DIRECTLY. The word may appear in comments
 * elsewhere; what must not happen is a localStorage.getItem / .setItem call
 * outside src/storage/ — that is the layer failing at the one job it exists
 * to do.
 *
 * Two things worth knowing about the implementation:
 *
 *  - EVERY WRITE IS A READ-MODIFY-WRITE of a whole list, because
 *    localStorage has no notion of a row. That is fine here (a library is
 *    tens of beats, and there is exactly one tab writing) and it is
 *    deliberately hidden behind per-entity methods: the interface is shaped
 *    for the database this becomes, not for the key-value store it is.
 *
 *  - THE ERRORS ARE THE POINT. localStorage throws in two situations that
 *    both really happen: the origin is out of quota (~5MB), and storage is
 *    blocked entirely (Safari private browsing, or cookies disabled — where
 *    even READING throws). The code this replaced caught both and carried
 *    on, which is why a failed save used to look identical to a good one.
 */

import { StorageError, newId } from "./BeatsProvider.js";
import { migrateIfNeeded, KEYS } from "./migrate.js";

/** Turn a localStorage throw into the error code that describes it. */
function classify(err) {
  // Browsers disagree on how they report a full store; check all the spellings.
  const quota =
    err &&
    (err.name === "QuotaExceededError" ||
      err.name === "NS_ERROR_DOM_QUOTA_REACHED" ||
      err.code === 22 ||
      err.code === 1014);
  return quota ? "quota" : "unavailable";
}

function wrap(err, whileDoing) {
  const code = classify(err);
  const message =
    code === "quota"
      ? "There's no room left in this browser's storage, so that couldn't be saved. Deleting a few beats you don't use will free some up."
      : `This browser is blocking storage, so ${whileDoing} couldn't be saved. Private browsing windows often do this.`;
  return new StorageError(code, message, err);
}

export function createLocalStorageProvider() {
  // The migration mints random ids, so it is the one step that must not run
  // twice concurrently — StrictMode double-invokes effects, which means two
  // loadAll calls can be in flight at once. Caching the promise makes the
  // second call join the first instead of racing it. A REJECTED promise is
  // cached too, on purpose: if the migration failed we must not go on to
  // read half-migrated data.
  let migration = null;
  const ready = () => (migration ??= Promise.resolve().then(migrateIfNeeded));

  /** Read a JSON key. A corrupt value falls back rather than bricking the app. */
  function read(key, fallback) {
    let raw;
    try {
      raw = localStorage.getItem(key);
    } catch (err) {
      throw wrap(err, "your beats");
    }
    if (raw == null) return fallback;
    try {
      return JSON.parse(raw);
    } catch (err) {
      // Someone (or something) put a non-JSON value in our key. Losing it is
      // better than refusing to start, but say so in the console.
      console.warn(`[storage] ${key} was unreadable and has been ignored`, err);
      return fallback;
    }
  }

  function write(key, value, whileDoing) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (err) {
      throw wrap(err, whileDoing);
    }
  }

  const readBeats = () => {
    const v = read(KEYS.beats, []);
    return Array.isArray(v) ? v : [];
  };
  const readCategories = () => {
    const v = read(KEYS.categories, []);
    return Array.isArray(v) ? v : [];
  };

  return {
    async loadAll() {
      await ready();
      return {
        beats: readBeats(),
        categories: readCategories(),
        activeCategoryId: read(KEYS.activeCategory, "builtin"),
      };
    },

    async createBeat(draft) {
      await ready();
      const beat = { ...draft, id: newId() };
      write(KEYS.beats, [...readBeats(), beat], "that beat");
      return beat;
    },

    // One write for the whole batch — see the note in BeatsProvider.js.
    async createBeats(drafts) {
      await ready();
      const beats = drafts.map((d) => ({ ...d, id: newId() }));
      write(KEYS.beats, [...readBeats(), ...beats], "those beats");
      return beats;
    },

    async updateBeat(id, patch) {
      await ready();
      const beats = readBeats();
      const i = beats.findIndex((b) => b.id === id);
      if (i === -1) {
        throw new StorageError("notFound", "That beat no longer exists.");
      }
      const beat = { ...beats[i], ...patch, id };
      const next = beats.slice();
      next[i] = beat;
      write(KEYS.beats, next, "that beat");
      return beat;
    },

    async deleteBeat(id) {
      await ready();
      write(KEYS.beats, readBeats().filter((b) => b.id !== id), "that change");
    },

    async createCategory(draft) {
      await ready();
      const category = { beatIds: [], ...draft, id: newId() };
      write(KEYS.categories, [...readCategories(), category], "that list");
      return category;
    },

    async updateCategory(id, patch) {
      await ready();
      const cats = readCategories();
      const i = cats.findIndex((c) => c.id === id);
      if (i === -1) {
        throw new StorageError("notFound", "That list no longer exists.");
      }
      const category = { ...cats[i], ...patch, id };
      const next = cats.slice();
      next[i] = category;
      write(KEYS.categories, next, "that list");
      return category;
    },

    async deleteCategory(id) {
      await ready();
      write(KEYS.categories, readCategories().filter((c) => c.id !== id), "that change");
    },

    async setActiveCategory(id) {
      await ready();
      write(KEYS.activeCategory, id, "that change");
    },
  };
}
