package app.loopdo.client.journal

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the journal screens ask of the database (JournalActions; a fake in tests). */
interface JournalCommands {
    suspend fun addLines(date: LocalDate, input: String, at: Instant? = null)
    /** [lineId] = the line's row; its time stays. */
    suspend fun editLine(date: LocalDate, lineId: String, newText: String)
    suspend fun deleteLine(date: LocalDate, lineId: String)
    suspend fun setMood(date: LocalDate, mood: Int?)
    suspend fun addPhoto(date: LocalDate, photo: PreparedPhoto): UUID
    suspend fun deletePhoto(id: UUID)
}
