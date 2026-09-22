# Supabase backend

The cloud side of Kirtan Companion: accounts, per-user beat/playlist storage,
the community library, and the built-in beat set both apps serve themselves at
launch. The app runs fine **without** any of this — with no keys set it falls
back to on-device storage (`localStorage` on the web, DataStore on Android) and
to the beats compiled into its own build, and simply doesn't offer the cloud
features. Set the two keys below to turn them on.

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

| Table               | Holds                                   | Who can read / write                  |
| ------------------- | --------------------------------------- | ------------------------------------- |
| `profiles`          | per-user prefs (active playlist, name)  | only the user themselves              |
| `beats`             | a user's private custom beats           | only the owner                        |
| `playlists`         | a user's private kirtan progressions    | only the owner                        |
| `published_beats`   | the public community library            | **anyone reads**, only author writes  |
| `published_ratings` | one person's 1–5 stars on one item      | **anyone reads**, only the rater writes |
| `maintainers`       | who may edit the built-in beat set      | only yourself; **nobody writes**      |
| `shipped_beats`     | the canonical built-in beat set         | **anyone reads**, only maintainers write |

Privacy is enforced by **Row-Level Security** policies in `schema.sql`, not by
the app. Even with the anon key in hand, one user cannot read another's private
beats — the database refuses the row.

## Notes

- **Built-in beats are served, with a compiled fallback.** `shipped_beats` is
  canonical: both apps read it at launch, cache the rows, and fall back to the
  list compiled into their own build only when the read fails. Correcting a
  pattern therefore reaches every installed app on its next launch, with no
  rebuild and no reinstall. The compiled lists (`src/data/beats.js` and
  `android/.../data/Beats.kt`) still exist, still have to agree with each other,
  and are still what an offline first launch plays — generate both from one
  source with `node scripts/generateBuiltinBeats.mjs`.
- **A playlist's `beat_ids` can mix a built-in slug with a custom beat's uuid**
  — which is why that column is `text[]`, not a foreign key. It is also why a
  `shipped_beats.id` must never be reused or re-slugified: renaming one silently
  orphans every progression that referenced it.
- **Published beats are snapshots.** The full payload is copied into
  `published_beats.payload`, so editing your private copy later doesn't rewrite
  what the community already has.
- **Ratings are counted by a trigger.** `rating_sum` / `rating_count` on
  `published_beats` are maintained by `recount_published_ratings()`, so two
  people rating at once can't lose a count to a read-modify-write, and a client
  can't leave an average that disagrees with the stars. A retracted rating is a
  DELETE, not a zero: a stored zero would sit in the sum forever.

## Maintaining the built-in beats

Two people do this, and neither needs a terminal for the common cases.

**Correct a beat that's already shipping.** Open it under *Beats → Built in* and
choose **Edit for everyone** — a maintainer-only action that sits next to
*Customize*, which still forks a private copy. Fix the cells, save, confirm. Every
install has it on its next launch. The row keeps its `id`, so progressions that
use the beat keep working **even if you rename it** — an id is forever, and
nothing anywhere can tell a stale reference from a live one. `ordinal`, `heading`,
`description` and `source_published_id` are carried over from the row, because the
editor has no field for any of them; `note` is re-derived from the group count so
it can't go stale when you change the meter.

**Add a new one.** Author or edit it in the app's editor, **publish it to the
community library** (or send it as a share link and publish that), then use
**Make this a built-in beat** on its card in Community. That asks for a section
heading and writes a new row with an id slugged from the name — suffixed if the
slug is taken, because a promote must never silently overwrite a beat that
already ships.

**Then regenerate the compiled fallbacks**, which is a developer step:

```sh
node scripts/generateBuiltinBeats.mjs --from-server   # writes beats.js + Beats.kt
node scripts/generateShareVectors.mjs                 # wire-compat fixtures
git diff                                              # should show only your change
```

`src/data/beats.js` and `android/.../data/Beats.kt` are what an offline launch
and a fresh install play before the first fetch, so they must not drift from the
table. Running the script against an unchanged table writes nothing and says so,
which makes it a drift check as well as a generator. Never hand-edit those two
files: a mistyped cell in a 48-character pattern changes the music silently and
nothing fails.

**Who counts as a maintainer** is `public.maintainers`. That table has **no write
policy**, deliberately: only the service role can change it, so neither
maintainer can grant it to the other and no bug in a client can promote itself.
Adding someone is an operator act — run this in the SQL editor, and note that
`maintainers` is readable only by the person themselves, so the app can answer
"may I edit the built-ins?" without exposing the list:

```sql
insert into public.maintainers (user_id, note)
select id, email from auth.users where email = 'someone@example.com'
on conflict (user_id) do nothing;
```

**Retiring a beat** has no in-app path yet:
`delete from public.shipped_beats where id = '…'`, then regenerate as above.
Clients drop it on their next check, and a playlist that pointed at it filters it
out rather than breaking. Don't reuse the id afterwards.
