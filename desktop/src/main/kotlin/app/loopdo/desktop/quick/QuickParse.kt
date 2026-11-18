package app.loopdo.desktop.quick

/** What a quick-add line becomes: `t …` / `t: …` → a task in the inbox; anything else → a journal line. */
sealed interface QuickItem {
    data class Journal(val text: String) : QuickItem
    data class Task(val title: String) : QuickItem
}

private val TASK = Regex("""^[tT]:?(\s+|$)(.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Null when there is nothing to save. */
fun parseQuick(input: String): QuickItem? {
    val s = input.trim()
    if (s.isEmpty()) return null
    TASK.matchEntire(s)?.let { m -> return m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let(QuickItem::Task) }
    return QuickItem.Journal(s)
}
