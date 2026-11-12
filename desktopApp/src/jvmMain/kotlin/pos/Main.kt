package pos

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.*
import pos.domain.*
import pos.data.PosRepository
import pos.security.WorkspaceAccess
import pos.screens.SellScreen
import pos.screens.StockScreen
import pos.screens.MoneyScreen
import pos.screens.SalesScreen
import pos.screens.ReportsScreen
import pos.screens.SuppliersScreen
import pos.screens.BackupPanel
import pos.screens.RestoreBackupDialog
import pos.screens.ProductImportScreen
import pos.screens.CustomerWorkspace
import pos.ui.theme.PosTheme

fun main() = application {
    val repository = remember { PosRepository(System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos") + "/pos.db") }
    DesktopWindow(title = "Retail POS", onClose = ::exitApplication) { App(repository) }
}

@Composable internal fun App(repo: PosRepository, initialTenant: Tenant? = null, initialRoute: String = "Sell") {
    var tenant by remember { mutableStateOf<Tenant?>(initialTenant) }
    val access = remember { WorkspaceAccess() }
    var revision by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf(initialRoute) }
    var notice by remember { mutableStateOf<String?>(null) }
    val saleStates = rememberSaveableStateHolder()
    if (tenant == null) {
        WorkspacePicker(repo, access) { tenant = it; route = "Sell"; revision++ }
        return
    }
    val current = tenant!!
    val products = remember(current.id, revision) { repo.products(current.id) }
    val customers = remember(current.id, revision) { repo.customers(current.id) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(208.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RETAIL / POS", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                Text(current.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                Text("Owner workspace", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(20.dp))
                listOf("Sell", "Sales", "Stock", "Customers", "Money", "More").forEach { item ->
                    val selected = route == item || (item == "More" && route in setOf("Reports","Suppliers","Backup","Import"))
                    Surface(onClick = { route = item; notice = null }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                        Text(item, Modifier.fillMaxWidth().padding(14.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Local workspace", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { tenant = null }) { Text("Lock / switch shop") }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(route, style = MaterialTheme.typography.headlineSmall)
                    if (route in setOf("Reports","Suppliers","Backup","Import")) TextButton({ route = if(route=="Import") "Stock" else "More" }) { Text(if(route=="Import") "Back to stock" else "Back to shop tools") }
                    Spacer(Modifier.weight(1f))
                    if (route == "Sell") {
                        val activeShift = remember(current.id, revision) { repo.activeCashShift(current.id) }
                        TextButton(onClick = { route = "Money" }) {
                            Text(if (activeShift == null) "Open today's cash" else "Cash drawer open")
                        }
                    }
                    Text("${current.name}  /  Counter 1", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f).padding(24.dp)) {
                    when (route) {
                        "Sell" -> saleStates.SaveableStateProvider(current.id) { SellScreen(current.id, products, customers) { lines, cash, customer, operationId ->
                            try { repo.checkout(current.id, lines, cash, customer, operationId); revision++; null } catch (e: Exception) { e.message ?: "Sale not saved" }
                        } }
                        "Stock" -> StockScreen(repo, current.id, onChanged={ revision++ }, onImport={ route="Import" })
                        "Customers" -> CustomerWorkspace(repo,current.id) { revision++ }
                        "Sales" -> SalesScreen(repo, current.id) { revision++ }
                        "Reports" -> ReportsScreen(repo, current.id)
                        "Suppliers" -> SuppliersScreen(repo, current.id) { revision++ }
                        "Backup" -> BackupPanel(repo, current)
                        "Import" -> ProductImportScreen(repo, current.id) { revision++ }
                        "Money" -> MoneyScreen(repo, current.id) { revision++ }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("Your shop tools", style = MaterialTheme.typography.headlineSmall)
                            Text("Choose the work you need to do.")
                            pos.ui.components.NeutralButton("Suppliers & payments", { route = "Suppliers" })
                            pos.ui.components.NeutralButton("Reports & CSV exports", { route = "Reports" })
                            pos.ui.components.NeutralButton("Encrypted shop backup", { route = "Backup" })
                            pos.ui.components.NeutralButton("Import new items from CSV", { route = "Import" })
                            Text("Saved work: ${repo.pendingSyncCount(current.id)} local changes are waiting for online setup. No online backup has been created.", Modifier.widthIn(max=680.dp))
                            Text("Online accounts, staff roles, packaging conversions and external payment services remain under development.", Modifier.widthIn(max=680.dp))
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Text("On this computer · Online services not connected", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text("This shop only", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun WorkspacePicker(repo: PosRepository, access: WorkspaceAccess, onEnter: (Tenant)->Unit) {
    var tenants by remember { mutableStateOf(repo.tenants()) }
    var selected by remember { mutableStateOf<Tenant?>(null) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    if(restoring) RestoreBackupDialog(repo,access,{restoring=false}) { restoring=false; onEnter(it) }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(64.dp), horizontalArrangement=Arrangement.spacedBy(64.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Text("Your shop.\nYour records.", style=MaterialTheme.typography.displaySmall, fontWeight=FontWeight.SemiBold)
            Text("Choose goods. Record sales.\nKeep trading without internet.", style=MaterialTheme.typography.headlineSmall)
            Text("Each business has its own workspace.\nYour records stay on this computer until online sync is configured.")
        }
        Surface(Modifier.width(440.dp), shape=MaterialTheme.shapes.large) { Column(Modifier.padding(32.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(selected == null) "Open your shop" else selected!!.name, style=MaterialTheme.typography.headlineSmall)
            if(tenants.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) { tenants.forEach { t -> FilterChip(selected=selected?.id==t.id,onClick={selected=t; error=null;password=""},label={Text(t.name)}) } }
            if(selected==null) OutlinedTextField(name,{name=it},label={Text("Shop name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("Owner password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Button(shape=MaterialTheme.shapes.small,onClick={ try {
                if(selected==null) {
                    require(name.isNotBlank()) { "Enter your shop name" }; require(password.length>=8) { "Use at least 8 characters" }
                    val t=repo.createTenant(name.trim()) { access.register(it.id,password) }; tenants=repo.tenants(); onEnter(t)
                } else { require(access.unlock(selected!!.id,password)) { "Password not recognized for this shop" }; onEnter(selected!!) }
            } catch(e:Exception) {error=e.message ?: "Could not open shop"}},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(selected==null) "Create shop workspace" else "Unlock shop") }
            if(selected!=null) TextButton(shape=MaterialTheme.shapes.small,onClick={selected=null;password=""}) { Text("Create another shop") }
            TextButton({ restoring=true }) { Text("Restore an encrypted backup") }
            Text("No online account is created. Use a trusted computer; local database encryption is not yet enabled.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}
