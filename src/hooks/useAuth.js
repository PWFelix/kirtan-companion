import { useState, useEffect, useCallback } from "react";
import { supabase, isSupabaseConfigured } from "../storage/supabaseClient.js";

/**
 * useAuth — the signed-in user, and the handful of ways in and out.
 *
 * It is a thin mirror of Supabase's auth: `getSession` for the state on load,
 * `onAuthStateChange` to follow sign-ins/outs (including the one that lands
 * back from a Google redirect), and pass-through methods that return a plain
 * `{ error }` string the UI can show without knowing anything about Supabase.
 *
 * WHEN THE PROJECT ISN'T CONFIGURED (no keys, e.g. a fresh clone), there is no
 * client. Rather than break, the hook reports `configured: false`, never
 * loads, and the app runs signed-out on localStorage. Every cloud affordance
 * checks `configured` before offering itself.
 *
 * `user.id` is the seam to storage: App builds the Supabase provider from it,
 * so a sign-in swaps the whole library from device to cloud (see App.jsx).
 */
export function useAuth() {
  const [session, setSession] = useState(null);
  // Only "loading" when there's a client to ask; otherwise resolved at once.
  const [loading, setLoading] = useState(isSupabaseConfigured);

  useEffect(() => {
    if (!supabase) return;
    let active = true;
    supabase.auth.getSession().then(({ data }) => {
      if (!active) return;
      setSession(data.session ?? null);
      setLoading(false);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, s) => {
      setSession(s);
    });
    return () => {
      active = false;
      sub.subscription.unsubscribe();
    };
  }, []);

  // Turn a Supabase auth error into a sentence, or null on success.
  const wrap = (result) => ({ error: result?.error ? result.error.message : null });

  const signInWithPassword = useCallback(async (email, password) => {
    if (!supabase) return { error: "Cloud accounts aren't set up in this build." };
    return wrap(await supabase.auth.signInWithPassword({ email, password }));
  }, []);

  const signUpWithPassword = useCallback(async (email, password, name) => {
    if (!supabase) return { error: "Cloud accounts aren't set up in this build." };
    return wrap(await supabase.auth.signUp({
      email, password,
      options: { data: name ? { name } : undefined },
    }));
  }, []);

  const signInWithGoogle = useCallback(async () => {
    if (!supabase) return { error: "Cloud accounts aren't set up in this build." };
    return wrap(await supabase.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: window.location.origin },
    }));
  }, []);

  const signOut = useCallback(async () => {
    if (!supabase) return;
    await supabase.auth.signOut();
  }, []);

  const user = session?.user ?? null;
  const displayName =
    user?.user_metadata?.name ||
    user?.user_metadata?.full_name ||
    user?.email?.split("@")[0] ||
    null;

  return {
    configured: isSupabaseConfigured,
    loading,
    user,
    displayName,
    signInWithPassword,
    signUpWithPassword,
    signInWithGoogle,
    signOut,
  };
}
