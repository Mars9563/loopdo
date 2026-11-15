begin;
set local role postgres;
-- At most 4 photos per journal entry.
insert into public.journal_photos (user_id, entry_id, r2_key, sort_order)
select '00000000-0000-0000-0000-00000000000a', '20000000-0000-0000-0000-00000000000a', 'k' || g, g from generate_series(1, 4) g;
select t.expect_error($q$insert into public.journal_photos (user_id, entry_id, r2_key, sort_order)
  values ('00000000-0000-0000-0000-00000000000a', '20000000-0000-0000-0000-00000000000a', 'k5', 5)$q$,
  'P0001', 'fifth photo');

-- A habit log's day can never be moved.
insert into public.habit_logs (id, user_id, habit_id, loopdo_date, count, verified, evidence)
values ('30000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a',
        '10000000-0000-0000-0000-00000000000a', '2026-11-14', 1, true, '{}');
select t.expect_error($q$update public.habit_logs set loopdo_date = '2026-11-13' where id = '30000000-0000-0000-0000-00000000000a'$q$,
  'P0001', 'move a habit log to another day');
update public.habit_logs set count = 2 where id = '30000000-0000-0000-0000-00000000000a'; -- count may change

-- A completed task's completion day is frozen; it can only be cleared together with completed_at (un-tick).
insert into public.tasks (id, user_id, title, due_date, completed_at, completed_on, verified, evidence)
values ('40000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', 'pay rent',
        '2026-11-13', now(), '2026-11-14', true, '{}');
select t.expect_error($q$update public.tasks set completed_on = '2026-11-13' where id = '40000000-0000-0000-0000-00000000000a'$q$,
  'P0001', 'backdate a task completion');
select t.expect_error($q$update public.tasks set completed_on = null where id = '40000000-0000-0000-0000-00000000000a'$q$,
  'P0001', 'clear completed_on but keep completed_at');
update public.tasks set completed_on = null, completed_at = null where id = '40000000-0000-0000-0000-00000000000a';

-- Shape checks.
select t.expect_error($q$insert into public.habits (user_id, title, kind, target)
  values ('00000000-0000-0000-0000-00000000000a', 'bad', 'yes_no', 3)$q$, '23514', 'yes/no habit with target 3');
select t.expect_error($q$insert into public.day_settings_versions (user_id, effective_at, time_zone, day_end)
  values ('00000000-0000-0000-0000-00000000000a', now(), 'Asia/Kolkata', '09:00')$q$, '23514', 'day end after 08:00');

-- updated_at moves on update.
do $$
declare before timestamptz;
begin
  select updated_at into before from public.habits where id = '10000000-0000-0000-0000-00000000000a';
  perform pg_sleep(0.01);
  update public.habits set title = 'meditate 10m' where id = '10000000-0000-0000-0000-00000000000a';
  if (select updated_at from public.habits where id = '10000000-0000-0000-0000-00000000000a') <= before then
    raise exception 'FAIL triggers: updated_at not bumped';
  end if;
end $$;
rollback;
