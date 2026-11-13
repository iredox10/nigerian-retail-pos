package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.domain.*
import pos.ui.components.*

/** Uses the established Money form and token roles; no new navigation or visual system. */
@Composable
internal fun CashDenominationPanel(inputs: Map<Long, String>, onChange: (Long, String) -> Unit) {
    var showCoins by remember { mutableStateOf(inputs.any { it.key in nairaCoinsMinor && it.value.isNotBlank() }) }
    Text("Count each note or coin once. Leave unused rows blank. If the drawer is empty, enter 0 in any row.", style = MaterialTheme.typography.bodySmall)
    nairaBanknotesMinor.forEach { denomination -> CashCountRow(denomination, inputs[denomination].orEmpty(), false, onChange) }
    TextButton({ showCoins = !showCoins }) { Text(if (showCoins) "Hide coin rows" else "Count coins") }
    if (showCoins) nairaCoinsMinor.forEach { denomination -> CashCountRow(denomination, inputs[denomination].orEmpty(), true, onChange) }
    val count = cashCountFromInputs(inputs)
    if (count == null) InlineNotice("Check your count", "Use whole counts of notes and coins within the supported amount.", NoticeTone.Danger)
    else LabelValueRow("Cash counted", money(count.totalMinor), emphasized = true)
}

@Composable
private fun CashCountRow(denomination: Long, value: String, coin: Boolean, onChange: (Long, String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PosTextField(value, { onChange(denomination, it) }, "${money(denomination)} ${if (coin) "coins" else "notes"}", Modifier.weight(1f), hint = "Number, e.g. 3")
        val subtotal = if (value.isBlank()) 0L else cashCountFromInputs(mapOf(denomination to value))?.totalMinor
        Text(subtotal?.let(::money) ?: "Check count", Modifier.widthIn(min = 96.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SavedCashCount(title: String, count: CashCount) {
    var expanded by remember(count, title) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide $title" else "View $title") }
    if (expanded) {
        if (count.counts.isEmpty()) Text("No notes or coins counted", style = MaterialTheme.typography.bodySmall)
        count.counts.entries.sortedByDescending { it.key }.forEach { (denomination, pieces) ->
            LabelValueRow("${money(denomination)} × $pieces", money(denomination * pieces))
        }
        LabelValueRow("Count total", money(count.totalMinor), emphasized = true)
    }
}
