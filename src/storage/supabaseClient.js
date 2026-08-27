/**
 * supabaseClient.js
 * -----------------
 * The one Supabase client for the whole app, plus the flag that says whether
 * a project is even configured.
 *
 * WHY IT CAN BE null. The keys come from .env.local, which is git-ignored and
 * absent on a fresh clone. Rather than throw on import — which would brick the
 * app for anyone doing local UI work without a project — the client is `null`
 * when unconfigured, and `isSupabaseConfigured` lets the rest of the app fall
 * back to the localStorage provider. The cloud features simply aren't offered.
 *
 * This is the ONLY file that calls createClient. Everything else (auth hook,
 * remote provider, community client) imports `supabase` from here, so there is
 * a single session and a single socket, and swapping config is one place.
 */

import { createClient } from "@supabase/supabase-js";

const url = import.meta.env.VITE_SUPABASE_URL;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

/** True only when both keys are present — the gate for every cloud feature. */
export const isSupabaseConfigured = Boolean(url && anonKey);

export const supabase = isSupabaseConfigured
  ? createClient(url, anonKey, {
      auth: {
        // Keep the user signed in across reloads, refresh tokens silently, and
        // pick up the session from the URL after an OAuth / magic-link redirect.
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
    })
  : null;
