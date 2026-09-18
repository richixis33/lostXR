-- PhoneXR: profiles with a unique username and a friends list.
-- Run once in Supabase → SQL Editor. Only signed-in PhoneXR users can read or change anything.

create table if not exists public.phonexr_profiles (
  id uuid primary key references auth.users on delete cascade,
  username text unique not null check (username ~ '^[a-z0-9_.]{3,20}$'),
  display_name text not null default '',
  created_at timestamptz not null default now()
);
alter table public.phonexr_profiles enable row level security;
drop policy if exists "phonexr profiles readable" on public.phonexr_profiles;
create policy "phonexr profiles readable" on public.phonexr_profiles for select to authenticated using (true);
drop policy if exists "phonexr own profile insert" on public.phonexr_profiles;
create policy "phonexr own profile insert" on public.phonexr_profiles for insert to authenticated with check (auth.uid() = id);
drop policy if exists "phonexr own profile update" on public.phonexr_profiles;
create policy "phonexr own profile update" on public.phonexr_profiles for update to authenticated using (auth.uid() = id);

create table if not exists public.phonexr_friends (
  user_id uuid not null references auth.users on delete cascade,
  friend_id uuid not null references auth.users on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);
alter table public.phonexr_friends enable row level security;
drop policy if exists "phonexr friends read" on public.phonexr_friends;
create policy "phonexr friends read" on public.phonexr_friends for select to authenticated using (auth.uid() = user_id or auth.uid() = friend_id);
drop policy if exists "phonexr friends add" on public.phonexr_friends;
create policy "phonexr friends add" on public.phonexr_friends for insert to authenticated with check (auth.uid() = user_id);
drop policy if exists "phonexr friends remove" on public.phonexr_friends;
create policy "phonexr friends remove" on public.phonexr_friends for delete to authenticated using (auth.uid() = user_id);
