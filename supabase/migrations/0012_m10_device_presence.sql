-- LoopDo 0012 (M10 D7, the laptop takes over): one row per laptop saying until when it is "in use". The laptop
-- pushes active_until 5 min ahead while in use and back to now when it stops; the phone reads its synced copy at
-- ping time and rings silently while any is in the future. Only adds a table; nothing is removed.
-- The API checks the id (uuidV5(user, "presence:<device_id>"): a device writes only its own row) and clamps
-- active_until to at most 10 min after the server's now (Apply.kt presenceRules). Not in the export: a signal, not
-- the owner's data. Deleted with the account (cascade).
-- Design: docs/superpowers/specs/2026-11-18-m10-desktop-design.md (private).

create table if not exists public.device_presence (
  id           uuid primary key,
  user_id      uuid not null references auth.users (id) on delete cascade,
  device_id    uuid not null,
  active_until timestamptz not null,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (user_id, device_id),
  unique (id, user_id)
);

drop trigger if exists device_presence_updated_at on public.device_presence;
create trigger device_presence_updated_at before update on public.device_presence
  for each row execute function public.set_updated_at();

alter table public.device_presence enable row level security;
drop policy if exists own_rows on public.device_presence;
create policy own_rows on public.device_presence for all to loopdo_user
  using (user_id = (select public.current_user_id()))
  with check (user_id = (select public.current_user_id()));

grant select, insert, update on public.device_presence to loopdo_user;
grant select on public.device_presence to powersync_role, loopdo_backup;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables where pubname = 'powersync' and schemaname = 'public' and tablename = 'device_presence'
  ) then
    alter publication powersync add table public.device_presence;
  end if;
end $$;
