/**
 * supabaseProvider.js
 * -------------------
 * The BeatsProvider backed by Supabase — the cloud twin of
 * localStorageProvider.js, implementing the SAME contract (see
 * BeatsProvider.js) so `useBeatLibrary` can't tell them apart.
 *
 * It is created WITH the signed-in user's id, because inserts must stamp
 * `user_id` for the Row-Level Security check to pass (`auth.uid() = user_id`).
 * Reads don't need the id — RLS already hides other people's rows — but we
 * filter by it anyway to hit the index and to read the same way we write.
 *
 * SHAPE ON THE WIRE. A beat's whole body lives in the `data` jsonb column;
 * `name` is duplicated into its own column purely so the database can sort and
 * search on it. A row becomes an app beat with `{ id, ...row.data }` — the id
 * is the column, everything else is the blob. Playlists are columnar
 * (`name`, `beat_ids`) because the app patches those fields independently.
 *
 * ERRORS. Every method turns a failure into a StorageError with one of the
 * codes the UI already handles, so a dropped connection or an RLS rejection
 * surfaces as a sentence a person can read — never a silent loss.
 */

import { supabase } from "./supabaseClient.js";
import { StorageError } from "./BeatsProvider.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The clock-skew race. Right after sign-in the access token's "issued at" is
 * NOW; if the database node validating it runs a second or two behind the node
 * that minted it, it reads the token as issued in the future and rejects the
 * request ("JWT issued at future"). It's a tiny difference between Supabase's
 * own servers and it clears itself within seconds, so one delayed retry hides
 * it — this fires on the very first read at sign-in more than anywhere.
 */
function isClockSkew(err) {
  const msg = (err?.message || "").toLowerCase();
  return msg.includes("issued at future") || (msg.includes("jwt") && msg.includes("future"));
}

// A Postgres/PostgREST failure, mapped to the code the app branches on.
function mapError(error, whileDoing) {
  const code = error?.code;
  if (code === "23505") return new StorageError("conflict", "That already exists.", error);
  if (code === "PGRST116") return new StorageError("notFound", `${whileDoing} no longer exists.`, error);
  if (code === "42501" || error?.status === 401 || error?.status === 403) {
    return new StorageError("unavailable", "You're signed out, so that couldn't be saved. Sign in and try again.", error);
  }
  return new StorageError("unknown", `Couldn't save ${whileDoing}. ${error?.message ?? ""}`.trim(), error);
}

/**
 * Run a Supabase query, converting a rejected fetch (offline) or a returned
 * `error` (rejected by the database) into a StorageError.
 *
 * `build` is a FUNCTION that returns a fresh query, not a query — a Supabase
 * query builder can only be awaited once, so retrying means rebuilding it.
 * On a clock-skew rejection it waits and rebuilds once before giving up.
 */
async function run(build, whileDoing) {
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await build();
    } catch (err) {
      if (attempt < 1 && isClockSkew(err)) { await sleep(2000); continue; }
      throw new StorageError("network", `Couldn't reach the server, so ${whileDoing} wasn't saved. Check your connection and try again.`, err);
    }
    if (res.error) {
      if (attempt < 1 && isClockSkew(res.error)) { await sleep(2000); continue; }
      throw mapError(res.error, whileDoing);
    }
    return res.data;
  }
}

const rowToBeat = (row) => ({ id: row.id, ...row.data });
const rowToCategory = (row) => ({ id: row.id, name: row.name, beatIds: row.beat_ids ?? [] });
// The body stored in jsonb — everything about the beat except its id (the id
// is the primary-key column, never duplicated into the blob).
const beatBody = ({ id, ...rest }) => rest; // eslint-disable-line no-unused-vars

export function createSupabaseProvider(userId) {
  if (!supabase) throw new Error("supabaseProvider needs a configured client");

  return {
    async loadAll() {
      const [beats, playlists, profile] = await Promise.all([
        run(() => supabase.from("beats").select("id, name, data").eq("user_id", userId).order("created_at"), "your beats"),
        run(() => supabase.from("playlists").select("id, name, beat_ids").eq("user_id", userId).order("created_at"), "your playlists"),
        // maybeSingle: a brand-new user may not have a profile row yet (the
        // trigger normally makes one). Absent → fall back to the built-ins.
        run(() => supabase.from("profiles").select("active_category_id").eq("id", userId).maybeSingle(), "your settings"),
      ]);
      return {
        beats: (beats ?? []).map(rowToBeat),
        categories: (playlists ?? []).map(rowToCategory),
        activeCategoryId: profile?.active_category_id || "builtin",
      };
    },

    async createBeat(draft) {
      const row = await run(
        () => supabase.from("beats").insert({ user_id: userId, name: draft.name, data: beatBody(draft) }).select("id, name, data").single(),
        "that beat",
      );
      return rowToBeat(row);
    },

    // One insert for the whole batch — see the note in BeatsProvider.js. A
    // single insert returns its rows in input order, so the mapping lines up.
    async createBeats(drafts) {
      if (drafts.length === 0) return [];
      const rows = await run(
        () => supabase.from("beats")
          .insert(drafts.map((d) => ({ user_id: userId, name: d.name, data: beatBody(d) })))
          .select("id, name, data"),
        "those beats",
      );
      return (rows ?? []).map(rowToBeat);
    },

    // The sole caller (saveBeat) hands over the complete beat body, so the
    // jsonb blob is replaced wholesale rather than merged.
    async updateBeat(id, patch) {
      const row = await run(
        () => supabase.from("beats").update({ name: patch.name, data: beatBody(patch) })
          .eq("id", id).eq("user_id", userId).select("id, name, data").single(),
        "that beat",
      );
      return rowToBeat(row);
    },

    async deleteBeat(id) {
      await run(() => supabase.from("beats").delete().eq("id", id).eq("user_id", userId), "that change");
    },

    async createCategory(draft) {
      const row = await run(
        () => supabase.from("playlists").insert({ user_id: userId, name: draft.name, beat_ids: draft.beatIds ?? [] })
          .select("id, name, beat_ids").single(),
        "that playlist",
      );
      return rowToCategory(row);
    },

    // A partial patch: only the columns present are touched, so updating
    // beat_ids leaves the name alone and vice-versa.
    async updateCategory(id, patch) {
      const rowPatch = {};
      if ("name" in patch) rowPatch.name = patch.name;
      if ("beatIds" in patch) rowPatch.beat_ids = patch.beatIds;
      const row = await run(
        () => supabase.from("playlists").update(rowPatch).eq("id", id).eq("user_id", userId)
          .select("id, name, beat_ids").single(),
        "that playlist",
      );
      return rowToCategory(row);
    },

    async deleteCategory(id) {
      await run(() => supabase.from("playlists").delete().eq("id", id).eq("user_id", userId), "that change");
    },

    // Upsert so the row is created on first use even if the new-user trigger
    // never ran — a preference should never fail for a missing profile.
    async setActiveCategory(id) {
      await run(
        () => supabase.from("profiles").upsert({ id: userId, active_category_id: id }, { onConflict: "id" }),
        "that change",
      );
    },
  };
}
