-- ════════════════════════════════════════════════════════════════════════
--  Kirtan Companion — Supabase schema
-- ════════════════════════════════════════════════════════════════════════
--  Run this ONCE in your project's SQL editor (Supabase dashboard → SQL) or
--  via `supabase db push`. It is idempotent — safe to re-run.
--
--  It creates seven tables and the Row-Level Security (RLS) policies that make
--  the app's privacy model real:
--    profiles          one row per user — preferences (active playlist, name)
--    beats             a user's PRIVATE custom beats
--    playlists         a user's PRIVATE kirtan progressions
--    published_beats   the PUBLIC community library (world-readable snapshots)
--    published_ratings one person's stars on one published item
--    maintainers       who may change the built-in beat set
--    shipped_beats     the CANONICAL built-in beat set, served to every client
--
--  RLS is the actual security boundary. The browser holds the anon key, so the
--  rules below — not the key — are what stop one user reading another's beats.
-- ════════════════════════════════════════════════════════════════════════

create extension if not exists pgcrypto;   -- gen_random_uuid()

-- ── profiles ────────────────────────────────────────────────────────────
-- Everything about a user that isn't a beat or a playlist. Today: which
-- playlist Home is cycling within (mirrors the old localStorage "active
-- category"), and the display name shown as the author on shared beats.
create table if not exists public.profiles (
  id                 uuid primary key references auth.users(id) on delete cascade,
  display_name       text,
  active_category_id text not null default 'builtin',
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

-- ── beats ───────────────────────────────────────────────────────────────
-- A user's own custom beats. The whole beat body (note, bpm, steps, dayan,
-- bayan, meter fields, description, …) rides in `data` as jsonb, so the editor
-- can grow new fields without a migration. `name` is promoted to a column
-- because the app sorts and de-duplicates on it.
create table if not exists public.beats (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  data       jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists beats_user_id_idx on public.beats(user_id);

-- ── playlists ───────────────────────────────────────────────────────────
-- The user's kirtan progressions. `beat_ids` is an ORDERED text array — the
-- order IS the progression — and holds BOTH built-in ids ("te-ta") and the
-- uuids of custom beats. That mix is why it's text[] and carries no foreign
-- key: a built-in id is a slug from `shipped_beats`, whose rows are served to
-- and cached by every client, so a progression has to survive one being renamed
-- or retired rather than cascading away with it.
create table if not exists public.playlists (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  beat_ids   text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists playlists_user_id_idx on public.playlists(user_id);

-- ── published_beats ─────────────────────────────────────────────────────
-- The community library. A published beat or playlist is a SNAPSHOT: the full
-- shareable payload (the same shape shareCodec encodes for a link) lives in
-- `payload`, so editing your private copy later never rewrites what you
-- shared. Anyone may read; only the author may publish, edit, or unpublish.
create table if not exists public.published_beats (
  id          uuid primary key default gen_random_uuid(),
  author_id   uuid not null references auth.users(id) on delete cascade,
  author_name text,
  kind        text not null check (kind in ('beat', 'playlist')),
  name        text not null,
  payload     jsonb not null,
  copies      integer not null default 0,
  created_at  timestamptz not null default now(),
  -- Denormalised from `published_ratings` and maintained by a trigger, so the
  -- Browse list can show an average without a join per card. A sum and a count
  -- rather than an average because the average of an empty set has to read as
  -- "not rated yet", and 0.0 would read as a verdict.
  rating_sum   integer not null default 0,
  rating_count integer not null default 0
);
-- For a project created before ratings existed: `create table if not exists`
-- above is a no-op on a table that is already there, so the columns need their
-- own idempotent step.
alter table public.published_beats add column if not exists rating_sum integer not null default 0;
alter table public.published_beats add column if not exists rating_count integer not null default 0;
create index if not exists published_created_idx on public.published_beats(created_at desc);
-- Full-text index on the name so Browse can search a growing library cheaply.
create index if not exists published_name_idx
  on public.published_beats using gin (to_tsvector('simple', name));


-- ── published_ratings ───────────────────────────────────────────────────
-- One person's stars on one published item. The unique pair is what makes
-- "tap your own star again to take it back" a DELETE rather than a zero: a
-- stored 0 would sit in the sum forever and drag the average down.
create table if not exists public.published_ratings (
  id         uuid primary key default gen_random_uuid(),
  item_id    uuid not null references public.published_beats(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  stars      integer not null check (stars between 1 and 5),
  created_at timestamptz not null default now(),
  unique (item_id, user_id)
);
create index if not exists published_ratings_item_idx on public.published_ratings(item_id);


-- ── maintainers ─────────────────────────────────────────────────────────
-- Who may change the built-in beat set. DELIBERATELY HAS NO WRITE POLICY:
-- only the service role (dashboard, CLI, MCP) can add or remove a maintainer,
-- so neither maintainer can grant it to the other and no bug in a client can
-- promote itself. Adding a person is an operator act, not a feature.
create table if not exists public.maintainers (
  user_id  uuid primary key references auth.users(id) on delete cascade,
  note     text,
  added_at timestamptz not null default now()
);


-- ── shipped_beats ───────────────────────────────────────────────────────
-- The canonical built-in beat set. Every client reads this at launch and falls
-- back to the list compiled into its own build only when the read fails, which
-- is what lets the two people who maintain the beats correct a pattern for
-- every installed app without a rebuild or a reinstall.
--
-- `id` is a stable slug and must never be reused: playlists store built-in ids,
-- so re-slugifying a beat orphans every progression that referenced it.
create table if not exists public.shipped_beats (
  id                  text primary key,
  ordinal             integer not null default 0,
  heading             text not null,
  name                text not null,
  note                text not null default '',
  bpm                 integer not null check (bpm between 40 and 200),
  groups              integer[] not null check (cardinality(groups) between 1 and 32),
  cpq                 integer not null check (cpq between 1 and 12),
  -- { "dayan": "X-OO…", "bayan": "…", "kartal": "…" } in the share format's
  -- compact notation. A lane key is ABSENT when the beat has no cymbals, which
  -- is not the same thing as an all-rest lane.
  lanes               jsonb not null,
  description         text,
  -- Which community snapshot this was promoted from, if any. Provenance only;
  -- set null if that snapshot is unpublished.
  source_published_id uuid references public.published_beats(id) on delete set null,
  updated_at          timestamptz not null default now()
);
create index if not exists shipped_beats_ordinal_idx on public.shipped_beats(ordinal);

-- ════════════════════════════════════════════════════════════════════════
--  Row-Level Security
-- ════════════════════════════════════════════════════════════════════════
alter table public.profiles          enable row level security;
alter table public.beats             enable row level security;
alter table public.playlists         enable row level security;
alter table public.published_beats   enable row level security;
alter table public.published_ratings enable row level security;
alter table public.maintainers       enable row level security;
alter table public.shipped_beats     enable row level security;

-- profiles / beats / playlists: a user touches only their own rows. One
-- "for all" policy covers select/insert/update/delete; `using` guards reads
-- and deletes, `with check` guards writes.
drop policy if exists "profiles are self-owned" on public.profiles;
create policy "profiles are self-owned" on public.profiles
  for all using (auth.uid() = id) with check (auth.uid() = id);

drop policy if exists "beats are owner-only" on public.beats;
create policy "beats are owner-only" on public.beats
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "playlists are owner-only" on public.playlists;
create policy "playlists are owner-only" on public.playlists
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- published_beats: world-readable, author-writable. Split into per-command
-- policies because the read rule (true) differs from the write rule (owner).
drop policy if exists "published are world-readable" on public.published_beats;
create policy "published are world-readable" on public.published_beats
  for select using (true);

drop policy if exists "published insert by author" on public.published_beats;
create policy "published insert by author" on public.published_beats
  for insert with check (auth.uid() = author_id);

drop policy if exists "published update by author" on public.published_beats;
create policy "published update by author" on public.published_beats
  for update using (auth.uid() = author_id) with check (auth.uid() = author_id);

drop policy if exists "published delete by author" on public.published_beats;
create policy "published delete by author" on public.published_beats
  for delete using (auth.uid() = author_id);

-- published_ratings: anyone may read a rating (the average is public), only its
-- author may write or retract their own. One "for all" policy covers the write
-- verbs; the unique (item_id, user_id) pair is what stops a second star row.
drop policy if exists "ratings are world-readable" on public.published_ratings;
create policy "ratings are world-readable" on public.published_ratings
  for select using (true);

drop policy if exists "ratings are own-only" on public.published_ratings;
create policy "ratings are own-only" on public.published_ratings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- maintainers: readable by the person themselves, and by NOBODY else. The rule
-- is `user_id = auth.uid()` rather than a lookup of "is auth.uid() in
-- maintainers?" — that phrasing selects FROM the table the policy is on, which
-- re-applies the policy and recurses until Postgres refuses the query outright
-- (42P17 "infinite recursion detected in policy"). It also means a maintainer
-- cannot enumerate the others; who else may edit the beats is a dashboard
-- question, and the app only ever asks "did any row come back?".
drop policy if exists "maintainers can read the list" on public.maintainers;
create policy "maintainers can read the list" on public.maintainers
  for select using (user_id = auth.uid());

-- No write policy at all: see the table's comment. Only the service role can
-- change who maintains the beats.
grant select on public.maintainers to authenticated;

-- shipped_beats: world-readable (a signed-out user still needs the built-in
-- beats — that is the whole point of serving them), maintainer-writable. One
-- "for all" policy covers insert/update/delete because the rule is identical
-- for all three.
drop policy if exists "shipped beats are world-readable" on public.shipped_beats;
create policy "shipped beats are world-readable" on public.shipped_beats
  for select using (true);

drop policy if exists "maintainers manage shipped beats" on public.shipped_beats;
create policy "maintainers manage shipped beats" on public.shipped_beats
  for all using (public.is_maintainer()) with check (public.is_maintainer());

grant select on public.shipped_beats to anon, authenticated;
grant insert, update, delete on public.shipped_beats to authenticated;

-- ════════════════════════════════════════════════════════════════════════
--  Triggers & functions
-- ════════════════════════════════════════════════════════════════════════

-- Keep updated_at honest without trusting the client to set it.
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists profiles_touch  on public.profiles;
drop trigger if exists beats_touch     on public.beats;
drop trigger if exists playlists_touch on public.playlists;
create trigger profiles_touch  before update on public.profiles  for each row execute function public.touch_updated_at();
create trigger beats_touch     before update on public.beats     for each row execute function public.touch_updated_at();
create trigger playlists_touch before update on public.playlists for each row execute function public.touch_updated_at();

-- Give every new user a profile row automatically, seeding the display name
-- from OAuth metadata or the local-part of their email. security definer so it
-- can write to public.profiles from the auth schema's insert.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'name',
             new.raw_user_meta_data->>'full_name',
             split_part(new.email, '@', 1))
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- Copying a community beat bumps its counter. RLS blocks a non-author from
-- UPDATE, so this runs as a security-definer RPC anyone signed in may call —
-- it can only ever increment the one counter, nothing else.
create or replace function public.increment_published_copies(pub_id uuid)
returns void language sql security definer set search_path = public as $$
  update public.published_beats set copies = copies + 1 where id = pub_id;
$$;
grant execute on function public.increment_published_copies(uuid) to authenticated;

-- Rating the community library keeps `rating_sum` / `rating_count` on the parent
-- row, so the Browse list needs no join per card. A trigger rather than client
-- arithmetic for two reasons: a read-modify-write from two people rating at once
-- loses a count, and a client that forgot to update the parent would leave an
-- average that silently disagrees with the stars. security definer because the
-- write lands on `published_beats`, whose RLS lets only the author through.
create or replace function public.recount_published_ratings()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'DELETE' then
    update public.published_beats
       set rating_sum = rating_sum - old.stars,
           rating_count = rating_count - 1
     where id = old.item_id;
    return old;
  elsif tg_op = 'UPDATE' then
    update public.published_beats
       set rating_sum = rating_sum - old.stars + new.stars
     where id = old.item_id;
    return new;
  else
    update public.published_beats
       set rating_sum = rating_sum + new.stars,
           rating_count = rating_count + 1
     where id = new.item_id;
    return new;
  end if;
end;
$$;

drop trigger if exists published_ratings_recount on public.published_ratings;
create trigger published_ratings_recount
  after insert or update or delete on public.published_ratings
  for each row execute function public.recount_published_ratings();

-- The gate on `shipped_beats`. SECURITY DEFINER so it answers for a caller who
-- cannot read `maintainers` at all — which is everyone except the maintainers
-- themselves. `stable` and a pinned search_path: it is called from inside RLS
-- policies, so it must not recurse and must not be steerable by a caller-set
-- search_path.
create or replace function public.is_maintainer()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.maintainers m where m.user_id = auth.uid())
$$;

-- Revoked from PUBLIC on purpose: a function used as a security gate should be
-- callable by the roles that need it and nobody else.
revoke all on function public.is_maintainer() from public;
grant execute on function public.is_maintainer() to anon, authenticated;

drop trigger if exists shipped_beats_touch on public.shipped_beats;
create trigger shipped_beats_touch
  before update on public.shipped_beats
  for each row execute function public.touch_updated_at();
