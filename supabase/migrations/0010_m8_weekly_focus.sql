-- LoopDo 0010 (M8, weekly review): the weekly focus — 1–2 promises for a week, set on its Sunday-before or
-- Monday and checked by the next review. Only adds a table; nothing is removed.
-- The window and the 2-item limit are enforced by the API (Apply.kt focusRules); the database checks shapes.
-- Design: docs/superpowers/specs/2026-11-18-m8-stats-design.md (private).

create table if not exists public.weekly_focus (
  id          uuid primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  week_start  date not null check (extract(isodow from week_start) = 1),   -- a Monday
  kind        text not null check (kind in ('habit', 'task')),
  item_id     uuid not null,                                            -- the habit or task (a promise outlives it)
  title       text not null check (char_length(title) <= 200),          -- copy, shown if the item is gone
  goal        integer check (goal between 1 and 7),                     -- habit: N times that week
  deadline    date check (deadline is null or deadline between week_start and week_start + 6), -- task: saved when picked
  sort_order  integer not null default 0,
  deleted_at  timestamptz,                                               -- removed inside the window
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (id, user_id),
  check ((kind = 'habit' and goal is not null and deadline is null) or (kind = 'task' and goal is null and deadline is not null))
);
create index if not exists weekly_focus_week on public.weekly_focus (user_id, week_start);

drop trigger if exists weekly_focus_updated_at on public.weekly_focus;
create trigger weekly_focus_updated_at before update on public.weekly_focus
  for each row execute function public.set_updated_at();

alter table public.weekly_focus enable row level security;
drop policy if exists own_rows on public.weekly_focus;
create policy own_rows on public.weekly_focus for all to loopdo_user
  using (user_id = (select public.current_user_id()))
  with check (user_id = (select public.current_user_id()));

grant select, insert, update on public.weekly_focus to loopdo_user;
grant select on public.weekly_focus to powersync_role, loopdo_backup;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables where pubname = 'powersync' and schemaname = 'public' and tablename = 'weekly_focus'
  ) then
    alter publication powersync add table public.weekly_focus;
  end if;
end $$;
