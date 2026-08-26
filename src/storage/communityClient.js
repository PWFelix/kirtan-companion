/**
 * communityClient.js
 * ------------------
 * The PUBLIC side of the backend: the community library that the Browse page
 * shows. Separate from BeatsProvider because it is a different surface with
 * different rules — anyone may READ it (RLS policy `using (true)`), only the
 * author may write — so it isn't per-user storage and doesn't belong behind
 * the provider seam.
 *
 * A published item is a SNAPSHOT. Publishing copies the whole shareable body
 * into `published_beats.payload` (the same content shareCodec puts in a link),
 * so editing your private beat afterwards never rewrites what the community
 * already downloaded. Copying one back is just an import: `toImportPayload`
 * hands the exact shape `useBeatLibrary.importShared` already knows how to add.
 *
 * These are plain functions over the shared `supabase` client rather than a
 * factory, because reads need no identity; publishing takes the author's id
 * and name explicitly.
 */

import { supabase } from "./supabaseClient.js";
import { StorageError } from "./BeatsProvider.js";

function ensureClient() {
  if (!supabase) {
    throw new StorageError("unavailable", "The community library isn't set up in this build.");
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Same clock-skew race as the provider: a brand-new sign-in token can look
// "issued at future" to a database node a second behind. One delayed retry.
function isClockSkew(err) {
  const msg = (err?.message || "").toLowerCase();
  return msg.includes("issued at future") || (msg.includes("jwt") && msg.includes("future"));
}

// `build` returns a fresh query (a Supabase builder can only be awaited once,
// so a retry has to rebuild it).
async function run(build, whileDoing) {
  ensureClient();
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await build();
    } catch (err) {
      if (attempt < 1 && isClockSkew(err)) { await sleep(2000); continue; }
      throw new StorageError("network", `Couldn't reach the community library, so ${whileDoing} failed. Check your connection.`, err);
    }
    if (res.error) {
      if (attempt < 1 && isClockSkew(res.error)) { await sleep(2000); continue; }
      throw new StorageError("unknown", `Couldn't ${whileDoing}. ${res.error.message ?? ""}`.trim(), res.error);
    }
    return res.data;
  }
}

/**
 * Publish a beat or a playlist to the community library.
 *
 * `target` is the same object the share sheet builds:
 *   { kind: "beat", beat } | { kind: "category", name, beats }
 * (the app's internal word is "category"; the table's is "playlist").
 */
export async function publish(target, { authorId, authorName }) {
  const isBeat = target.kind === "beat";
  const row = {
    author_id: authorId,
    author_name: authorName || "A devotee",
    kind: isBeat ? "beat" : "playlist",
    name: isBeat ? target.beat.name : target.name,
    payload: isBeat ? { beat: target.beat } : { name: target.name, beats: target.beats },
  };
  const data = await run(
    () => supabase.from("published_beats").insert(row).select("id, author_name, kind, name, copies, created_at").single(),
    "publish that",
  );
  return data;
}

/**
 * The community list, newest first. `query` full-text-matches the name;
 * `limit` caps the page. Returns rows with their payload so a card can preview
 * the real pattern without a second request.
 */
export async function browse({ query = "", limit = 40 } = {}) {
  const build = () => {
    let q = supabase
      .from("published_beats")
      .select("id, author_name, kind, name, payload, copies, created_at")
      .order("created_at", { ascending: false })
      .limit(limit);
    if (query.trim()) q = q.ilike("name", `%${query.trim()}%`);
    return q;
  };
  return (await run(build, "load the community library")) ?? [];
}

/** Everything the signed-in user has published, so they can unpublish it. */
export async function myPublished(authorId) {
  const build = () => supabase
    .from("published_beats")
    .select("id, kind, name, copies, created_at")
    .eq("author_id", authorId)
    .order("created_at", { ascending: false });
  return (await run(build, "load your shared beats")) ?? [];
}

/** Remove a published item. RLS lets only its author through. */
export async function unpublish(id) {
  await run(() => supabase.from("published_beats").delete().eq("id", id), "unpublish that");
}

/** Bump the copy counter when someone adds a community item to their library. */
export async function incrementCopies(id) {
  // Best-effort: a failed count must never block the copy that succeeded.
  try {
    await supabase.rpc("increment_published_copies", { pub_id: id });
  } catch (err) {
    console.warn("[community] copy count not bumped", err);
  }
}

/**
 * Turn a published row into the payload `importShared` consumes. The table's
 * "playlist" becomes the app's "category" here — the one place the two names
 * meet.
 */
export function toImportPayload(row) {
  if (row.kind === "beat") return { kind: "beat", beat: row.payload.beat };
  return { kind: "category", name: row.payload.name, beats: row.payload.beats };
}
