package pos.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Lucide outline artwork, distributed under the ISC license in design/licenses. */
object PosIcons {
    val ShoppingCart: ImageVector by lazy { outline("shopping-cart", "M 7.0 21.0 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0", "M 18.0 21.0 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0", "M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12") }
    val ReceiptText: ImageVector by lazy { outline("receipt-text", "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z", "M14 8H8", "M16 12H8", "M13 16H8") }
    val Package: ImageVector by lazy { outline("package", "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z", "M12 22V12", "m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7", "m7.5 4.27 9 5.15") }
    val Users: ImageVector by lazy { outline("users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M 5.0 7.0 a 4.0 4.0 0 1 0 8.0 0 a 4.0 4.0 0 1 0 -8.0 0", "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75") }
    val Wallet: ImageVector by lazy { outline("wallet", "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1", "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4") }
    val LayoutGrid: ImageVector by lazy { outline("layout-grid", "M 4.0 3.0 H 9.0 Q 10.0 3.0 10.0 4.0 V 9.0 Q 10.0 10.0 9.0 10.0 H 4.0 Q 3.0 10.0 3.0 9.0 V 4.0 Q 3.0 3.0 4.0 3.0 Z", "M 15.0 3.0 H 20.0 Q 21.0 3.0 21.0 4.0 V 9.0 Q 21.0 10.0 20.0 10.0 H 15.0 Q 14.0 10.0 14.0 9.0 V 4.0 Q 14.0 3.0 15.0 3.0 Z", "M 15.0 14.0 H 20.0 Q 21.0 14.0 21.0 15.0 V 20.0 Q 21.0 21.0 20.0 21.0 H 15.0 Q 14.0 21.0 14.0 20.0 V 15.0 Q 14.0 14.0 15.0 14.0 Z", "M 4.0 14.0 H 9.0 Q 10.0 14.0 10.0 15.0 V 20.0 Q 10.0 21.0 9.0 21.0 H 4.0 Q 3.0 21.0 3.0 20.0 V 15.0 Q 3.0 14.0 4.0 14.0 Z") }
    val Store: ImageVector by lazy { outline("store", "m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4", "M2 7h20", "M22 7v3a2 2 0 0 1-2 2a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12a2 2 0 0 1-2-2V7") }
    val LockKeyhole: ImageVector by lazy { outline("lock-keyhole", "M 11.0 16.0 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0", "M 5.0 10.0 H 19.0 Q 21.0 10.0 21.0 12.0 V 20.0 Q 21.0 22.0 19.0 22.0 H 5.0 Q 3.0 22.0 3.0 20.0 V 12.0 Q 3.0 10.0 5.0 10.0 Z", "M7 10V7a5 5 0 0 1 10 0v3") }
    val Search: ImageVector by lazy { outline("search", "M 3.0 11.0 a 8.0 8.0 0 1 0 16.0 0 a 8.0 8.0 0 1 0 -16.0 0", "m21 21-4.3-4.3") }
    val Plus: ImageVector by lazy { outline("plus", "M5 12h14", "M12 5v14") }
    val ArrowRight: ImageVector by lazy { outline("arrow-right", "M5 12h14", "m12 5 7 7-7 7") }
    val ArrowLeft: ImageVector by lazy { outline("arrow-left", "m12 19-7-7 7-7", "M19 12H5") }
    val Check: ImageVector by lazy { outline("check", "M20 6 9 17l-5-5") }
    val CircleCheck: ImageVector by lazy { outline("circle-check", "M 2.0 12.0 a 10.0 10.0 0 1 0 20.0 0 a 10.0 10.0 0 1 0 -20.0 0", "m9 12 2 2 4-4") }
    val TriangleAlert: ImageVector by lazy { outline("triangle-alert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01") }
    val Info: ImageVector by lazy { outline("info", "M 2.0 12.0 a 10.0 10.0 0 1 0 20.0 0 a 10.0 10.0 0 1 0 -20.0 0", "M12 16v-4", "M12 8h.01") }
    val CircleHelp: ImageVector by lazy { outline("circle-help", "M 2.0 12.0 a 10.0 10.0 0 1 0 20.0 0 a 10.0 10.0 0 1 0 -20.0 0", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01") }
    val FileText: ImageVector by lazy { outline("file-text", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8") }
    val Truck: ImageVector by lazy { outline("truck", "M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2", "M15 18H9", "M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14", "M 15.0 18.0 a 2.0 2.0 0 1 0 4.0 0 a 2.0 2.0 0 1 0 -4.0 0", "M 5.0 18.0 a 2.0 2.0 0 1 0 4.0 0 a 2.0 2.0 0 1 0 -4.0 0") }
    val ChartNoAxesCombined: ImageVector by lazy { outline("chart-no-axes-combined", "M12 16v5", "M16 14v7", "M20 10v11", "m22 3-8.646 8.646a.5.5 0 0 1-.708 0L9.354 8.354a.5.5 0 0 0-.707 0L2 15", "M4 18v3", "M8 14v7") }
    val History: ImageVector by lazy { outline("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2") }
    val Database: ImageVector by lazy { outline("database", "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0", "M3 5V19A9 3 0 0 0 21 19V5", "M3 12A9 3 0 0 0 21 12") }
    val Settings: ImageVector by lazy { outline("settings", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M 9.0 12.0 a 3.0 3.0 0 1 0 6.0 0 a 3.0 3.0 0 1 0 -6.0 0") }
    val ShieldCheck: ImageVector by lazy { outline("shield-check", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4") }
    val Upload: ImageVector by lazy { outline("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M 17 8 12 3 7 8", "M 12 3 L 12 15") }
    val Image: ImageVector by lazy { outline("image", "M 5.0 3.0 H 19.0 Q 21.0 3.0 21.0 5.0 V 19.0 Q 21.0 21.0 19.0 21.0 H 5.0 Q 3.0 21.0 3.0 19.0 V 5.0 Q 3.0 3.0 5.0 3.0 Z", "M 7.0 9.0 a 2.0 2.0 0 1 0 4.0 0 a 2.0 2.0 0 1 0 -4.0 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Banknote: ImageVector by lazy { outline("banknote", "M 4.0 6.0 H 20.0 Q 22.0 6.0 22.0 8.0 V 16.0 Q 22.0 18.0 20.0 18.0 H 4.0 Q 2.0 18.0 2.0 16.0 V 8.0 Q 2.0 6.0 4.0 6.0 Z", "M 10.0 12.0 a 2.0 2.0 0 1 0 4.0 0 a 2.0 2.0 0 1 0 -4.0 0", "M6 12h.01M18 12h.01") }
    val Tag: ImageVector by lazy { outline("tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", "M 7.0 7.5 a 0.5 0.5 0 1 0 1.0 0 a 0.5 0.5 0 1 0 -1.0 0") }
    val ClipboardList: ImageVector by lazy { outline("clipboard-list", "M 9.0 2.0 H 15.0 Q 16.0 2.0 16.0 3.0 V 5.0 Q 16.0 6.0 15.0 6.0 H 9.0 Q 8.0 6.0 8.0 5.0 V 3.0 Q 8.0 2.0 9.0 2.0 Z", "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01") }
    val Inbox: ImageVector by lazy { outline("inbox", "M 22 12 16 12 14 15 10 15 8 12 2 12", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z") }
    val Monitor: ImageVector by lazy { outline("monitor", "M 4.0 3.0 H 20.0 Q 22.0 3.0 22.0 5.0 V 15.0 Q 22.0 17.0 20.0 17.0 H 4.0 Q 2.0 17.0 2.0 15.0 V 5.0 Q 2.0 3.0 4.0 3.0 Z", "M 8 21 L 16 21", "M 12 17 L 12 21") }
}

private fun outline(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { data ->
            addPath(PathParser().parsePathString(data).toNodes(),
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

@Composable
fun PosIcon(image: ImageVector, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.LocalContentColor.current) {
    Icon(image, contentDescription = null, modifier = modifier.size(20.dp), tint = tint)
}

fun destinationIcon(title: String): ImageVector = when (title) {
    "Sell" -> PosIcons.ShoppingCart
    "Sales" -> PosIcons.ReceiptText
    "Stock" -> PosIcons.Package
    "Customers" -> PosIcons.Users
    "Money" -> PosIcons.Wallet
    "More", "Your shop tools" -> PosIcons.LayoutGrid
    "Customer orders", "Orders", "Stock counts" -> PosIcons.ClipboardList
    "Suppliers", "Purchase orders" -> PosIcons.Truck
    "Reports", "Reorder suggestions" -> PosIcons.ChartNoAxesCombined
    "Activity history" -> PosIcons.History
    "Promotions", "Price lists", "Minimum selling prices" -> PosIcons.Tag
    "Landed costs", "Purchase cost history" -> PosIcons.Banknote
    "Shop & receipt details" -> PosIcons.Store
    "Staff & permissions" -> PosIcons.ShieldCheck
    "Help", "Help & practice" -> PosIcons.CircleHelp
    else -> PosIcons.ClipboardList
}

fun actionIcon(label: String): ImageVector? = when {
    label.startsWith("Back") -> PosIcons.ArrowLeft
    label.startsWith("Choose image") || label.startsWith("Choose another image") -> PosIcons.Image
    label.startsWith("Add") || label.startsWith("New") -> PosIcons.Plus
    label.startsWith("Save") || label.startsWith("Finish") -> PosIcons.Check
    label.startsWith("Print") || label.contains("receipt", true) -> PosIcons.ReceiptText
    label.startsWith("Import") || label.startsWith("Export") -> PosIcons.Upload
    label.startsWith("Stock tools") -> PosIcons.Settings
    label.startsWith("Take payment") || label.startsWith("Record payment") -> PosIcons.Banknote
    label.startsWith("Review") -> PosIcons.ClipboardList
    label.contains("history", true) -> PosIcons.History
    else -> null
}
