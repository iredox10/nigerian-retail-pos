package pos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import pos.ui.theme.PosTheme

/** Fullscreen is explicit, not a fixed-size window or a window-manager maximize hint. */
@Composable
internal fun DesktopWindow(
    title: String,
    onClose: () -> Unit,
    initialPlacement: WindowPlacement = WindowPlacement.Fullscreen,
    width: Int = 1440,
    height: Int = 900,
    content: @Composable () -> Unit,
) {
    val state = rememberWindowState(placement = initialPlacement, width = width.dp, height = height.dp)
    fun toggleFullscreen() {
        state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    Window(
        onCloseRequest = onClose,
        title = title,
        state = state,
        undecorated = true,
        onPreviewKeyEvent = {
            if (it.key == Key.F11 && it.type == KeyEventType.KeyUp) { toggleFullscreen(); true } else false
        },
    ) {
        PosTheme {
            Column(Modifier.fillMaxSize()) {
                WindowDraggableArea {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Retail POS", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton({ state.isMinimized = true }) { Text("Minimize") }
                            TextButton({ toggleFullscreen() }) { Text(if(state.placement == WindowPlacement.Fullscreen) "Exit fullscreen · F11" else "Fullscreen · F11") }
                            TextButton(onClose) { Text("Close") }
                        }
                    }
                    HorizontalDivider()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}
