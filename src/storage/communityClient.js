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

import { t } from "../i18n/index.js";
import { supabase } from "./supabaseClient.js";
import { StorageError } from "./BeatsProvider.js";

function ensureClient() {
  if (!supabase) {
    throw new StorageError("unavailable", t("community.notConfigured"));
  }
}

/**
 * A beat as it goes out: no local id and none of the library's bookkeeping
 * about where it came from (another item's id would make other people's
 * "In your library" wrong).
 */
export function publishable(beat) {
  // eslint-disable-next-line no-unused-vars
  const { id, sourceItemId, sourceIndex, forkedFrom, readOnly, ...rest } = beat;
  return rest;
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
      throw new StorageError("network", t("community.offline", { action: t(whileDoing) }), err);
    }
    if (res.error) {
      if (attempt < 1 && isClockSkew(res.error)) { await sleep(2000); continue; }
      throw new StorageError("unknown", `${t("community.failed", { action: t(whileDoing) })} ${res.error.message ?? ""}`.trim(), res.error);
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
    payload: isBeat ? { beat: publishable(target.beat) } : { name: target.name, beats: target.beats.map(publishable) },
  };
  const data = await run(
    () => supabase.from("published_beats").insert(row).select("id, author_name, kind, name, copies, created_at").single(),
    "act.publish",
  );
  return data;
}

/** The orders Community browse offers; "top" is the default. */
export const SORTS = ["top", "new", "copies"];

const BROWSE_COLUMNS = "id, author_name, kind, name, payload, copies, vote_count, created_at";

/**
 * The community list. `query` matches the name, `tag` the beat's author tags,
 * `sort` is one of SORTS ("top" = votes decayed by age — see browse_published
 * in supabase/schema.sql). Returns rows with their payload so a card can
 * preview the real pattern without a second request.
 *
 * A database that hasn't had the votes schema run yet has no
 * browse_published: fall back to the plain table query (newest or most added,
 * no vote counts) rather than show an error.
 */
export async function browse({ query = "", tag = null, sort = "top", limit = 40 } = {}) {
  ensureClient();
  const res = await supabase.rpc("browse_published", {
    q: query.trim() || null, tag, sort, lim: limit,
  }).select(BROWSE_COLUMNS);
  if (!res.error) return res.data ?? [];
  if (res.error.code !== "PGRST202" && res.error.code !== "42883") {
    return run(() => Promise.resolve(res), "act.loadCommunity");
  }
  const build = () => {
    let q = supabase
      .from("published_beats")
      .select("id, author_name, kind, name, payload, copies, created_at")
      .order(sort === "copies" ? "copies" : "created_at", { ascending: false })
      .limit(limit);
    if (query.trim()) q = q.ilike("name", `%${query.trim()}%`);
    // JSON containment on the snapshot's tags (so playlists drop out).
    if (tag) q = q.filter("payload->beat->tags", "cs", JSON.stringify([tag]));
    return q;
  };
  return (await run(build, "act.loadCommunity")) ?? [];
}

/** Which of `itemIds` the user has upvoted, as a Set. */
export async function myVotes(userId, itemIds) {
  if (!itemIds.length) return new Set();
  const rows = await run(
    () => supabase.from("published_votes").select("item_id").eq("user_id", userId).in("item_id", itemIds),
    "act.loadCommunity",
  );
  return new Set((rows ?? []).map((r) => r.item_id));
}

/**
 * Upvote an item, or take the vote back. The count on the item follows by
 * trigger, so there's nothing else to update.
 */
export async function vote(itemId, userId, on) {
  await run(
    () => on
      ? supabase.from("published_votes").upsert({ item_id: itemId, user_id: userId }, { onConflict: "item_id,user_id", ignoreDuplicates: true })
      : supabase.from("published_votes").delete().eq("item_id", itemId).eq("user_id", userId),
    "act.vote",
  );
}

/**
 * The current originals of the library's community beats: { itemId: [beats] }
 * (one beat for a beat item, the list's beats for a playlist). An id missing
 * from the result was unpublished. Readable signed out. Batched to keep URLs
 * short.
 */
export async function originals(itemIds) {
  const ids = [...new Set(itemIds)].sort();
  const out = {};
  for (let i = 0; i < ids.length; i += 50) {
    const batch = ids.slice(i, i + 50);
    const rows = (await run(
      () => supabase.from("published_beats").select("id, kind, payload").in("id", batch),
      "act.loadCommunity",
    )) ?? [];
    for (const row of rows) {
      const beats = row.kind === "beat" ? [row.payload?.beat] : row.payload?.beats;
      if (Array.isArray(beats) && beats.every(Boolean)) out[row.id] = beats.map(publishable);
    }
  }
  return out;
}

/** Everything the signed-in user has published, so they can unpublish it. */
export async function myPublished(authorId) {
  const build = () => supabase
    .from("published_beats")
    .select("id, kind, name, copies, created_at")
    .eq("author_id", authorId)
    .order("created_at", { ascending: false });
  return (await run(build, "act.loadShared")) ?? [];
}

/** The user's published BEATS with their snapshots, for the Share status. */
export async function myPublishedBeats(authorId) {
  const build = () => supabase
    .from("published_beats")
    .select("id, payload")
    .eq("author_id", authorId)
    .eq("kind", "beat");
  const rows = (await run(build, "act.loadShared")) ?? [];
  return rows.filter((r) => r.payload?.beat).map((r) => ({ id: r.id, beat: r.payload.beat }));
}

/**
 * Replace the snapshot of a beat the user already published ("Share updated
 * version"), keeping the item's id, copies and votes. RLS lets only its
 * author through.
 */
export async function updatePublishedBeat(id, beat) {
  await run(
    () => supabase.from("published_beats").update({ name: beat.name, payload: { beat: publishable(beat) } }).eq("id", id),
    "act.publish",
  );
}

/** Remove a published item. RLS lets only its author through. */
export async function unpublish(id) {
  await run(() => supabase.from("published_beats").delete().eq("id", id), "act.unpublish");
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
