package app.loopdo.android.popup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.loopdo.android.LoopDoApp
import app.loopdo.android.MainActivity
import app.loopdo.android.today.short
import app.loopdo.android.ui.theme.LoopDoTheme
import kotlinx.coroutines.launch

/**
 * The widget's `[✎ journal]`: the day's entry (mood + text) floating over the home screen.
 * The LoopDo date is captured when it opens and the save goes to that date, even after day end
 * (journal entries stay editable; only ticks are frozen).
 */
class JournalPopupActivity : ComponentActivity() {
    private val graph get() = (application as LoopDoApp).graph
    private val vm: PopupViewModel by viewModels {
        viewModelFactory { initializer { PopupViewModel(graph.actions, PrefsPopupDrafts(applicationContext)) { graph.redrawWidget() } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val today = graph.todayForPopup()
            if (today == null) {
                startActivity(Intent(this@JournalPopupActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return@launch
            }
            // Unsaved text from an earlier day reopens on that day (it stays editable), never stranded.
            val date = journalDateToOpen(today.date, vm.journalDraftDates())
            val entry = graph.todayData.journalOn(date)
            val current = entry?.text.orEmpty()
            // A draft is replayed on top of the entry as it is now (owner: merge, never overwrite).
            val startText = vm.journalDraft(date)?.let { (draft, base) -> mergeJournal(current, base, draft) } ?: current
            vm.keepJournalDraft(date, startText, current)
            val notice = if (date != today.date) "unsaved text from ${date.short()}: save it or clear it." else null
            setContent {
                LoopDoTheme(backdrop = false) {
                    var text by rememberSaveable { mutableStateOf(startText) }
                    var mood by rememberSaveable { mutableStateOf(entry?.mood) }
                    val error by vm.error.collectAsStateWithLifecycle()
                    JournalSheet(
                        dateLabel = date.short(), streak = today.streaks.journal,
                        text = text, onText = { text = it; vm.keepJournalDraft(date, it, current) },
                        mood = mood, onMood = { mood = it },
                        error = error, notice = notice,
                        onOpenApp = {
                            startActivity(
                                Intent(this@JournalPopupActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_TAB, "journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onSave = { vm.saveJournal(date, text, mood, base = current) { finish() } },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }
}
