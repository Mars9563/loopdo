package app.loopdo.android.popup

import app.loopdo.client.popup.PopupViewModel

import app.loopdo.client.popup.journalDateToOpen

import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import app.loopdo.client.today.short
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
            val earlier = date != today.date
            val entry = graph.todayData.journalOn(date)
            // M6 (locked journal-log-v2): the pop-up writes one new line, stamped when it's saved; an earlier
            // day's draft keeps the time it was last edited.
            val (startText, draftAt) = vm.lineDraft(date)
            val now = graph.trustedClock.now().instant
            val zone = app.loopdo.shared.day.settingsAt(graph.todayData.versionsNow(), now).zone
            val stampTime = (if (earlier) draftAt ?: now else now).atZone(zone).toLocalTime()
            val stamp = String.format(java.util.Locale.ROOT, "[%02d:%02d]", stampTime.hour, stampTime.minute)
            val notice = if (earlier) "unsaved text from ${date.short()}: save it or clear it." else null
            setContent {
                LoopDoTheme(backdrop = false) {
                    var text by rememberSaveable { mutableStateOf(startText) }
                    var mood by rememberSaveable { mutableStateOf(entry?.mood) }
                    // The stamp a save gets now: it follows the clock while the pop-up stays open (M6 minor).
                    var shownStamp by remember { mutableStateOf(stamp) }
                    if (!earlier) LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(10_000)
                            val t = graph.trustedClock.now().instant.atZone(zone).toLocalTime()
                            shownStamp = String.format(java.util.Locale.ROOT, "[%02d:%02d]", t.hour, t.minute)
                        }
                    }
                    val error by vm.error.collectAsStateWithLifecycle()
                    app.loopdo.android.journal.WriterSheet(
                        title = "~/journal ${date.short()}", stamp = shownStamp,
                        text = text, onText = { text = it; vm.keepLineDraft(date, it, graph.trustedClock.now().instant, earlierDay = earlier) },
                        mood = mood, onMood = { mood = it; vm.setMood(date, it) },
                        error = error, onPhoto = null, notice = notice,
                        onCancel = { finish() },
                        onSave = { vm.saveLine(date, text, earlierDay = earlier) { finish() } },
                        // The typed text stays as the shared line draft: the app's writer opens with it.
                        onOpenApp = {
                            startActivity(
                                Intent(this@JournalPopupActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_TAB, "journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }
}
