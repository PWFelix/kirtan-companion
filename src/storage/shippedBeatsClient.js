/**
 * shippedBeatsClient.js
 * ---------------------
 * The server side of the built-in beat set: reading `shipped_beats`, the
 * maintainer probe, and the one write — promoting a community beat into it.
 *
 * WHY IT ISN'T A BeatsProvider. A provider holds one user's work behind an
 * identity; this table holds what the app SHIPS, is world-readable (RLS
 * `using (true)`, so the anon key works signed out) and is written by two
 * people. It is the same kind of surface as communityClient, and for the same
 * reason it stays outside the provider seam.
 *
 * WHY IT ISN'T IN communityClient. `published_beats` is what anyone shares;
 * `shipped_beats` is what everyone gets. They meet in exactly one place —
 * promoteShippedBeat, which copies a beat from the first into the second —
 * and the two otherwise have opposite write rules (only the author / only a
 * maintainer). Keeping them apart is what makes that one crossing obvious.
 *
 * PERMISSIONS LIVE IN THE DATABASE. `is_maintainer()` gates the write
 * policies, so nothing here decides who may promote; the UI hides the button
 * for a non-maintainer purely so it doesn't offer something that will fail.
 * A refusal therefore has to come back as a sentence about permission, which
 * is what mapError's 42501 branch is for.
 */

import { supabase } from "./supabaseClient.js";
import { StorageError } from "./BeatsProvider.js";
import { shippedIdFor, nextOrdinal, shippedRowForBeat } from "../data/shippedBeats.js";

function ensureClient() {
  if (!supabase) {
    throw new StorageError("unavailable", "The built-in beat set isn't served in this build.");
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The clock-skew race communityClient and supabaseProvider both handle: a
// token minted a second ago can read as "issued in the future" to a database
// node running behind, which hits an authenticated call made right after
// sign-in. The two reads here are anon and immune; the maintainer probe and
// the promote are not.
function isClockSkew(err) {
  const msg = (err?.message || "").toLowerCase();
  return msg.includes("issued at future") || (msg.includes("jwt") && msg.includes("future"));
}

function mapError(error, whileDoing) {
  const code = error?.code;
  if (code === "42501" || error?.status === 401 || error?.status === 403) {
    return new StorageError("unavailable", "That needs a maintainer's account — this sign-in can't edit the built-in beats.", error);
  }
  // An id collision can only be a genuine race (two maintainers promoting the
  // same slug between this call's read and its write), since the id is chosen
  // against the set as it was a moment ago.
  if (code === "23505") return new StorageError("conflict", "Something else claimed that beat's id a moment ago. Try again.", error);
  return new StorageError("unknown", `Couldn't ${whileDoing}. ${error?.message ?? ""}`.trim(), error);
}

// `build` returns a fresh query, because a Supabase builder can only be
// awaited once and a retry has to rebuild it. The clock-skew retry and the
// rejected-fetch / returned-error split are the ones the two neighbouring
// clients use; they stay local rather than being extracted because each of the
// three words its failures for its own surface, and the shared part is small.
async function run(build, whileDoing) {
  ensureClient();
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await build();
    } catch (err) {
      if (attempt < 1 && isClockSkew(err)) { await sleep(2000); continue; }
      throw new StorageError("network", `Couldn't reach the server, so ${whileDoing} failed. Check your connection.`, err);
    }
    if (res.error) {
      if (attempt < 1 && isClockSkew(res.error)) { await sleep(2000); continue; }
      throw mapError(res.error, whileDoing);
    }
    return res.data;
  }
}

// The columns the derivation reads, named rather than `select("*")`: this is
// the row shape both platforms agreed on, and spelling it out keeps a new
// column from entering the cached blob before either half knows what it means.
const COLUMNS =
  "id, ordinal, heading, name, note, bpm, groups, cpq, lanes, description, source_published_id, updated_at";

/**
 * The built-in set as the table holds it, ordered by `ordinal`.
 *
 * RAW rows, deliberately. Validation belongs to data/shippedBeats.js and is
 * applied by the caller, because the exact same check has to run on the cached
 * copy: two validators would be two definitions of a usable set, and the copy
 * on disk would end up trusted more than the server's.
 */
export async function fetchShippedRows() {
  const rows = await run(
    () => supabase.from("shipped_beats").select(COLUMNS).order("ordinal", { ascending: true }),
    "load the built-in beats",
  );
  return rows ?? [];
}

/**
 * Is the signed-in user one of the app's maintainers?
 *
 * `maintainers` is select-only for its own rows and has no write policy at
 * all, so RLS answers this: a maintainer gets their row, everyone else gets
 * an empty array rather than an error. A limit of 1 because the only question
 * is whether the set is empty.
 */
export async function checkMaintainer() {
  const rows = await run(
    () => supabase.from("maintainers").select("user_id").limit(1),
    "check your maintainer access",
  );
  return Array.isArray(rows) && rows.length > 0;
}

/**
 * Promote a community beat into the built-in set, and return the row written.
 *
 * `beat` is the published payload's beat; `publishedId` goes into
 * `source_published_id` so the row keeps its provenance — which community beat
 * this came from, and therefore who made it.
 *
 * THE SET IS READ FRESH rather than taken from the one on screen, and that is
 * load-bearing. `id` is the primary key and the write is an upsert, so
 * choosing an id against a stale list can reuse one and silently OVERWRITE a
 * beat that ships to everybody: a community beat called "Double Time 2" slugs
 * to the compiled beat's own id, and the maintainer would see a success. The
 * ordinal has the same problem in a milder form — two promotes off one read
 * both claim it, and the second beat lands in the middle of the set instead of
 * at the end.
 */
export async function promoteShippedBeat(beat, { heading, publishedId }) {
  const current = await fetchShippedRows();
  // Every id the table holds, valid rows or not: an id in a row this build
  // can't parse is still taken, and reusing it would still overwrite.
  const taken = new Set(
    current.map((r) => r?.id).filter((id) => typeof id === "string" && id !== ""),
  );

  const row = shippedRowForBeat(beat, {
    id: shippedIdFor(beat?.name, taken),
    ordinal: nextOrdinal(current),
    heading,
    sourcePublishedId: publishedId ?? null,
  });
  if (!row) {
    // shippedRowForBeat refuses a beat it can't represent, rather than write a
    // row the other platform would reject and so fall back on — see its header.
    throw new StorageError("unknown", "That beat can't become a built-in one — its name or its pattern is outside what the built-in set allows.");
  }

  // Upsert on the primary key so a promote whose response was lost (the write
  // landed, the connection dropped) can be run again instead of failing on a
  // duplicate. The row always carries both nullable columns, so a re-run
  // replaces the row wholesale rather than merging stale prose into it — see
  // shippedRowForBeat. `updated_at` is trigger-maintained and is never sent.
  await run(
    () => supabase.from("shipped_beats").upsert(row, { onConflict: "id" }),
    "add that to the built-in beats",
  );
  return row;
}
