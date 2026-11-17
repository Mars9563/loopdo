package app.loopdo.android.habits

import app.loopdo.android.sync.oneTransaction
import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.TrustedClock
import app.loopdo.android.today.parseRecurrence
import app.loopdo.android.today.parseWeekdays
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.nextVersionValidFrom
import app.loopdo.shared.habit.pauseVersions
import app.loopdo.shared.habit.scheduleOn
import com.powersync.PowerSyncDatabase
import com.powersync.db.internal.PowerSyncTransaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Everything the habits screens can change (M5). Each action is ONE local transaction, so it
 * uploads as one batch and a 422 undoes exactly it (API contract).
 *
 * The past is never re-judged: a change to days or target is a new schedule version from
 * tomorrow, a pause starts tomorrow at the earliest, versions already in force are never
 * touched (the server refuses that), and only pending versions (from tomorrow on) are
 * replaced or deleted. Every schedule row carries time evidence, so a change made offline
 * keeps its dates when it syncs later.
 */
/** What the habits screens ask of the database (a fake in JVM tests). */
interface HabitCommands {
    suspend fun createHabit(draft: HabitDraft): UUID
    suspend fun editHabit(id: UUID, draft: HabitDraft)
    suspend fun pauseHabit(id: UUID, from: LocalDate, to: LocalDate)
    suspend fun endPause(id: UUID)
    suspend fun cancelPause(id: UUID)
    suspend fun archiveHabit(id: UUID)
    suspend fun restoreHabit(id: UUID)
    suspend fun hideHabit(id: UUID)
    suspend fun reorderHabits(ids: List<UUID>)
}

class HabitActions(
    private val db: PowerSyncDatabase,
    private val clock: TrustedClock,
    private val evidence: EvidenceFactory,
    private val dateAt: suspend (Instant) -> LocalDate,
    private val userId: () -> UUID?,
) : HabitCommands {
    private class Moment(val now: Instant, val today: LocalDate, val evidence: String) {
        val tomorrow: LocalDate get() = nextVersionValidFrom(today)
    }

    private suspend fun moment(): Moment {
        val now = clock.now()
        val today = dateAt(now.instant)
        return Moment(now.instant, today, evidence.forTick(today).toString())
    }

    private class HabitRow(val kind: HabitKind, val target: Int, val archivedOn: LocalDate?)

    /** A stored schedule version: its row id (to delete it) and its content. */
    private class Version(val id: String, val s: HabitSchedule) {
        val from: LocalDate get() = s.validFrom
    }

    /** A new habit, due from today (its first version), at the end of the order. */
    override suspend fun createHabit(draft: HabitDraft): UUID {
        val d = checkDraft(draft)
        val m = moment()
        val id = UUID.randomUUID()
        write { tx ->
            val order = tx.get("SELECT coalesce(max(sort_order), -1) + 1 FROM habits") { it.getLong(0)!! }
            tx.execute(
                "INSERT INTO habits (id, user_id, title, kind, target, step, unit, reminder_time, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(id.toString(), userId()?.toString(), d.title, d.kind.db(), d.target, d.step, d.unit, d.reminderTime?.toString(), order),
            )
            // No own target on the first version: it follows habits.target, which may still change while there are no logs.
            tx.insertVersion(id, HabitSchedule(m.today, d.recurrence, d.weekdays), m)
        }
        return id
    }

    /**
     * Name, step, unit and reminder change in place. Days or target: a version from tomorrow
     * that replaces the pending ones; a pause stays, and its resume version takes the edit
     * (an edit during a running pause applies when it ends). The type changes only while the
     * habit has no history.
     */
    override suspend fun editHabit(id: UUID, draft: HabitDraft) {
        val d = checkDraft(draft)
        val m = moment()
        write { tx ->
            val h = tx.habit(id)
            val vs = tx.versions(id)
            val kindChanges = d.kind != h.kind
            if (kindChanges) {
                // An own target already in force would judge a yes/no day by a count target.
                check(!tx.hasLogs(id) && vs.none { it.from <= m.today && it.s.target != null }) {
                    "the type can't change once a habit has history. make a new habit instead."
                }
                tx.execute(
                    "UPDATE habits SET title = ?, step = ?, unit = ?, reminder_time = ?, kind = ?, target = ? WHERE id = ?",
                    listOf(d.title, d.step, d.unit, d.reminderTime?.toString(), d.kind.db(), d.target, id.toString()),
                )
            } else {
                tx.execute(
                    "UPDATE habits SET title = ?, step = ?, unit = ?, reminder_time = ? WHERE id = ?",
                    listOf(d.title, d.step, d.unit, d.reminderTime?.toString(), id.toString()),
                )
            }
            val habitTarget = if (kindChanges) d.target else h.target
            val wanted = HabitSchedule(
                m.tomorrow, d.recurrence, d.weekdays,
                target = d.target.takeIf { d.kind == HabitKind.COUNT && it != habitTarget },
            )
            val pause = pauseIn(vs, m.today)
            val todays = scheduleOn(vs.filter { it.from <= m.today }.map { it.s }, m.today)
            val desired = buildList {
                val pausedFrom = pause?.first?.from
                if ((pausedFrom == null || pausedFrom > m.tomorrow) && (todays == null || !wanted.sameAs(todays, habitTarget))) add(wanted)
                if (pause != null && pause.first.from > m.today) add(pause.first.s)
                pause?.second?.let { add(wanted.copy(validFrom = it.from)) }
            }
            tx.replacePending(id, vs, desired, m)
        }
    }

    /** Pauses from [from] (tomorrow at the earliest) through [to]; it is due again the day after. One pause at a time. */
    override suspend fun pauseHabit(id: UUID, from: LocalDate, to: LocalDate) {
        val m = moment()
        require(from >= m.tomorrow) { "a pause starts tomorrow at the earliest." }
        require(to >= from) { "a pause ends on or after the day it starts." }
        write { tx ->
            check(tx.habit(id).archivedOn == null) { "restore the habit before pausing it." }
            val vs = tx.versions(id)
            check(pauseIn(vs, m.today) == null) { "this habit already has a pause. end or cancel it first." }
            val resume = to.plusDays(1)
            // The pause absorbs pending versions inside it; the one in force on the resume day comes back after it.
            val base = scheduleOn(vs.map { it.s }, resume) ?: error("the habit has no schedule.")
            vs.filter { it.from > m.today && it.from in from..resume }.forEach { tx.deleteVersion(it) }
            pauseVersions(base, from, to).forEach { tx.insertVersion(id, it, m) }
        }
    }

    /** A running pause ends: due again from tomorrow. A pause that hasn't started is cancelled. */
    override suspend fun endPause(id: UUID) {
        val m = moment()
        write { tx ->
            val vs = tx.versions(id)
            val (paused, resume) = pauseIn(vs, m.today) ?: return@write
            if (paused.from > m.today) return@write tx.cancel(id, vs, paused, resume, m)
            if (resume != null && resume.from <= m.tomorrow) return@write
            val back = resume?.s ?: vs.lastOrNull { it.s.recurrence != Recurrence.PAUSED }?.s ?: return@write
            resume?.let { tx.deleteVersion(it) }
            tx.insertVersion(id, back.copy(validFrom = m.tomorrow), m)
        }
    }

    /** Removes a pause that hasn't started. A running one is history up to today: end it instead. */
    override suspend fun cancelPause(id: UUID) {
        val m = moment()
        write { tx ->
            val vs = tx.versions(id)
            val (paused, resume) = pauseIn(vs, m.today) ?: return@write
            check(paused.from > m.today) { "the pause has started. end it instead." }
            tx.cancel(id, vs, paused, resume, m)
        }
    }

    /** Archived from tomorrow (today still counts). Planned changes (pending versions, a pause) are dropped. */
    override suspend fun archiveHabit(id: UUID) {
        val m = moment()
        write { tx ->
            if (tx.habit(id).archivedOn != null) return@write
            tx.versions(id).filter { it.from > m.today }.forEach { tx.deleteVersion(it) }
            tx.execute("UPDATE habits SET archived_on = ? WHERE id = ?", listOf(m.tomorrow.toString(), id.toString()))
        }
    }

    /**
     * Due again from tomorrow. The archived days become a pause from the archive day, so they stay
     * neutral (the server keeps that one past date). The versions go first: the server checks them
     * against archived_on before it is cleared.
     */
    override suspend fun restoreHabit(id: UUID) {
        val m = moment()
        write { tx ->
            val archivedOn = tx.habit(id).archivedOn ?: return@write
            if (archivedOn > m.today) { // not in force yet: simply undone
                tx.execute("UPDATE habits SET archived_on = NULL WHERE id = ?", listOf(id.toString()))
                return@write
            }
            val vs = tx.versions(id)
            vs.filter { it.from > m.today }.forEach { tx.deleteVersion(it) }
            check(vs.none { it.from in archivedOn..m.today }) { "this habit changed while it was archived, so it can't be restored. make a new one instead." }
            val base = vs.lastOrNull { it.s.recurrence != Recurrence.PAUSED }?.s ?: error("the habit has no schedule.")
            tx.insertVersion(id, HabitSchedule(archivedOn, Recurrence.PAUSED), m)
            tx.insertVersion(id, base.copy(validFrom = m.tomorrow), m)
            tx.execute("UPDATE habits SET archived_on = NULL WHERE id = ?", listOf(id.toString()))
        }
    }

    /** "Delete forever": hidden everywhere; its schedules and logs stay and keep counting in perfect and active days. */
    override suspend fun hideHabit(id: UUID) {
        val m = moment()
        write { tx ->
            tx.execute("UPDATE habits SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", listOf(m.now.toString(), id.toString()))
        }
    }

    /** [ids] top to bottom. Only rows whose place changes are written. */
    override suspend fun reorderHabits(ids: List<UUID>) {
        write { tx ->
            ids.forEachIndexed { i, id ->
                tx.execute("UPDATE habits SET sort_order = ? WHERE id = ? AND sort_order IS NOT ?", listOf(i, id.toString(), i))
            }
        }
    }

    // --- inside a transaction ------------------------------------------------

    private suspend fun <R> write(block: (PowerSyncTransaction) -> R): R = db.oneTransaction(block)

    private fun PowerSyncTransaction.habit(id: UUID): HabitRow =
        getOptional("SELECT kind, target, archived_on FROM habits WHERE id = ?", listOf(id.toString())) {
            HabitRow(kindOf(it.getString(0)), (it.getLong(1) ?: 1L).toInt(), it.getString(2)?.let { d -> LocalDate.parse(d.take(10)) })
        } ?: throw IllegalStateException("that habit is gone.")

    private fun PowerSyncTransaction.versions(id: UUID): List<Version> =
        getAll("SELECT id, valid_from, recurrence, weekdays, target FROM habit_schedules WHERE habit_id = ?", listOf(id.toString())) {
            Version(
                it.getString(0)!!,
                HabitSchedule(LocalDate.parse(it.getString(1)!!.take(10)), parseRecurrence(it.getString(2)), parseWeekdays(it.getString(3)), it.getLong(4)?.toInt()),
            )
        }.sortedBy { it.from }

    private fun PowerSyncTransaction.hasLogs(id: UUID): Boolean =
        getOptional("SELECT 1 FROM habit_logs WHERE habit_id = ? LIMIT 1", listOf(id.toString())) { true } ?: false

    private fun PowerSyncTransaction.insertVersion(habitId: UUID, s: HabitSchedule, m: Moment) = execute(
        "INSERT INTO habit_schedules (id, user_id, habit_id, valid_from, recurrence, weekdays, target, evidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            UUID.randomUUID().toString(), userId()?.toString(), habitId.toString(), s.validFrom.toString(), s.recurrence.db(),
            s.weekdays.takeIf { it.isNotEmpty() }?.map { it.value }?.sorted()?.joinToString(",", "[", "]"), s.target, m.evidence,
        ),
    )

    private fun PowerSyncTransaction.deleteVersion(v: Version) = execute("DELETE FROM habit_schedules WHERE id = ?", listOf(v.id))

    /**
     * Makes the pending versions (after today) exactly [desired]: identical ones are kept, the rest
     * deleted, then the missing ones inserted. Deletes go first, so the server never sees two
     * versions on one day (unique habit_id + valid_from).
     */
    private fun PowerSyncTransaction.replacePending(habitId: UUID, vs: List<Version>, desired: List<HabitSchedule>, m: Moment) {
        val pending = vs.filter { it.from > m.today }
        val kept = pending.filter { it.s in desired }
        (pending - kept.toSet()).forEach { deleteVersion(it) }
        (desired - kept.map { it.s }.toSet()).forEach { insertVersion(habitId, it, m) }
    }

    /** Drops a pause that hasn't started. An edit made since lives only in its resume version: it moves to the pause's first day. */
    private fun PowerSyncTransaction.cancel(habitId: UUID, vs: List<Version>, paused: Version, resume: Version?, m: Moment) {
        deleteVersion(paused)
        resume?.let { deleteVersion(it) }
        if (resume == null) return
        val before = scheduleOn(vs.filter { it !== paused && it !== resume }.map { it.s }, paused.from)
        if (before == null || before != resume.s.copy(validFrom = before.validFrom)) insertVersion(habitId, resume.s.copy(validFrom = paused.from), m)
    }

    private companion object {
        /** The pause still running or ahead: (paused version, resume version). An ended pause is history. */
        fun pauseIn(vs: List<Version>, today: LocalDate) = currentPause(vs, today) { it.s }

        /** Same days and same effective target (null = the habit's own). */
        fun HabitSchedule.sameAs(o: HabitSchedule, habitTarget: Int) =
            recurrence == o.recurrence && weekdays == o.weekdays && (target ?: habitTarget) == (o.target ?: habitTarget)

        fun kindOf(s: String?) = if (s == "count") HabitKind.COUNT else HabitKind.YES_NO
        fun HabitKind.db() = if (this == HabitKind.COUNT) "count" else "yes_no"
        fun Recurrence.db() = name.lowercase()
    }
}
