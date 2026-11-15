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
            val date = today.date
            val key = PopupViewModel.journalKey(date)
            val entry = graph.todayData.journalOn(date)
            val startText = vm.draft(key).ifEmpty { entry?.text.orEmpty() }
            setContent {
                LoopDoTheme(backdrop = false) {
                    var text by rememberSaveable { mutableStateOf(startText) }
                    var mood by rememberSaveable { mutableStateOf(entry?.mood) }
                    val error by vm.error.collectAsStateWithLifecycle()
                    JournalSheet(
                        dateLabel = date.short(), streak = today.streaks.journal,
                        text = text, onText = { text = it; vm.keepDraft(key, it) },
                        mood = mood, onMood = { mood = it },
                        error = error,
                        onOpenApp = {
                            startActivity(
                                Intent(this@JournalPopupActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_TAB, "journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onSave = { vm.saveJournal(date, text, mood) { finish() } },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }
}
