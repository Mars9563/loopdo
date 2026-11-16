-- LoopDo 0007 (M5): habits and tasks. Only adds columns and widens one check; nothing is removed.
-- Design: docs/superpowers/specs/2026-11-16-m5-habits-tasks-design.md (private).

-- Count habits: the [+step] button and a unit ("20/35 words"); "delete forever" hides (history keeps counting).
alter table public.habits
  add column if not exists step integer not null default 1 check (step >= 1),
  add column if not exists unit text check (char_length(unit) <= 16),
  add column if not exists deleted_at timestamptz;

-- A target per schedule version (target changes apply from tomorrow), time evidence (a pause set
-- offline keeps its dates), and a 'paused' recurrence (nothing due).
alter table public.habit_schedules
  add column if not exists target integer check (target >= 1),
  add column if not exists evidence jsonb;
alter table public.habit_schedules drop constraint if exists habit_schedules_recurrence_check;
alter table public.habit_schedules drop constraint if exists habit_schedules_check;
alter table public.habit_schedules add constraint habit_schedules_recurrence_m5 check (
  (recurrence in ('daily', 'paused') and weekdays is null)
  or (recurrence = 'weekdays' and weekdays is not null
      and cardinality(weekdays) between 1 and 7
      and weekdays <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[])
);

-- Tasks: the first date a moved task was late (server-owned), and how far a repeating task's
-- occurrences have been created (a deleted occurrence is never created again).
alter table public.tasks
  add column if not exists late_since date,
  add column if not exists generated_through date;
