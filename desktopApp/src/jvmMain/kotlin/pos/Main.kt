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
import pos.security.StaffAccess
import pos.screens.StaffScreen
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
import pos.screens.CreditReviewScreen
import pos.ui.theme.PosTheme

private fun localDatabasePath() = System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos") + "/pos.db"

fun main() = application {
    val repository = remember { PosRepository(localDatabasePath()) }
    DesktopWindow(title = "Retail POS", onClose = ::exitApplication) { App(repository) }
}

@Composable internal fun App(repo: PosRepository, initialTenant: Tenant? = null, initialRoute: String = "Sell", initialSession: StaffSession? = null, initialStaffAccess: StaffAccess? = null) {
    var tenant by remember { mutableStateOf<Tenant?>(initialTenant) }
    val access = remember { WorkspaceAccess() }
    val staffAccess = remember { initialStaffAccess ?: StaffAccess() }
    DisposableEffect(staffAccess) { onDispose { staffAccess.close() } }
    var session by remember { mutableStateOf<StaffSession?>(initialSession) }
    // Only the isolated preview may run without a credential-issued session.
    val isolatedPreview = initialTenant != null && initialSession == null
    repo.setPermissionCheck { tenantId, permission ->
        if (!isolatedPreview) {
            val active = requireNotNull(session) { "Unlock this shop before making changes." }
            require(active.tenantId == tenantId) { "This session belongs to another shop." }
            staffAccess.requireSession(active, permission)
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf(initialRoute) }
    var notice by remember { mutableStateOf<String?>(null) }
    val saleStates = rememberSaveableStateHolder()
    if (tenant == null) {
        WorkspacePicker(repo, access, staffAccess) { shop, active -> tenant = shop; session = active; route = "Sell"; revision++ }
        return
    }
    val current = tenant!!
    val activeRole = session?.role ?: if(isolatedPreview) StaffRole.OWNER else StaffRole.CASHIER
    fun allowed(permission: StaffPermission) = isolatedPreview || session?.role?.allows(permission)==true
    fun routeAllowed(destination: String): Boolean = when(destination) {
        "Sell" -> allowed(StaffPermission.SELL)
        "Sales" -> allowed(StaffPermission.VIEW_SALES)
        "Stock","Import" -> allowed(StaffPermission.MANAGE_STOCK)
        "Customers","Credit review" -> allowed(StaffPermission.MANAGE_CUSTOMERS)
        "Money" -> allowed(StaffPermission.MANAGE_MONEY)
        "Reports" -> allowed(StaffPermission.VIEW_REPORTS)
        "Suppliers" -> allowed(StaffPermission.MANAGE_SUPPLIERS)
        "Backup" -> allowed(StaffPermission.BACKUP)
        "Staff" -> allowed(StaffPermission.MANAGE_STAFF)
        else -> true
    }
    LaunchedEffect(session?.token) {
        val active=session ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if(runCatching { staffAccess.requireSession(active,StaffPermission.SELL) }.isFailure) {
                staffAccess.logout(active);session=null;tenant=null
                saleStates.removeState(current.id)
                return@LaunchedEffect
            }
        }
    }
    fun lock() {
        session?.let { staffAccess.logout(it) }
        session = null; tenant = null
        saleStates.removeState(current.id)
    }
    val products = remember(current.id, revision) { repo.products(current.id) }
    val customers = remember(current.id, revision) { if (allowed(StaffPermission.SELL_CREDIT) || allowed(StaffPermission.MANAGE_CUSTOMERS)) repo.customers(current.id) else emptyList() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(208.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RETAIL / POS", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                Text(current.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                Text(session?.let { "${it.name} · ${it.role.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() }}" } ?: "Isolated owner preview", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(20.dp))
                listOf("Sell", "Sales", "Stock", "Customers", "Money", "More").filter { routeAllowed(it) && (it != "More" || activeRole != StaffRole.CASHIER) }.forEach { item ->
                    val selected = route == item || (item == "More" && route in setOf("Reports","Suppliers","Backup","Import","Credit review","Staff"))
                    Surface(onClick = {
                        try {
                            session?.let { staffAccess.requireSession(it,StaffPermission.SELL) }
                            route = item; notice = null
                        } catch(e:Exception) { lock() }
                    }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                        Text(item, Modifier.fillMaxWidth().padding(14.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Local workspace", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { lock() }) { Text("Lock / switch shop") }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(route, style = MaterialTheme.typography.headlineSmall)
                    if (route in setOf("Reports","Suppliers","Backup","Import","Credit review","Staff")) TextButton({ route = if(route=="Import") "Stock" else "More" }) { Text(if(route=="Import") "Back to stock" else "Back to shop tools") }
                    Spacer(Modifier.weight(1f))
                    if (route == "Sell" && allowed(StaffPermission.MANAGE_MONEY)) {
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
                    if (!routeAllowed(route)) Text("Your role cannot open this workspace. Lock the shop to sign in with another account.")
                    else when (route) {
                        "Sell" -> saleStates.SaveableStateProvider(current.id) { SellScreen(current.id, products, customers, remember(current.id, revision) { repo.sellingUnits(current.id) }, allowCredit = allowed(StaffPermission.SELL_CREDIT), allowDiscounts = allowed(StaffPermission.DISCOUNT)) { lines, cash, customer, operationId, dueDate ->
                            try { repo.checkout(current.id, lines, cash, customer, operationId,dueDate); revision++; null } catch (e: Exception) { e.message ?: "Sale not saved" }
                        } }
                        "Stock" -> StockScreen(repo, current.id, onChanged={ revision++ }, onImport={ route="Import" })
                        "Customers" -> CustomerWorkspace(repo,current.id) { revision++ }
                        "Sales" -> SalesScreen(repo, current.id, allowReturns = allowed(StaffPermission.RETURN_SALE)) { revision++ }
                        "Credit review" -> CreditReviewScreen(repo,current.id)
                        "Reports" -> ReportsScreen(repo, current.id)
                        "Suppliers" -> SuppliersScreen(repo, current.id) { revision++ }
                        "Backup" -> BackupPanel(repo, current)
                        "Staff" -> session?.let { StaffScreen(staffAccess,it) { revision++ } } ?: Text("Staff management requires an owner sign-in.")
                        "Import" -> ProductImportScreen(repo, current.id) { revision++ }
                        "Money" -> MoneyScreen(repo, current.id) { revision++ }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("Your shop tools", style = MaterialTheme.typography.headlineSmall)
                            Text("Choose the work you need to do.")
                            if(allowed(StaffPermission.MANAGE_SUPPLIERS)) pos.ui.components.NeutralButton("Suppliers & payments", { route = "Suppliers" })
                            if(allowed(StaffPermission.MANAGE_CUSTOMERS)) pos.ui.components.NeutralButton("Review customer credit", { route="Credit review" })
                            if(allowed(StaffPermission.VIEW_REPORTS)) pos.ui.components.NeutralButton("Reports & CSV exports", { route = "Reports" })
                            if(allowed(StaffPermission.BACKUP)) pos.ui.components.NeutralButton("Encrypted shop backup", { route = "Backup" })
                            if(allowed(StaffPermission.MANAGE_STOCK)) pos.ui.components.NeutralButton("Import new items from CSV", { route = "Import" })
                            Text("Saved work: ${repo.pendingSyncCount(current.id)} local changes are waiting for online setup. No online backup has been created.", Modifier.widthIn(max=680.dp))
                            if(allowed(StaffPermission.MANAGE_STAFF)) pos.ui.components.NeutralButton("Staff & permissions", { route="Staff" })
                            Text("Online accounts, staff approval requests and external payment services remain under development.", Modifier.widthIn(max=680.dp))
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

@Composable private fun WorkspacePicker(repo: PosRepository, access: WorkspaceAccess, staffAccess: StaffAccess, onEnter: (Tenant,StaffSession)->Unit) {
    var tenants by remember { mutableStateOf(repo.tenants()) }
    var selected by remember { mutableStateOf<Tenant?>(null) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var staffId by remember { mutableStateOf<String?>(null) }
    val signInMembers = remember(selected?.id) { selected?.let { runCatching { staffAccess.signInMembers(it.id) }.getOrDefault(emptyList()) }.orEmpty() }
    if(restoring) {
        // Backup password plus new-owner provisioning authorizes this recovery-only connection.
        // The active application connection remains locked; recovery never bypasses its session check.
        val recoveryRepo=remember { PosRepository(localDatabasePath()) }
        DisposableEffect(recoveryRepo) { onDispose { recoveryRepo.close() } }
        RestoreBackupDialog(recoveryRepo,access,{restoring=false}) {
            restoring=false;tenants=repo.tenants();selected=it;staffId=null;password=""
            error="Backup restored. Sign in with the new owner password."
        }
    }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(64.dp), horizontalArrangement=Arrangement.spacedBy(64.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Text("Your shop.\nYour records.", style=MaterialTheme.typography.displaySmall, fontWeight=FontWeight.SemiBold)
            Text("Choose goods. Record sales.\nKeep trading without internet.", style=MaterialTheme.typography.headlineSmall)
            Text("Each business has its own workspace.\nYour records stay on this computer until online sync is configured.")
        }
        Surface(Modifier.width(440.dp), shape=MaterialTheme.shapes.large) { Column(Modifier.padding(32.dp).heightIn(max=760.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(selected == null) "Open your shop" else selected!!.name, style=MaterialTheme.typography.headlineSmall)
            if(tenants.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) { tenants.forEach { t -> FilterChip(selected=selected?.id==t.id,onClick={selected=t; staffId=null;error=null;password=""},label={Text(t.name)}) } }
            if(selected==null) OutlinedTextField(name,{name=it},label={Text("Shop name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(selected!=null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(staffId==null,{staffId=null;password="";error=null},label={Text("Owner")})
                signInMembers.forEach { member -> FilterChip(staffId==member.id,{staffId=member.id;password="";error=null},label={Text(member.name)}) }
            }
            OutlinedTextField(password,{password=it},label={Text(if(staffId==null) "Owner password" else "Staff password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Button(shape=MaterialTheme.shapes.small,onClick={ try {
                if(selected==null) {
                    require(name.isNotBlank()) { "Enter your shop name" }; require(password.length>=8) { "Use at least 8 characters" }
                    val t=repo.createTenant(name.trim()) { access.register(it.id,password) }; tenants=repo.tenants(); onEnter(t,requireNotNull(staffAccess.unlockOwner(t.id,password)))
                } else {
                    val active = if(staffId==null) staffAccess.unlockOwner(selected!!.id,password) else staffAccess.unlockStaff(selected!!.id,staffId!!,password)
                    require(active!=null) { "Account or password not recognized for this shop." }
                    onEnter(selected!!,active);password=""
                }
            } catch(e:Exception) {error=e.message ?: "Could not open shop"}},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(selected==null) "Create shop workspace" else "Unlock shop") }
            if(selected!=null) TextButton(shape=MaterialTheme.shapes.small,onClick={selected=null;staffId=null;password=""}) { Text("Create another shop") }
            TextButton({ restoring=true }) { Text("Restore an encrypted backup") }
            Text("No online account is created. Use a trusted computer; local database encryption is not yet enabled.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}
