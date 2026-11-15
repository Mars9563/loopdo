-- LoopDo 0002: grants and the PowerSync publication.
-- Supabase gives anon/authenticated full rights on new public tables by default.
-- LoopDo takes them all away: only the LoopDo API (as loopdo_user) writes data.

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke all on all functions in schema public from public, anon, authenticated;
alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
alter default privileges in schema public revoke all on functions from public, anon, authenticated;

grant usage on schema public to loopdo_user, powersync_role;
grant execute on function public.current_user_id() to loopdo_user;

grant select, insert, update, delete on
  public.day_settings_versions, public.habits, public.habit_schedules, public.habit_logs,
  public.lists, public.tasks, public.subtasks, public.journal_entries, public.journal_photos
to loopdo_user;

grant select on public.profiles to loopdo_user;
grant update (display_name, onboarded_at, settings) on public.profiles to loopdo_user;

grant select on public.app_config to loopdo_user;
grant update (signups_enabled) on public.app_config to loopdo_user;

grant select on
  public.profiles, public.day_settings_versions, public.habits, public.habit_schedules, public.habit_logs,
  public.lists, public.tasks, public.subtasks, public.journal_entries, public.journal_photos
to powersync_role;

-- PowerSync replicates exactly these tables (named, not FOR ALL TABLES).
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'powersync') then
    create publication powersync for table
      public.profiles, public.day_settings_versions, public.habits, public.habit_schedules, public.habit_logs,
      public.lists, public.tasks, public.subtasks, public.journal_entries, public.journal_photos;
  end if;
end $$;
