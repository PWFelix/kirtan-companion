# Supabase backend

The cloud side of Kirtan Companion: accounts, per-user beat/playlist storage,
and the community library. The app runs fine **without** any of this — with no
keys set it falls back to on-device `localStorage` and simply doesn't offer the
cloud features. Set the two keys below to turn them on.

## One-time setup

1. **Create a project** at <https://supabase.com> (the free tier is plenty).

2. **Create the tables.** Open the project's **SQL editor**, paste the whole of
   [`schema.sql`](./schema.sql), and run it. It's idempotent — safe to re-run.

3. **Add the keys.** In the app's repo root, create `.env.local` (git-ignored):

   ```
   VITE_SUPABASE_URL=https://YOUR-PROJECT.supabase.co
   VITE_SUPABASE_ANON_KEY=YOUR-ANON-PUBLIC-KEY
   ```

   Both values are under **Project Settings → API**. The *anon public* key is
   meant for the browser; Row-Level Security is what protects data (see below),
   not the secrecy of that key. Never put the **service_role** key in a `VITE_`
   variable — that one bypasses every rule.

4. **Turn on auth providers** (Dashboard → Authentication → Providers):
   - **Email** is on by default. For quick local testing, turn *off* "Confirm
     email" so a new sign-up is usable immediately.
   - **Google** (optional): enable the provider and paste an OAuth client
     id/secret from the Google Cloud console. Skip this and the app's Google
     button just won't be shown.

5. Restart `npm run dev` so Vite picks up the new env vars.

## What the schema sets up

| Table             | Holds                                   | Who can read / write                 |
| ----------------- | --------------------------------------- | ------------------------------------ |
| `profiles`        | per-user prefs (active playlist, name)  | only the user themselves             |
| `beats`           | a user's private custom beats           | only the owner                       |
| `playlists`       | a user's private kirtan progressions    | only the owner                       |
| `published_beats` | the public community library            | **anyone reads**, only author writes |

Privacy is enforced by **Row-Level Security** policies in `schema.sql`, not by
the app. Even with the anon key in hand, one user cannot read another's private
beats — the database refuses the row.

## Notes

- **Built-in beats never touch the database.** They're compiled into the app
  and merged in above the storage layer, so a playlist's `beat_ids` can mix a
  built-in id (`"te-ta"`) with a custom beat's uuid — which is why that column
  is `text[]`, not a foreign key.
- **Published beats are snapshots.** The full payload is copied into
  `published_beats.payload`, so editing your private copy later doesn't rewrite
  what the community already has.
