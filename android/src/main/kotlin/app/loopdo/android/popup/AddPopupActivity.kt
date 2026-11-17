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
import app.loopdo.android.today.AddSheet
import app.loopdo.android.ui.theme.LoopDoTheme
import kotlinx.coroutines.launch

/** The widget's `[+ add]`: the app's add sheet floating over the home screen, keyboard open (locked widget pop-up 1). */
class AddPopupActivity : ComponentActivity() {
    private val graph get() = (application as LoopDoApp).graph
    private val vm: PopupViewModel by viewModels {
        viewModelFactory { initializer { PopupViewModel(graph.actions, PrefsPopupDrafts(applicationContext)) { graph.redrawWidget() } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val today = graph.todayForPopup()
            if (today == null) { // not signed in / not synced yet: the app explains
                startActivity(Intent(this@AddPopupActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return@launch
            }
            setContent {
                LoopDoTheme(backdrop = false) {
                    var text by rememberSaveable { mutableStateOf(vm.draft(PopupViewModel.ADD)) }
                    var journal by rememberSaveable { mutableStateOf(false) }
                    val error by vm.error.collectAsStateWithLifecycle()
                    AddSheet(
                        today = today.date,
                        lists = today.lists.map { it.id to it.name },
                        text = text, onText = { text = it; vm.keepDraft(PopupViewModel.ADD, it) },
                        journal = journal, onJournal = { journal = it },
                        error = error,
                        onDismiss = { finish() },
                        onAddTask = { title, due, time, listId, _, repeat -> vm.addTask(title, due, time, listId, repeat) { finish() } },
                        onAddJournal = { t -> vm.appendJournal(t) { finish() } },
                    )
                }
            }
        }
    }
}
