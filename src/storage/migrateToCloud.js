/**
 * migrateToCloud.js
 * -----------------
 * Reading THIS DEVICE's local library, so it can be offered to a freshly
 * signed-in account (see useCloudMigration).
 *
 * The key point: `beatsProvider` is always the localStorage provider (index.js
 * builds it as the module singleton), even after the app has switched the
 * *active* provider to the cloud on sign-in. So this reads the guest library
 * off the device regardless of who's signed in — exactly what a "bring your
 * on-device beats up to your account" step needs.
 *
 * It only READS. The actual upload is useBeatLibrary.importLibrary, which owns
 * id-minting and keeps React state in sync; doing it here would duplicate that
 * and drift.
 */

import { beatsProvider } from "./index.js";

/** The device's saved beats and playlists (built-ins are never in here). */
export async function readLocalLibrary() {
  const { beats = [], categories = [] } = await beatsProvider.loadAll();
  return {
    beats: Array.isArray(beats) ? beats : [],
    categories: Array.isArray(categories) ? categories : [],
  };
}

/** True when there's anything worth offering to migrate. */
export function hasLocalContent({ beats, categories }) {
  return beats.length > 0 || categories.length > 0;
}
