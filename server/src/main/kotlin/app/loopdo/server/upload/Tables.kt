package app.loopdo.server.upload

/** How a JSON value is bound: as text, cast by Postgres. */
enum class Col(val cast: String) {
    TEXT(""), INT("::int"), BOOL("::boolean"), DATE("::date"), TIME("::time"),
    TIMESTAMPTZ("::timestamptz"), UUID("::uuid"), JSONB("::jsonb"), SMALLINT_ARRAY("::smallint[]"),
}

/**
 * What the phone may write. `id` and `user_id` are never in [columns]: the id comes
 * from the op and user_id from the token. Columns decided by the server
 * (`verified`, `completed_on`) are not listed, so a client can't send them.
 */
data class TableSpec(val name: String, val ops: Set<String>, val columns: Map<String, Col>)

private val ALL = setOf("PUT", "PATCH", "DELETE")

val TABLES: Map<String, TableSpec> = listOf(
    TableSpec("profiles", setOf("PATCH"), mapOf(
        "display_name" to Col.TEXT, "onboarded_at" to Col.TIMESTAMPTZ, "settings" to Col.JSONB)),
    TableSpec("habits", ALL, mapOf(
        "title" to Col.TEXT, "kind" to Col.TEXT, "target" to Col.INT, "reminder_time" to Col.TIME,
        "archived_on" to Col.DATE, "sort_order" to Col.INT,
        "step" to Col.INT, "unit" to Col.TEXT, "deleted_at" to Col.TIMESTAMPTZ,
        "evidence" to Col.JSONB)), // proof of when an archive was made; checked, not stored (M6.5)
    TableSpec("habit_schedules", setOf("PUT", "DELETE"), mapOf(
        "habit_id" to Col.UUID, "valid_from" to Col.DATE, "recurrence" to Col.TEXT, "weekdays" to Col.SMALLINT_ARRAY,
        "target" to Col.INT, "evidence" to Col.JSONB)),
    TableSpec("habit_logs", setOf("PUT", "PATCH"), mapOf(
        "habit_id" to Col.UUID, "loopdo_date" to Col.DATE, "count" to Col.INT,
        "completed_at" to Col.TIMESTAMPTZ, "evidence" to Col.JSONB)),
    TableSpec("lists", ALL, mapOf("name" to Col.TEXT, "sort_order" to Col.INT)),
    TableSpec("tasks", ALL, mapOf(
        "list_id" to Col.UUID, "title" to Col.TEXT, "notes" to Col.TEXT, "due_date" to Col.DATE,
        "due_time" to Col.TIME, "repeat_rule" to Col.JSONB, "template_id" to Col.UUID,
        "occurrence_date" to Col.DATE, "completed_at" to Col.TIMESTAMPTZ, "evidence" to Col.JSONB,
        "sort_order" to Col.INT, "generated_through" to Col.DATE)),
    TableSpec("subtasks", ALL, mapOf(
        "task_id" to Col.UUID, "title" to Col.TEXT, "done" to Col.BOOL, "sort_order" to Col.INT)),
    TableSpec("journal_entries", ALL, mapOf("loopdo_date" to Col.DATE, "text" to Col.TEXT, "mood" to Col.INT)),
    TableSpec("journal_photos", setOf("PUT", "DELETE"), mapOf(
        "entry_id" to Col.UUID, "r2_key" to Col.TEXT, "width" to Col.INT, "height" to Col.INT,
        "bytes" to Col.INT, "sort_order" to Col.INT, "added_at" to Col.TIMESTAMPTZ)),
    // M8: the weekly focus (removed = deleted_at set inside its window; evidence proves when, checked not stored).
    TableSpec("weekly_focus", setOf("PUT", "PATCH"), mapOf(
        "week_start" to Col.DATE, "kind" to Col.TEXT, "item_id" to Col.UUID, "title" to Col.TEXT, "goal" to Col.INT,
        "deadline" to Col.DATE, "sort_order" to Col.INT, "deleted_at" to Col.TIMESTAMPTZ, "evidence" to Col.JSONB)),
    // M6.5: a journal line is its own row (deleted = deleted_at set, never removed).
    TableSpec("journal_lines", setOf("PUT", "PATCH"), mapOf(
        "entry_id" to Col.UUID, "stamped_at" to Col.TIMESTAMPTZ, "text" to Col.TEXT, "edited_at" to Col.TIMESTAMPTZ,
        "deleted_at" to Col.TIMESTAMPTZ, "sort_order" to Col.INT)),
).associateBy { it.name }

/** Server-decided columns, written alongside the client's. */
val SERVER_COLUMNS: Map<String, Col> = mapOf("verified" to Col.BOOL, "completed_on" to Col.DATE, "late_since" to Col.DATE)
