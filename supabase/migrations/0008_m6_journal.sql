-- LoopDo 0008 (M6): the journal as a time log. Only adds a column; nothing is removed.
-- Design: docs/superpowers/specs/2026-11-18-m6-journal-design.md (private).

-- When a photo was added to its day (client-set), so it shows inline at its time; null = no time (old or past-day photos).
alter table public.journal_photos
  add column if not exists added_at timestamptz;
