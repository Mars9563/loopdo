-- LoopDo 0001: roles, tables, triggers, row-level security.
-- Runs as `postgres` on Supabase Cloud (not a superuser). Idempotent where cheap.

-- ---------------------------------------------------------------------------
-- Roles
--   loopdo_user    NOLOGIN; holds all table rights; the API switches into it per
--                  request so RLS applies. Supabase's anon/authenticated get nothing,
--                  so Supabase's auto REST API can't bypass the LoopDo API.
--   loopdo_api     LOGIN, NOINHERIT; may only SET ROLE loopdo_user.
--   powersync_role LOGIN, REPLICATION, BYPASSRLS; read-only, for PowerSync Cloud.
-- Passwords are set outside migrations (scripts/db-migrate.sh).
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'loopdo_user') then
    create role loopdo_user nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'loopdo_api') then
    create role loopdo_api login noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'powersync_role') then
    create role powersync_role login replication bypassrls;
  end if;
end $$;

grant loopdo_user to loopdo_api with inherit false, set true;

-- The signed-in user, from the claims the API sets per transaction.
create or replace function public.current_user_id() returns uuid
language sql stable set search_path = '' as $$
  select nullif(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub', '')::uuid
$$;

create or replace function public.set_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- Tables. Every user table carries user_id; child tables reference their parent
-- by (id, user_id), so a row can never point at another user's row.
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  display_name text check (char_length(display_name) <= 80),
  onboarded_at timestamptz,
  is_admin     boolean not null default false,
  settings     jsonb not null default '{}'::jsonb,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create table if not exists public.app_config (
  id              smallint primary key default 1 check (id = 1),
  signups_enabled boolean not null default true,
  updated_at      timestamptz not null default now()
);
insert into public.app_config (id) values (1) on conflict do nothing;

create table if not exists public.day_settings_versions (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users (id) on delete cascade,
  effective_at timestamptz not null,
  time_zone    text not null check (time_zone <> '' and time_zone !~ '^(posix|right)/'),
  day_end      time not null check (day_end <= time '08:00'),
  floor_date   date,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (user_id, effective_at)
);

create table if not exists public.habits (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users (id) on delete cascade,
  title         text not null check (btrim(title) <> '' and char_length(title) <= 200),
  kind          text not null check (kind in ('yes_no', 'count')),
  target        integer not null default 1 check (target >= 1 and (kind = 'count' or target = 1)),
  reminder_time time,
  archived_on   date,
  sort_order    integer not null default 0,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (id, user_id)
);

create table if not exists public.habit_schedules (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  habit_id   uuid not null,
  valid_from date not null,
  recurrence text not null check (recurrence in ('daily', 'weekdays')),
  weekdays   smallint[],
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (habit_id, user_id) references public.habits (id, user_id) on delete cascade,
  unique (habit_id, valid_from),
  check (
    (recurrence = 'daily' and weekdays is null)
    or (recurrence = 'weekdays' and weekdays is not null
        and cardinality(weekdays) between 1 and 7
        and weekdays <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[])
  )
);

create table if not exists public.habit_logs (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users (id) on delete cascade,
  habit_id     uuid not null,
  loopdo_date  date not null,
  count        integer not null default 0 check (count >= 0),
  completed_at timestamptz,
  verified     boolean not null,
  evidence     jsonb not null,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  foreign key (habit_id, user_id) references public.habits (id, user_id) on delete cascade,
  unique (habit_id, loopdo_date)
);

create table if not exists public.lists (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  name       text not null check (btrim(name) <> '' and char_length(name) <= 80),
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (id, user_id)
);

create table if not exists public.tasks (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users (id) on delete cascade,
  list_id         uuid,
  title           text not null check (btrim(title) <> '' and char_length(title) <= 300),
  notes           text check (char_length(notes) <= 10000),
  due_date        date,
  due_time        time,
  repeat_rule     jsonb,
  template_id     uuid,
  occurrence_date date,
  completed_at    timestamptz,
  completed_on    date,
  verified        boolean,
  evidence        jsonb,
  sort_order      integer not null default 0,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (id, user_id),
  foreign key (list_id, user_id) references public.lists (id, user_id) on delete set null (list_id),
  foreign key (template_id, user_id) references public.tasks (id, user_id) on delete set null (template_id),
  check ((completed_at is null) = (completed_on is null)),
  check (due_time is null or due_date is not null),
  unique (template_id, occurrence_date)
);

create table if not exists public.subtasks (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  task_id    uuid not null,
  title      text not null check (btrim(title) <> '' and char_length(title) <= 300),
  done       boolean not null default false,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (task_id, user_id) references public.tasks (id, user_id) on delete cascade
);

create table if not exists public.journal_entries (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users (id) on delete cascade,
  loopdo_date date not null,
  text        text not null default '' check (char_length(text) <= 20000),
  mood        smallint check (mood between 1 and 5),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (id, user_id),
  unique (user_id, loopdo_date)
);

create table if not exists public.journal_photos (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  entry_id   uuid not null,
  r2_key     text not null unique,
  width      integer check (width > 0),
  height     integer check (height > 0),
  bytes      integer check (bytes > 0),
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (entry_id, user_id) references public.journal_entries (id, user_id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
do $$
declare tbl text;
begin
  foreach tbl in array array['profiles', 'app_config', 'day_settings_versions', 'habits', 'habit_schedules',
                             'habit_logs', 'lists', 'tasks', 'subtasks', 'journal_entries', 'journal_photos'] loop
    execute format('drop trigger if exists %I on public.%I', tbl || '_updated_at', tbl);
    execute format('create trigger %I before update on public.%I for each row execute function public.set_updated_at()',
                   tbl || '_updated_at', tbl);
  end loop;
end $$;

-- A habit log belongs to one habit and one LoopDo day forever.
create or replace function public.habit_logs_freeze_day() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.habit_id <> old.habit_id or new.loopdo_date <> old.loopdo_date then
    raise exception 'a habit log cannot move to another habit or day';
  end if;
  return new;
end $$;
drop trigger if exists habit_logs_freeze_day on public.habit_logs;
create trigger habit_logs_freeze_day before update on public.habit_logs
  for each row execute function public.habit_logs_freeze_day();

-- A task's completion day is frozen; it may only be cleared together with completed_at (un-tick).
create or replace function public.tasks_freeze_completed_on() returns trigger
language plpgsql set search_path = '' as $$
begin
  if old.completed_on is not null and new.completed_on is distinct from old.completed_on then
    if new.completed_on is not null or new.completed_at is not null then
      raise exception 'a task''s completion day cannot be changed';
    end if;
  end if;
  return new;
end $$;
drop trigger if exists tasks_freeze_completed_on on public.tasks;
create trigger tasks_freeze_completed_on before update on public.tasks
  for each row execute function public.tasks_freeze_completed_on();

-- At most 4 photos per journal entry (locks the entry so concurrent inserts can't race past it).
create or replace function public.journal_photos_limit() returns trigger
language plpgsql set search_path = '' as $$
begin
  perform 1 from public.journal_entries where id = new.entry_id for update;
  if (select count(*) from public.journal_photos where entry_id = new.entry_id) >= 4 then
    raise exception 'an entry can have at most 4 photos';
  end if;
  return new;
end $$;
drop trigger if exists journal_photos_limit on public.journal_photos;
create trigger journal_photos_limit before insert on public.journal_photos
  for each row execute function public.journal_photos_limit();

-- Every new auth user gets a profile.
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id) values (new.id) on conflict do nothing;
  return new;
end $$;
drop trigger if exists loopdo_on_auth_user_created on auth.users;
create trigger loopdo_on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- Row-level security (applies to loopdo_user; PowerSync bypasses it and filters by stream)
-- ---------------------------------------------------------------------------
do $$
declare tbl text;
begin
  foreach tbl in array array['day_settings_versions', 'habits', 'habit_schedules', 'habit_logs', 'lists',
                             'tasks', 'subtasks', 'journal_entries', 'journal_photos'] loop
    execute format('alter table public.%I enable row level security', tbl);
    execute format('drop policy if exists own_rows on public.%I', tbl);
    execute format('create policy own_rows on public.%I for all to loopdo_user
                      using (user_id = (select public.current_user_id()))
                      with check (user_id = (select public.current_user_id()))', tbl);
  end loop;
end $$;

alter table public.profiles enable row level security;
drop policy if exists own_profile on public.profiles;
create policy own_profile on public.profiles for all to loopdo_user
  using (id = (select public.current_user_id()))
  with check (id = (select public.current_user_id()));

alter table public.app_config enable row level security;
drop policy if exists read_config on public.app_config;
create policy read_config on public.app_config for select to loopdo_user using (true);
drop policy if exists admin_updates_config on public.app_config;
create policy admin_updates_config on public.app_config for update to loopdo_user
  using (exists (select 1 from public.profiles p where p.id = (select public.current_user_id()) and p.is_admin))
  with check (true);
