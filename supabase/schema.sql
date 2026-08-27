-- ════════════════════════════════════════════════════════════════════════
--  Kirtan Companion — Supabase schema
-- ════════════════════════════════════════════════════════════════════════
--  Run this ONCE in your project's SQL editor (Supabase dashboard → SQL) or
--  via `supabase db push`. It is idempotent — safe to re-run.
--
--  It creates four tables and the Row-Level Security (RLS) policies that make
--  the app's privacy model real:
--    profiles        one row per user  — preferences (active playlist, name)
--    beats           a user's PRIVATE custom beats
--    playlists       a user's PRIVATE kirtan progressions
--    published_beats the PUBLIC community library (world-readable snapshots)
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
-- key: built-in beats are compiled into the app, not rows in this database.
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
  created_at  timestamptz not null default now()
);
create index if not exists published_created_idx on public.published_beats(created_at desc);
-- Full-text index on the name so Browse can search a growing library cheaply.
create index if not exists published_name_idx
  on public.published_beats using gin (to_tsvector('simple', name));

-- ════════════════════════════════════════════════════════════════════════
--  Row-Level Security
-- ════════════════════════════════════════════════════════════════════════
alter table public.profiles        enable row level security;
alter table public.beats           enable row level security;
alter table public.playlists       enable row level security;
alter table public.published_beats enable row level security;

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
