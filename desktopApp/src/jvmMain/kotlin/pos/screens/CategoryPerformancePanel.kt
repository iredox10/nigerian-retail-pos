package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.domain.*
import pos.output.CategoryPerformanceService
import pos.ui.components.*

@Composable
internal fun CategoryPerformancePanel(tenantId: String, products: List<Product>, sales: List<Sale>, returns: List<SaleReturn>, range: ReportDateRange) {
    val loaded = remember(tenantId, products, sales, returns, range) {
        runCatching { CategoryPerformanceService.build(tenantId, products, sales, returns, range) }
    }
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Sales by category", "Compare with the preceding period of the same length.")
            Text("Uses current catalogue categories for both periods. Returned goods use their return date. Figures show sales value after returns.", style = MaterialTheme.typography.bodySmall)
            loaded.exceptionOrNull()?.let { InlineNotice("Category report unavailable", it.message.orEmpty(), NoticeTone.Warning) }
            loaded.getOrNull()?.let { comparison ->
                Text("Previous period · ${comparison.previous.from} to ${comparison.previous.through} (WAT)", style = MaterialTheme.typography.bodySmall)
                val maximum = comparison.rows.maxOfOrNull { java.math.BigInteger.valueOf(it.netMinor).abs() } ?: java.math.BigInteger.ZERO
                if (comparison.rows.isEmpty()) EmptyState("No category activity", "Saved sales and returns in either period will appear here.")
                (if (expanded) comparison.rows else comparison.rows.take(8)).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(row.category, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(money(row.netMinor), style = MaterialTheme.typography.titleSmall)
                    }
                    if (maximum.signum() > 0) LinearProgressIndicator(
                        progress = { (java.math.BigInteger.valueOf(row.netMinor).abs().toDouble() / maximum.toDouble()).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = if (row.netMinor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    LabelValueRow("Previous net sales", money(row.previousNetMinor))
                    LabelValueRow("Change in net sales", money(row.changeMinor))
                    HorizontalDivider()
                }
                if (comparison.rows.size > 8) NeutralButton(if (expanded) "Show fewer categories" else "Show all categories", { expanded = !expanded })
            }
        }
    }
}
