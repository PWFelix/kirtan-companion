/**
 * storage/index.js
 * ----------------
 * THE INJECTION POINT. This is the one place that decides where the user's
 * beats actually live, and it is deliberately the smallest file in the
 * folder: swapping localStorage for something else is meant to be an edit
 * to the two lines below and nothing else in the app.
 *
 * It is a module singleton rather than a React context because
 * `useBeatLibrary` is the only consumer in the entire codebase — a context
 * today would be scaffolding for a problem nobody has. If the provider ever
 * needs to change WHILE THE APP IS RUNNING (signed out → local, signed in →
 * remote), that is when a context earns its keep, and it stays a one-file
 * change because the call sites all go through the hook.
 *
 * The hook takes the provider as an optional argument defaulting to this
 * one, so tests can hand it a fake without touching anything here.
 */

import { createLocalStorageProvider } from "./localStorageProvider.js";

export const beatsProvider = createLocalStorageProvider();

export { StorageError, storageErrorMessage } from "./BeatsProvider.js";
