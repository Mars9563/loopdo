package app.loopdo.shared.task

import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TasksTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private val template = UUID.fromString("0f8e1d2c-3b4a-4596-8877-665544332211")

    @Test
    fun uuidV5MatchesRfcVector() = assertEquals(
        UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"),
        uuidV5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com"),
    )

    @Test
    fun instanceIdIsStable() {
        assertEquals(instanceId(template, d(11, 16)), instanceId(template, d(11, 16)))
        assertNotEquals(instanceId(template, d(11, 16)), instanceId(template, d(11, 17)))
        assertEquals(5, instanceId(template, d(11, 16)).version())
    }

    @Test
    fun monthlyDay31ClampsToMonthEnd() = assertEquals(
        listOf(d(1, 31, 2028), d(2, 29, 2028), d(3, 31, 2028)),
        occurrences(RepeatRule.MonthlyOnDay(31), d(1, 1, 2028), d(3, 31, 2028)),
    )

    @Test
    fun monthlyInNonLeapFebruary() = assertEquals(
        listOf(d(2, 28)),
        occurrences(RepeatRule.MonthlyOnDay(30), d(2, 1), d(2, 28)),
    )

    @Test
    fun weeklyMondays() = assertEquals(
        listOf(2, 9, 16, 23, 30).map { d(11, it) },
        occurrences(RepeatRule.Weekly(setOf(MONDAY)), d(11, 1), d(11, 30)),
    )

    @Test
    fun everyThreeDaysFromAnchor() = assertEquals(
        listOf(d(11, 2), d(11, 5), d(11, 8)),
        occurrences(RepeatRule.EveryNDays(3, d(11, 2)), d(11, 1), d(11, 10)),
    )

    @Test
    fun dailyOverRange() = assertEquals(3, occurrences(RepeatRule.Daily, d(11, 1), d(11, 3)).size)

    @Test
    fun badRulesRejected() {
        assertFailsWith<IllegalArgumentException> { RepeatRule.MonthlyOnDay(0) }
        assertFailsWith<IllegalArgumentException> { RepeatRule.EveryNDays(0, d(11, 1)) }
        assertFailsWith<IllegalArgumentException> { RepeatRule.Weekly(emptySet()) }
    }

    @Test
    fun lateTaskRollsOver() {
        assertEquals(TaskState.LATE, taskState(d(11, 13), null, today = d(11, 14)))
        assertEquals(d(11, 13), lateSince(d(11, 13), null, today = d(11, 14)))
    }

    @Test
    fun doneLateKeepsMiss() {
        assertEquals(TaskState.DONE_LATE, taskState(d(11, 13), d(11, 14), today = d(11, 15)))
        assertEquals(d(11, 13), lateSince(d(11, 13), d(11, 14), today = d(11, 15)))
    }

    @Test
    fun doneOnTime() {
        assertEquals(TaskState.DONE_ON_TIME, taskState(d(11, 14), d(11, 14), today = d(11, 15)))
        assertNull(lateSince(d(11, 14), d(11, 14), today = d(11, 15)))
    }

    @Test
    fun undatedIsInbox() = assertEquals(TaskState.INBOX, taskState(null, null, today = d(11, 14)))

    @Test
    fun dueTodayAndUpcoming() {
        assertEquals(TaskState.DUE_TODAY, taskState(d(11, 14), null, today = d(11, 14)))
        assertEquals(TaskState.UPCOMING, taskState(d(11, 15), null, today = d(11, 14)))
    }
}
