-- LoopDo 0009 (M6.5, two devices): each journal line is its own row, so lines written on two devices
-- never overwrite each other ("latest change wins" only between edits of the same line).
-- Only adds a table; nothing is removed. journal_entries keeps the day (mood, photos) and its old text
-- until the phone moves that text into lines.
-- Design: docs/superpowers/specs/2026-11-18-m6-5-two-devices-design.md (private).

create table if not exists public.journal_lines (
  id         uuid primary key,
  user_id    uuid not null references auth.users (id) on delete cascade,
  entry_id   uuid not null,
  stamped_at timestamptz,                         -- the line's [HH:mm]; null = no time (old entries)
  text       text not null default '' check (char_length(text) <= 20000),
  edited_at  timestamptz not null default now(),  -- the device's clock at the last text change: the newest edit wins
  deleted_at timestamptz,                         -- deleted (kept, so an edit made elsewhere can still win)
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (id, user_id),
  foreign key (entry_id, user_id) references public.journal_entries (id, user_id) on delete cascade
);
create index if not exists journal_lines_entry on public.journal_lines (entry_id);

drop trigger if exists journal_lines_updated_at on public.journal_lines;
create trigger journal_lines_updated_at before update on public.journal_lines
  for each row execute function public.set_updated_at();

-- A line belongs to one day forever.
create or replace function public.journal_lines_freeze_entry() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.entry_id <> old.entry_id then
    raise exception 'a journal line cannot move to another day';
  end if;
  return new;
end $$;
drop trigger if exists journal_lines_freeze_entry on public.journal_lines;
create trigger journal_lines_freeze_entry before update on public.journal_lines
  for each row execute function public.journal_lines_freeze_entry();

alter table public.journal_lines enable row level security;
drop policy if exists own_rows on public.journal_lines;
create policy own_rows on public.journal_lines for all to loopdo_user
  using (user_id = (select public.current_user_id()))
  with check (user_id = (select public.current_user_id()));

grant select, insert, update, delete on public.journal_lines to loopdo_user;
grant select on public.journal_lines to powersync_role, loopdo_backup;

-- Count changes are applied as differences (two devices' taps add up). A retried upload must not add them
-- twice: the key of every applied count change is kept here for a while (never synced to devices).
create table if not exists public.applied_ops (
  user_id    uuid not null references auth.users (id) on delete cascade,
  op_key     text not null check (char_length(op_key) <= 100),
  applied_at timestamptz not null default now(),
  primary key (user_id, op_key)
);
alter table public.applied_ops enable row level security;
drop policy if exists own_rows on public.applied_ops;
create policy own_rows on public.applied_ops for all to loopdo_user
  using (user_id = (select public.current_user_id()))
  with check (user_id = (select public.current_user_id()));
grant select, insert, delete on public.applied_ops to loopdo_user;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables where pubname = 'powersync' and schemaname = 'public' and tablename = 'journal_lines'
  ) then
    alter publication powersync add table public.journal_lines;
  end if;
end $$;
