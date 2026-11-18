package app.loopdo.desktop.ui

import app.loopdo.client.journal.JournalViewModel
import app.loopdo.desktop.journal.compressPhoto
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** The phone's JournalViewModel behind the laptop's [JournalUi]; photos are compressed into [photoDir]. */
class JournalBinding(
    private val vm: JournalViewModel,
    private val photoDir: File,
    private val store: app.loopdo.desktop.store.FilePopupDrafts,
    private val lines: app.loopdo.client.journal.LineDrafts,
) : JournalUi {
    override val draftVersion = store.version
    override fun draftFor(date: LocalDate) = vm.draftFor(date)
    override fun draftDates(): Set<LocalDate> = lines.dates().filter { lines.line(it).first.isNotEmpty() }.toSet()
    override fun keepDraft(date: LocalDate, text: String) = vm.keepDraft(date, text)
    override fun addLines(date: LocalDate, input: String, onSaved: () -> Unit, onError: (String) -> Unit) = vm.addLines(date, input, onSaved, onError)
    override fun editLine(date: LocalDate, lineId: String, new: String, onSaved: () -> Unit, onError: (String) -> Unit) { vm.editLine(date, lineId, new, onSaved, onError) }
    override fun deleteLine(date: LocalDate, lineId: String) { vm.deleteLine(date, lineId) }
    override fun setMood(date: LocalDate, mood: Int?) { vm.setMood(date, mood) }
    override fun deletePhoto(id: String) { vm.deletePhoto(id) }
    override fun addPhoto(date: LocalDate, file: File) {
        vm.addPhoto(date) { val id = UUID.randomUUID(); compressPhoto(file, File(photoDir, "$id.webp"), id) }
    }
    override suspend fun photo(id: String, localPath: String?): File? = vm.photo(id, localPath)
}

/** The desktop's "add a photo" dialog (KDE's file chooser through AWT); null when cancelled. */
fun pickPhotoFile(): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "add a photo", java.awt.FileDialog.LOAD)
    d.setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.', "") in setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp") }
    d.isMultipleMode = false
    d.isVisible = true
    return d.files.firstOrNull()
}
