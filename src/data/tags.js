/**
 * tags.js
 * -------
 * The curated tag vocabulary. Fixed for now: free-text tags drift ("keherwa",
 * "kaherva", "8 beat") and can't drive the community filter. Authors tag their
 * own beats (published with the beat, in `beat.tags`); anyone can add PRIVATE
 * tags to any beat (on this device only — see useBeatLibrary's privateTags).
 *
 * Ids are the wire format, stable forever; labels are i18n keys. A tag id this
 * build doesn't know (added by a later version) is kept on the beat and simply
 * not shown, so nothing is lost on a round trip through an older app.
 * Mirrors Android's TagVocabulary.kt and iOS's TagVocabulary.swift.
 */

import { t } from "../i18n/index.js";

const group = (id, tags) => ({ id, labelKey: `tags.group.${id}`, tags: tags.map((tag) => ({ id: tag, labelKey: `tags.${tag}` })) });

export const TAG_GROUPS = [
  group("tala", ["keherwa", "dadra", "jhaptal", "rupak", "teental"]),
  group("feel", ["steady", "double-time", "laid-back", "build-up"]),
  group("use", ["kirtan", "bhajan", "arati", "prayers"]),
  group("part", ["main", "fill", "turnaround", "ending"]),
  group("level", ["beginner", "intermediate", "advanced"]),
];

export const ALL_TAGS = TAG_GROUPS.flatMap((g) => g.tags);
export const ALL_TAG_IDS = ALL_TAGS.map((tag) => tag.id);

/** `ids` in vocabulary order, unknown ones dropped — for display. */
export function knownTags(ids) {
  const wanted = new Set(ids ?? []);
  return ALL_TAGS.filter((tag) => wanted.has(tag.id));
}

/** "Keherwa · Kirtan", or "" when none are known. */
export function tagLine(ids) {
  return knownTags(ids).map((tag) => t(tag.labelKey)).join(" · ");
}

/** Clean a stored tag list: strings of 1–40 chars, no repeats, at most 16. */
export function cleanTags(value) {
  if (!Array.isArray(value)) return [];
  const out = [];
  for (const tag of value) {
    if (typeof tag === "string" && tag.length >= 1 && tag.length <= 40 && !out.includes(tag)) out.push(tag);
    if (out.length === 16) break;
  }
  return out;
}
