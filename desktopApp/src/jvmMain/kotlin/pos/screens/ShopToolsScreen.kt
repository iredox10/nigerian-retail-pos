package pos.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pos.domain.StaffPermission
import pos.ui.components.*
import pos.ui.theme.PosPalette

private data class ShopTool(val title: String, val detail: String, val route: String, val icon: ImageVector, val permission: StaffPermission? = null)

@Composable
fun ShopToolsScreen(allowed: (StaffPermission) -> Boolean, open: (String) -> Unit) {
    val groups = listOf(
        "Trading & customers" to listOf(
            ShopTool("Customer orders", "Deposits, stock holds and collections", "Orders", PosIcons.ClipboardList, StaffPermission.MANAGE_CUSTOMERS),
            ShopTool("Quotations", "Prepare and review agreed prices", "Quotations", PosIcons.FileText, StaffPermission.VIEW_SALES),
            ShopTool("Suppliers & payments", "Purchases, deliveries and supplier balances", "Suppliers", PosIcons.Truck, StaffPermission.MANAGE_SUPPLIERS),
            ShopTool("Review customer credit", "Unpaid sales and repayment history", "Credit review", PosIcons.Users, StaffPermission.MANAGE_CUSTOMERS)),
        "Records & reporting" to listOf(
            ShopTool("Reports & CSV exports", "Review trading activity by date", "Reports", PosIcons.ChartNoAxesCombined, StaffPermission.VIEW_REPORTS),
            ShopTool("Activity history", "See who recorded each change", "Audit", PosIcons.History, StaffPermission.VIEW_REPORTS),
            ShopTool("Encrypted shop backup", "Save and restore a copy of your records", "Backup", PosIcons.Database, StaffPermission.BACKUP),
            ShopTool("Import new items from CSV", "Review your catalogue before importing", "Import", PosIcons.Upload, StaffPermission.MANAGE_STOCK)),
        "Your workspace" to listOf(
            ShopTool("Shop & receipt details", "Shop identity, logo and receipt wording", "Shop settings", PosIcons.Store, StaffPermission.MANAGE_SETTINGS),
            ShopTool("Staff & permissions", "Manage the people who use this shop", "Staff", PosIcons.ShieldCheck, StaffPermission.MANAGE_STAFF),
            ShopTool("Help & practice", "Find guidance and try sample sales", "Help", PosIcons.CircleHelp))
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Your shop tools", "Purchasing, records and workspace settings.")
        LazyVerticalGrid(columns = GridCells.Adaptive(270.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEach { (heading, tools) ->
                val visible = tools.filter { it.permission?.let(allowed) != false }
                if (visible.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(visible, key = { it.route }) { tool ->
                        Surface(onClick = { open(tool.route) }, shape = MaterialTheme.shapes.large,
                            color = PosPalette.Surface, border = BorderStroke(1.dp, PosPalette.Border)) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 164.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = PosPalette.AccentTint, shape = MaterialTheme.shapes.medium) {
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { PosIcon(tool.icon, tint = PosPalette.Accent) }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    PosIcon(PosIcons.ArrowRight, tint = PosPalette.Secondary)
                                }
                                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                                Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
