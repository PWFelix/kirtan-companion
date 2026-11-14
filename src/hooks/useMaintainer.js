import { useState, useEffect } from "react";
import { checkMaintainer } from "../storage/shippedBeatsClient.js";

/**
 * useMaintainer — whether the signed-in user may edit the built-in beat set.
 *
 * The flag decides ONE thing: whether the Promote button is drawn on a
 * community beat. Row-Level Security decides whether the write happens, so
 * this hook FAILS CLOSED and that is the whole design — a missed probe costs
 * a maintainer the button until they sign in again, while guessing "yes"
 * would cost nothing at all, because the write would be refused and the sheet
 * would say so in words.
 *
 * WHAT IS STORED IS THE LAST PROBE AND WHOSE IT WAS, rather than a bare
 * boolean. The answer is then derived (`probe.userId === userId`), which is
 * what makes a sign-out, or a different account signing in, read as "not a
 * maintainer" with no effect needed to clear the old answer: clearing state in
 * an effect body is a cascading render, and the comparison is free. It also
 * means a probe that resolves after its session ended can't grant the button
 * to whoever is signed in now.
 *
 * KEYED ON THE USER'S ID, not the user object, for the reason App's provider
 * memo gives: Supabase hands back a fresh object for the same session on a
 * token refresh, and re-probing on that would fire a query per refresh for no
 * new information. The id changes only on a real sign-in or sign-out, which is
 * exactly when the answer can change.
 *
 * NO SESSION → NO PROBE AT ALL. `maintainers` is select-only for its own rows,
 * so a signed-out query is a guaranteed empty answer; firing one every time the
 * Browse page renders would be a request storm that can only ever say "no".
 */
export function useMaintainer(auth) {
  const userId = auth?.user?.id ?? null;
  const [probe, setProbe] = useState(null);   // { userId, yes } | null

  useEffect(() => {
    if (!userId) return undefined;
    let cancelled = false;
    checkMaintainer()
      .then((yes) => { if (!cancelled) setProbe({ userId, yes }); })
      .catch((err) => {
        // Offline, or a token that hasn't settled: not a maintainer until
        // proven otherwise. The console line is for whoever wonders why the
        // button didn't appear.
        console.warn("[shipped] maintainer check failed", err);
        if (!cancelled) setProbe({ userId, yes: false });
      });
    return () => { cancelled = true; };
  }, [userId]);

  return probe?.userId === userId && probe.yes === true;
}
