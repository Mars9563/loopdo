package app.loopdo.client.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table
import com.powersync.db.schema.TrackPreviousValuesOptions

/**
 * The phone's copy of the 10 synced tables (supabase/migrations/0001) plus two
 * local-only tables. PowerSync stores uuid/date/time/jsonb as text, booleans as integers.
 */
object LoopDoSchema {
    private fun t(vararg names: String) = names.map(Column::text)
    private fun i(vararg names: String) = names.map(Column::integer)
    private val stamps = t("created_at", "updated_at")

    val schema = Schema(
        Table("profiles", t("display_name", "onboarded_at", "settings", "delete_requested_at", "wipe_claimed_at") + i("is_admin") + stamps),
        Table("day_settings_versions", t("user_id", "effective_at", "time_zone", "day_end", "floor_date") + stamps),
        // evidence: proof of when an archive was made, sent up only (the server checks it, never stores it).
        Table("habits", t("user_id", "title", "kind", "reminder_time", "archived_on", "unit", "deleted_at", "evidence") + i("target", "sort_order", "step") + stamps),
        Table("habit_schedules", t("user_id", "habit_id", "valid_from", "recurrence", "weekdays", "evidence") + i("target") + stamps),
        // M6.5: the old count goes up with each change (the server adds differences) and _metadata carries the retry key.
        Table(
            name = "habit_logs",
            columns = t("user_id", "habit_id", "loopdo_date", "completed_at", "evidence") + i("count", "verified") + stamps,
            trackMetadata = true,
            trackPreviousValues = TrackPreviousValuesOptions(columnFilter = listOf("count")),
        ),
        Table("lists", t("user_id", "name") + i("sort_order") + stamps),
        Table(
            "tasks",
            t("user_id", "list_id", "title", "notes", "due_date", "due_time", "repeat_rule", "template_id",
                "occurrence_date", "completed_at", "completed_on", "evidence", "late_since", "generated_through") + i("verified", "sort_order") + stamps,
        ),
        Table("subtasks", t("user_id", "task_id", "title") + i("done", "sort_order") + stamps),
        Table("journal_entries", t("user_id", "loopdo_date", "text") + i("mood") + stamps),
        // M6.5: each journal line is a row; the old edited_at goes up with a change (a stale delete is skipped).
        Table(
            name = "journal_lines",
            columns = t("user_id", "entry_id", "stamped_at", "text", "edited_at", "deleted_at") + i("sort_order") + stamps,
            trackPreviousValues = TrackPreviousValuesOptions(columnFilter = listOf("edited_at")),
        ),
        // M8: the weekly focus (1–2 promises a week; removed = deleted_at). evidence proves the day it was set.
        Table("weekly_focus", t("user_id", "week_start", "kind", "item_id", "title", "deadline", "deleted_at", "evidence") + i("goal", "sort_order") + stamps),
        Table("journal_photos", t("user_id", "entry_id", "r2_key", "added_at") + i("width", "height", "bytes", "sort_order") + stamps),
        // D7: a laptop's "in use" signal (one row per laptop; the phone reads active_until at ping time).
        Table("device_presence", t("user_id", "device_id", "active_until") + stamps),
        // Local-only: never uploaded.
        Table.localOnly("drafts", t("kind", "payload", "reason", "created_at")),
        Table.localOnly("upload_state", t("first_failure_at") + i("tx_id")),
        // M6: a photo file on this phone and whether it has reached R2 (the queue).
        Table.localOnly("photo_files", t("path") + i("uploaded")),
    )
}
