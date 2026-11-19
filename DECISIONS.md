# loopdo_ — decisions on record

A record of the deliberate decisions behind loopdo_, so a reviewer can tell **intended behaviour** from bugs.
It is not user documentation (that comes later). Tags:

- **[owner]** — the owner's decision.
- **[Claude → owner OK]** — proposed by Claude (the builder), approved by the owner.
- **[Claude]** — Claude's call while building, recorded for review; open to correction.
- **[accepted limit]** — a known gap the owner chose to live with, or a platform limit; please don't report it as new
  unless the code does worse than described here.

The top rule outranks everything below: **never lose the owner's data** [owner] (the app exists because a phone
reset once wiped a 219-day journal streak).

---

## 1. What it is

- A personal **Android** app (Galaxy A55, Android 17) plus a **Linux laptop** app (KDE Plasma 6, Wayland): to-dos,
  habits, a daily journal, streaks and stats, with a home-screen widget as the phone's main surface. No iOS [owner].
- **Local-first** [owner]: every device works offline on its own database and syncs; the server is the sync target
  and the restore source.
- **Multi-user, with an admin sign-up switch**; email + Google sign-in [owner].
- **Public repo** [owner]: no secrets, tokens, keystores or personal data are committed. `docs/` (specs, plans,
  ledgers) is private and git-ignored on purpose [owner]; this file is the reviewer-facing summary of it.

## 2. Day, accountability, time [owner unless tagged]

- **The LoopDo date**: a day ends at the user's *day end* (00:00–08:00, default 04:00), in their time zone. At 00:42
  with day end 04:00 it is still yesterday.
- **Day-end and zone changes are pending**: they take effect when today ends (under old and new settings, whichever
  is later), so a closed day never reopens.
- The date is defined through the day's end *instant* (monotonic across DST) [Claude → owner OK]. The same Kotlin
  function runs on phone, laptop and server; Postgres has an equivalent SQL function pinned to it by tests.
- **Ticks are frozen once their day ends; the journal is editable forever** (text, mood, photos).
- **Offline ticks are accepted**, judged by evidence: a signed server-time anchor + the device's monotonic clock
  (Android `elapsedRealtime` + boot count; Linux `/proc/uptime` + boot_id), or network time. A changed device clock
  can't tick a past day. **No trustworthy evidence** (rebooted while offline) → **accepted but marked unverified**
  (`?`). A proven-backdated tick is rejected.
- The app's own "now" is the trusted clock (anchor + elapsed), not the device wall clock.
- **Dated tasks roll over** when not done by the day end, marked `late since <date>`; the miss stays on its day.
  **Inbox (undated) tasks** can be completed and reopened any time; dated tasks only un-complete on their completion day.
- **Habit edits never re-judge the past**: schedule/target changes start tomorrow (stored as a new schedule version);
  a habit's kind freezes once it has logs; a pause starts tomorrow at the earliest [Claude → owner OK, M5].

## 3. Habits, tasks, journal, streaks [owner unless tagged]

- Habit kinds: yes/no; count with a target (partial = miss; `[+step]`, a same-day −1 in the app); daily or chosen
  weekdays; "n× a week". Archive keeps history; delete-forever = hidden (history kept) after confirmation.
- Four streaks: each habit, journal (a day with a live line or a photo), perfect days, active days. Each has its own
  **freeze bank**: +1 per 7 successful days, max 2, spent automatically on a miss. **Streaks are derived, never
  stored** (a pure function over the history) [Claude → owner OK].
- Tasks: lists (deleting a list moves its tasks to the inbox, never deletes them), subtasks, repeating tasks (one
  instance per occurrence; a missed daily series leaves **one late copy + "missed N"**; stopping a series keeps
  today's open copy) [owner, M6.5].
- Journal = a **time log** [owner, M6]: each saved line stamped `[HH:mm]`, a gap separator at ≥ 30 min, tap a line
  to fix it (its time stays), long-press deletes (asks); up to **4 photos/day**, compressed on the device (~2048 px,
  ~85 %), uploaded to R2 when online. One mood per day.
- **Each journal line is its own row** (two devices never overwrite each other) [Claude → owner OK, M6.5]: the
  newer edit wins; **edit vs delete of the same line keeps the edit** [owner].
- **Counts from two devices add up** (+1 and +1 = +2), with idempotent retry keys [Claude → owner OK, M6.5].
- A line written in a pop-up opened before the day end and saved after goes to **the day it was opened for**,
  stamped just before that day's end [owner, M6.5]. An earlier day's unsaved draft keeps its own day and last-edit
  time (laptop writer included) [Claude, D8 review].

## 4. Sync, accounts, data safety

- Supabase (Postgres + auth) and PowerSync Cloud (sync) on free tiers; only the Ktor API runs on the owner's server
  [owner]. Uploads go through the API, which applies the shared rules and decides `verified` / `completed_on` /
  `late_since`; clients can't send those columns.
- **Account switch rule** (one shared `SessionSync` for phone and laptop) [Claude → owner OK]: a different account
  signing in **clears the previous account's local data first, and is refused while any of it hasn't reached the
  server** (queued uploads, drafts, photos). Signing out only disconnects (nothing waiting is lost). The previous
  account's **unsaved journal text is set aside per account**, never shown to the next one, and comes back when that
  account returns [Claude, ultrareview fix].
- **Data line** [owner, M9]: settings shows `all saved · 2 min ago` or what is waiting; Today shows `↑n` when
  something waited > 10 min.
- **Export** is manual only, made on the phone from local data (ZIP: Markdown + photos + JSON) [owner].
  `device_presence` is **not** exported (a signal, not the owner's data) [Claude → owner OK].
- **Delete account** has a **7-day undo window**, export offered first, typed confirmation; the server's daily wipe
  job deletes the sign-in (cascading to every table) after the window [owner, M9]. Backups keep data up to 13 months
  and the delete screen says so [owner].
- Server errors that refuse an upload undo the local change and tell the owner ("set aside") rather than retrying
  forever; network failures retry.

## 5. The phone

- **Locked designs are law** [owner]: the widget, pop-ups and the four app screens (look, layout, wording style,
  behaviour) don't change without the owner. The visual world is a "terminal glass" theme; no Material You colours.
- **App lock** [owner, M6]: off by default; a switch in settings; asks after ≥ 1 min in the background; the widget is
  never locked (it shows no journal text).
- **Reminders** [owner, M7]: habit/task **10 min before + at**; untimed tasks never ping; journal nudge (default
  21:00) only if today has no line; **day-end warning** lists habits + journal only and is **silent 23:00–07:00**;
  Sunday review ping (20:00) [owner, M8]. Count habits update the same notification in place. Snooze 30 m re-pings
  once. Missed reminders show late if still relevant (latest ping of an item only). Times and on/off per kind are in
  settings and **synced between devices** [owner, M9].
- **"Unrestricted" battery** is asked for once (Android's own dialog), with a settings row showing its state
  [owner, 2026-11-19]: without it Android cut background sync with the screen off.
- The phone currently runs **debug builds** (a release build is offered, owner said "not now"). `debugForgedTick`
  exists only in debug builds, for device tests.

## 6. The laptop (M10)

- The laptop is the **phone's counterpart**, not a separate product: same account, same rules (`shared/`), same
  logic (`client/`), its own local database in `~/.local/share/loopdo/` [owner].
- **Locked laptop designs** [owner]: `desktop-v1` (today / journal / stats) and `desktop-screens-v1` (sign-in,
  quick add + tray, habits, editors, settings). New screens are mocked first.
- **Zoom**: 140 % by default on a 1440p panel (the owner's sweet spot), Ctrl +/−/0 [owner].
- **Tray**: KDE's native StatusNotifierItem + dbusmenu over D-Bus (Java's own tray gets no clicks on Plasma 6 +
  Wayland — tested) [owner OK'd dbus-java].
- **Pings on the laptop** [owner]: the phone's reminder set, as KDE notifications with the same buttons; loopdo_
  plays its own sound (Kenney "question_001", CC0) because Plasma plays none for a plain notification [owner picked
  the sound]; popups use **KDE's own popup time** [owner]. **The laptop always shows its own pings**, in use or not,
  locked or not (a duplicate with the phone is harmless) [Claude → owner OK].
- **The laptop takes over while in use** [owner picked "C", D7]: one `device_presence` row per laptop; while in use
  the laptop pushes `active_until` ahead and the phone posts its ping to a **quiet channel** (in the shade, buttons
  kept, no sound/vibration/pop-up). The phone reads its local copy and **never waits for sync**; when in doubt it rings
  ("one ping too many, never one too few").
  - **"In use" = screen unlocked + awake + pings on + signed in** [owner OK'd]: Plasma on Wayland refuses
    `GetSessionIdleTime`, and KDE's auto-lock after 5 min stands in for idle.
  - **Laptop Do Not Disturb counts as not in use** (the phone rings) [Claude, D7 review; owner told].
  - The server clamps `active_until` to ≤ now + 10 min (a fast laptop clock can't silence the phone for long).
  - **`active_until` is 2 min ahead, refreshed about every minute** [owner, D8 manual test].
  - Presence is written only while connected (it can't reach the phone otherwise and would only pile up).
- **PIN lock** [owner, D8, mock `desktop-pin-v1`]: off by default; 4 digits; asked when the window is **opened** more
  than 1 h after the last unlock (an open window doesn't re-lock); opens on the 4th digit; 5 wrong → 30 s wait
  (stored, survives restarts); salted PBKDF2. **Tray, pings and quick add are never locked.** The app stays alive
  under the lock (hidden, no input). "forgot? sign in again" signs out and removes the pin only after a real
  sign-out.
- **Google sign-in** opens the system browser (PKCE, local callback on fixed port 36584 — Supabase must allow
  `http://localhost:36584` and `http://localhost:36584/**`) [owner: build it]. Email + password also works.
- **Quick add** (Meta+J, bound by the owner in KDE): a line → today's journal; `t …` → an inbox task; drafts kept.
- **Single instance** per data folder (a lock file decides; a Unix socket carries commands). If the socket can't be
  made, the app still starts [Claude, ultrareview fix].
- **Start at login** is on for a first install, then the owner's own setting [Claude → owner OK].

## 7. Server

- The Ktor API runs on the owner's server (k3s + Argo CD; images from CI). Every server change is approved by the
  owner first and recorded in the owner's private server log; schema changes are **additive migrations** only.
- The admin UIs are VPN-only; auth, sync and the API are public.
- The daily wipe job is live (deletes accounts past their 7-day window).

## 8. Accepted limits [accepted limit]

- **PIN lock + Google**: after "forgot?", Google sign-in from the laptop's already-signed-in browser opens the app
  without a password. **Owner: "fine as is"** (a light lock; nobody else uses the laptop). The PIN also doesn't
  protect the files on disk.
- **Lid close**: NetworkManager drops Wi-Fi before the laptop can send "not in use", so after closing the lid the
  phone may stay quiet until `active_until` runs out (≤ 2 min). Owner accepted after shrinking it from 5 min.
- **Laptop pings arrive a few seconds late** (15 s timer sweep) — owner: not an issue.
- **The OAuth callback server binds all interfaces** for its 3-minute window (supabase-kt has no host option).
- **"habits + tasks" is one row** in the laptop's settings (the locked design): it switches both together.
- **Free tiers** (Supabase, PowerSync) are the chosen hosting; their quotas apply.
- The ledgers in the private docs also list deferred *minor* review findings per milestone; they are known and
  queued, not forgotten.
