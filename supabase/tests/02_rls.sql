-- As loopdo_user (the role the API switches into), each user sees and writes only their own rows.
begin;
select t.as_user('00000000-0000-0000-0000-00000000000a');
set local role loopdo_user;
do $$ begin
  if (select count(*) from public.habits) <> 1 then raise exception 'FAIL rls: A should see exactly 1 habit'; end if;
  if (select title from public.habits) <> 'meditate' then raise exception 'FAIL rls: A sees the wrong habit'; end if;
  if (select count(*) from public.profiles) <> 1 then raise exception 'FAIL rls: A should see only own profile'; end if;
end $$;
select t.expect_error($q$insert into public.habits (user_id, title, kind, target)
  values ('00000000-0000-0000-0000-00000000000b', 'sneaky', 'yes_no', 1)$q$, '42501', 'A inserts a habit for B');
-- A's own user_id but B's habit id: composite FK must refuse.
select t.expect_error($q$insert into public.habit_logs (user_id, habit_id, loopdo_date, count, verified, evidence)
  values ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-00000000000b', '2026-11-14', 1, true, '{}')$q$,
  '23503', 'A logs B''s habit');
do $$ begin
  update public.habits set title = 'hacked' where id = '10000000-0000-0000-0000-00000000000b';
  if found then raise exception 'FAIL rls: A updated B''s habit'; end if;
end $$;
select t.expect_error($q$update public.profiles set is_admin = true$q$, '42501', 'A makes self admin');
-- RLS hides the row from a non-admin: the update matches nothing (no error) and changes nothing.
do $$ begin
  update public.app_config set signups_enabled = false;
  if found then raise exception 'FAIL rls: non-admin closed sign-ups'; end if;
end $$;
-- A can write own rows.
insert into public.habits (user_id, title, kind, target) values ('00000000-0000-0000-0000-00000000000a', 'water', 'count', 8);
update public.profiles set display_name = 'Kaze';
rollback;

-- An admin may switch sign-ups off.
update public.profiles set is_admin = true where id = '00000000-0000-0000-0000-00000000000a';
begin;
select t.as_user('00000000-0000-0000-0000-00000000000a');
set local role loopdo_user;
do $$ begin
  update public.app_config set signups_enabled = false;
  if not found then raise exception 'FAIL rls: admin could not change app_config'; end if;
end $$;
rollback;
update public.profiles set is_admin = false where id = '00000000-0000-0000-0000-00000000000a';
