package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.help.HelpContent
import pos.help.PracticeLaunchResult
import pos.help.PracticeLauncher
import pos.ui.components.*

@Composable
fun HelpScreen(practiceLauncher: PracticeLauncher, isPractice: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val results = remember(query) { HelpContent.search(query) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeader("Help for your shop", "Available on this computer without internet") }
        item {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Try a practice sale", "A separate window with sample goods and sample money")
                    Text("Practice does not change this shop's stock, sales or cash. Its sample records are discarded when the practice window closes. Closing this app also closes practice.")
                    if (isPractice) InlineNotice("You are already in the practice shop", "Sample goods and money are separate from real shop records.", NoticeTone.Info)
                    else PosButton(if (starting) "Starting practice…" else "Open practice shop", {
                        starting = true; message = null; error = null
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { practiceLauncher.launch() }
                                message = if (result == PracticeLaunchResult.ALREADY_RUNNING)
                                    "A practice window is already open. Switch to the window labelled Sample data."
                                else "Practice window ready. Switch to the window labelled Sample data."
                            } catch (_: Exception) {
                                error = "The practice window could not start. Your shop records have not been changed. Try again or continue using the help below."
                            } finally { starting = false }
                        }
                    }, enabled = !starting)
                    message?.let { InlineNotice(it, tone = NoticeTone.Info) }
                    error?.let { InlineNotice("Practice unavailable", it, NoticeTone.Danger) }
                }
            }
        }
        item { PosTextField(query, { query = it }, "Search help", hint = "Try cash, carton, backup or credit") }
        if (results.isEmpty()) item {
            EmptyState("No matching help", "Try fewer words, or clear the search to see every topic.")
            TextButton({ query = "" }) { Text("Show all topics") }
        }
        items(results, key = { it.id }) { article ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(article.title, article.summary)
                    TextButton({ expanded = if (expanded == article.id) null else article.id }) {
                        Text(if (expanded == article.id) "Hide steps" else "Read steps")
                    }
                    if (expanded == article.id) article.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item { Text("Some tasks need an owner or manager. Reading help or opening practice does not grant access to this shop's restricted records.", style = MaterialTheme.typography.bodySmall) }
    }
}
