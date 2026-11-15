package app.loopdo.android.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * The phone's copy of the 10 synced tables (supabase/migrations/0001) plus two
 * local-only tables. PowerSync stores uuid/date/time/jsonb as text, booleans as integers.
 */
object LoopDoSchema {
    private fun t(vararg names: String) = names.map(Column::text)
    private fun i(vararg names: String) = names.map(Column::integer)
    private val stamps = t("created_at", "updated_at")

    val schema = Schema(
        Table("profiles", t("display_name", "onboarded_at", "settings") + i("is_admin") + stamps),
        Table("day_settings_versions", t("user_id", "effective_at", "time_zone", "day_end", "floor_date") + stamps),
        Table("habits", t("user_id", "title", "kind", "reminder_time", "archived_on") + i("target", "sort_order") + stamps),
        Table("habit_schedules", t("user_id", "habit_id", "valid_from", "recurrence", "weekdays") + stamps),
        Table("habit_logs", t("user_id", "habit_id", "loopdo_date", "completed_at", "evidence") + i("count", "verified") + stamps),
        Table("lists", t("user_id", "name") + i("sort_order") + stamps),
        Table(
            "tasks",
            t("user_id", "list_id", "title", "notes", "due_date", "due_time", "repeat_rule", "template_id",
                "occurrence_date", "completed_at", "completed_on", "evidence") + i("verified", "sort_order") + stamps,
        ),
        Table("subtasks", t("user_id", "task_id", "title") + i("done", "sort_order") + stamps),
        Table("journal_entries", t("user_id", "loopdo_date", "text") + i("mood") + stamps),
        Table("journal_photos", t("user_id", "entry_id", "r2_key") + i("width", "height", "bytes", "sort_order") + stamps),
        // Local-only: never uploaded.
        Table.localOnly("drafts", t("kind", "payload", "reason", "created_at")),
        Table.localOnly("upload_state", t("first_failure_at") + i("tx_id")),
    )
}
