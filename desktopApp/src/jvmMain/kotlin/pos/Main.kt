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
import pos.data.imageSelection
import pos.data.imageBytes
import pos.data.PosRepository
import pos.security.WorkspaceAccess
import pos.security.StaffAccess
import pos.screens.StaffScreen
import pos.screens.ShopToolsScreen
import pos.screens.AuditScreen
import pos.screens.SellScreen
import pos.screens.StockScreen
import pos.screens.ShopSettingsScreen
import pos.screens.QuotationsScreen
import pos.data.minimumPriceApprovalRequest
import pos.screens.BundlesScreen
import pos.screens.CashHandoverScreen
import pos.screens.MinimumPriceApprovalDialog
import pos.screens.CostingScreen
import pos.screens.CustomerAdvancesScreen
import pos.screens.OwnerRecoveryScreen
import pos.screens.OwnerRecoveryDialog
import pos.screens.VariantMatrixScreen
import pos.screens.CustomerDisputesScreen
import pos.screens.OrderDispatchScreen
import pos.screens.CustomerOrdersScreen
import pos.screens.LandedCostsScreen
import pos.screens.PromotionsScreen
import pos.screens.MinimumPricesScreen
import pos.screens.RepackingScreen
import pos.screens.ReorderSuggestionsScreen
import pos.screens.PurchaseCostHistoryScreen
import pos.screens.BulkProductPhotosScreen
import pos.screens.PriceListsScreen
import pos.screens.BulkCatalogueUpdateScreen
import pos.screens.InventoryDispositionsScreen
import pos.screens.StockCountSessionsScreen
import pos.screens.MoneyScreen
import pos.screens.SalesScreen
import pos.screens.ReportsScreen
import pos.screens.SuppliersScreen
import pos.screens.BackupPanel
import pos.screens.RestoreBackupDialog
import pos.screens.ProductImportScreen
import pos.screens.CustomerWorkspace
import pos.screens.CreditReviewScreen
import pos.screens.RepaymentRemindersScreen
import pos.screens.HelpScreen
import pos.screens.PinSetupPanel
import pos.help.PracticeLauncher
import pos.ui.theme.PosTheme
import pos.ui.components.*
import pos.ui.theme.PosPalette

private fun localDatabasePath() = System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos") + "/pos.db"

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--practice"))) {
        runPracticeApplication()
        return
    }
    application {
        val repository = remember { PosRepository(localDatabasePath()) }
        DisposableEffect(repository) { onDispose { repository.close() } }
        DesktopWindow(title = "Retail POS", onClose = ::exitApplication) { App(repository) }
    }
}

@Composable internal fun App(repo: PosRepository, initialTenant: Tenant? = null, initialRoute: String = "Sell", initialSession: StaffSession? = null, initialStaffAccess: StaffAccess? = null) {
    var tenant by remember { mutableStateOf<Tenant?>(initialTenant) }
    val access = remember { WorkspaceAccess() }
    val staffAccess = remember { initialStaffAccess ?: StaffAccess() }
    DisposableEffect(staffAccess) { onDispose { staffAccess.close() } }
    val practiceLauncher = remember { PracticeLauncher() }
    DisposableEffect(practiceLauncher) {
        onDispose { Thread({ practiceLauncher.close() }, "practice-cleanup").apply { isDaemon = true; start() } }
    }
    var session by remember { mutableStateOf<StaffSession?>(initialSession) }
    val automaticBackups=remember(repo) {pos.backup.AutoBackupCoordinator(repo,if(initialTenant!=null && initialSession==null) java.nio.file.Files.createTempDirectory("pos-practice-backups-") else java.nio.file.Path.of(System.getProperty("pos.dataDir",System.getProperty("user.home")+"/.nigerian-retail-pos")))}
    DisposableEffect(automaticBackups) {automaticBackups.startScheduler();onDispose {automaticBackups.close()}}
    DisposableEffect(session?.token) {automaticBackups.lock();onDispose {automaticBackups.lock()}}
    // Only the isolated preview may run without a credential-issued session.
    val isolatedPreview = initialTenant != null && initialSession == null
    repo.setMinimumPriceApprovalCheck { tenantId, grantId, request ->
        val active = requireNotNull(session) { "Sign in to use a manager approval." }
        require(active.tenantId == tenantId) { "Approval belongs to another shop." }
        staffAccess.requireMinimumPriceApproval(active, grantId, request)
    }
    repo.setCashHandoverAuthorizationCheck { tenantId, grantId, request ->
        val active = requireNotNull(session) { "Sign in to hand over cash." }
        require(active.tenantId == tenantId)
        staffAccess.requireCashHandover(active, grantId, request)
    }
    repo.setPermissionCheck { tenantId, permission ->
        if (!isolatedPreview) {
            val active = requireNotNull(session) { "Unlock this shop before making changes." }
            require(active.tenantId == tenantId) { "This session belongs to another shop." }
            staffAccess.requireSession(active, permission,touch=false)
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf(initialRoute) }
    var notice by remember { mutableStateOf<String?>(null) }
    val saleStates = rememberSaveableStateHolder()
    repo.setActorProvider { tenantId ->
        if(isolatedPreview) null else {
            val active=requireNotNull(session) { "Sign in before recording an activity." }
            require(active.tenantId==tenantId) { "This session belongs to another shop." }
            staffAccess.requireSession(active,StaffPermission.SELL,touch=false)
            AuditActor(tenantId,active.staffId,active.name,active.role)
        }
    }
    if (tenant == null) {
        WorkspacePicker(repo, access, staffAccess) { shop, active -> tenant = shop; session = active; route = "Sell"; revision++ }
        return
    }
    val current = tenant!!
    var disputeCustomerId by remember(current.id) { mutableStateOf<String?>(null) }
    var priceApprovalRequest by remember(current.id) { mutableStateOf<MinimumPriceApprovalRequest?>(null) }
    var priceApprovalResponse by remember(current.id) { mutableStateOf<((String) -> Unit)?>(null) }
    var dispatchOrderId by remember(current.id) { mutableStateOf<String?>(null) }
    fun allowed(permission: StaffPermission) = isolatedPreview || session?.role?.allows(permission)==true
    fun routeAllowed(destination: String): Boolean = when(destination) {
        "Sell" -> allowed(StaffPermission.SELL)
        "Sales" -> allowed(StaffPermission.VIEW_SALES)
        "Quotations" -> allowed(StaffPermission.VIEW_SALES)
        "Stock","Import","Stock counts","Stock removed","Bulk update","Import product photos","Price lists","Promotions","Repacking","Reorder suggestions","Variant matrix" -> allowed(StaffPermission.MANAGE_STOCK)
        "Purchase cost history" -> allowed(StaffPermission.MANAGE_STOCK) && allowed(StaffPermission.MANAGE_SUPPLIERS)
        "Bundles" -> allowed(StaffPermission.SELL)
        "Cash handover" -> allowed(StaffPermission.MANAGE_MONEY)
        "Stock value & costs" -> allowed(StaffPermission.MANAGE_STOCK) && allowed(StaffPermission.VIEW_REPORTS)
        "Landed costs" -> allowed(StaffPermission.MANAGE_STOCK) && allowed(StaffPermission.MANAGE_MONEY)
        "Customers","Credit review","Repayment follow-ups","Orders","Customer disputes","Order dispatch","Customer advances" -> allowed(StaffPermission.MANAGE_CUSTOMERS)
        "Money" -> allowed(StaffPermission.MANAGE_MONEY)
        "Reports","Audit" -> allowed(StaffPermission.VIEW_REPORTS)
        "Suppliers" -> allowed(StaffPermission.MANAGE_SUPPLIERS)
        "Backup" -> allowed(StaffPermission.BACKUP)
        "Staff","Owner recovery" -> allowed(StaffPermission.MANAGE_STAFF)
        "Shop settings","Minimum prices" -> allowed(StaffPermission.MANAGE_SETTINGS)
        else -> true
    }
    LaunchedEffect(session?.token) {
        val active=session ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if(runCatching { staffAccess.requireSession(active,StaffPermission.SELL,touch=false) }.isFailure) {
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
    DisposableEffect(session?.token) {
        val active = session
        if (active == null || isolatedPreview) {
            onDispose { }
        } else {
            val lastActivity = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
            val listener = java.awt.event.AWTEventListener { event ->
                val isActivity = event.id == java.awt.event.KeyEvent.KEY_PRESSED ||
                    event.id == java.awt.event.MouseEvent.MOUSE_PRESSED ||
                    event.id == java.awt.event.MouseEvent.MOUSE_WHEEL
                if (isActivity && isRetailWindowEvent(event) && session?.token == active.token) {
                    val now = System.nanoTime()
                    val previous = lastActivity.get()
                    if ((previous == Long.MIN_VALUE || now - previous >= 1_000_000_000L) &&
                        lastActivity.compareAndSet(previous, now)) {
                        try {
                            staffAccess.touchSession(active)
                        } catch (_: Exception) {
                            // A genuine user action must not revive expired or revoked access.
                            if (session?.token == active.token) {
                                staffAccess.logout(active)
                                session = null; tenant = null
                                saleStates.removeState(active.tenantId)
                            }
                        }
                    }
                }
            }
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            toolkit.addAWTEventListener(listener,
                java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            onDispose { toolkit.removeAWTEventListener(listener) }
        }
    }
    val products = remember(current.id, revision) { repo.products(current.id) }
    val customers = remember(current.id, revision) { if (allowed(StaffPermission.SELL_CREDIT) || allowed(StaffPermission.MANAGE_CUSTOMERS)) repo.customers(current.id) else emptyList() }
    priceApprovalRequest?.let { request ->
        session?.takeIf { it.tenantId == request.tenantId }?.let { requester ->
            MinimumPriceApprovalDialog(staffAccess, requester, request,
                onDismiss={priceApprovalRequest=null;priceApprovalResponse=null},
                onApproved={proof -> priceApprovalResponse?.invoke(proof.id);priceApprovalRequest=null;priceApprovalResponse=null})
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(208.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = PosPalette.Accent, shape = MaterialTheme.shapes.large) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { PosIcon(PosIcons.Store, tint = androidx.compose.ui.graphics.Color.White) }
                    }
                    Text("Retail POS", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                val logo=remember(current.id,revision) {runCatching {repo.imageSelection(current.id,"shop","shop").assetId?.let {repo.imageBytes(current.id,it)}}.getOrNull()}
                if(logo!=null) pos.ui.components.LocalImage(logo,"Shop logo",Modifier.padding(top=16.dp).size(64.dp))
                Text(current.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                Text(session?.let { "${it.name} · ${it.role.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() }}" } ?: "Isolated owner preview", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(20.dp))
                listOf("Sell", "Sales", "Stock", "Customers", "Money", "More").filter { routeAllowed(it) }.forEach { item ->
                    val selected = route == item || (item == "Customers" && route in setOf("Repayment follow-ups", "Customer disputes", "Customer advances")) || (item == "Stock" && route in setOf("Stock counts","Stock removed","Bulk update","Import product photos","Price lists","Repacking","Reorder suggestions","Purchase cost history","Landed costs","Promotions","Minimum prices","Variant matrix","Stock value & costs")) || (item == "More" && route in setOf("Reports","Suppliers","Backup","Import","Credit review","Staff","Audit","Help","Shop settings","Quotations","Orders","Order dispatch","Owner recovery","Bundles","Cash handover"))
                    Surface(onClick = {
                        try {
                            session?.let { staffAccess.requireSession(it,StaffPermission.SELL) }
                            route = item; notice = null
                        } catch(e:Exception) { lock() }
                    }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PosIcon(destinationIcon(item), tint = if (selected) PosPalette.Accent else PosPalette.Secondary)
                            Text(item, color = if (selected) PosPalette.Accent else PosPalette.Ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                }
                Text("Local workspace", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { lock() }) { PosIcon(PosIcons.LockKeyhole); Spacer(Modifier.width(8.dp)); Text("Lock / switch shop") }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if(route=="Audit") "Activity history" else route, style = MaterialTheme.typography.headlineSmall)
                    if (route in setOf("Repayment follow-ups", "Customer disputes", "Customer advances")) TextButton({route="Customers"}) {Text("Back to customers")}
                    else if(route in setOf("Stock counts","Stock removed","Bulk update","Import product photos","Price lists","Repacking","Reorder suggestions","Purchase cost history","Landed costs","Promotions","Minimum prices","Variant matrix","Stock value & costs")) TextButton({route="Stock"}) {Text("Back to stock")}
                    else if (route == "Order dispatch") TextButton({route="Orders"}) {Text("Back to orders")}
                    else if (route in setOf("Reports","Suppliers","Backup","Import","Credit review","Staff","Audit","Help","Shop settings","Quotations","Orders","Order dispatch","Owner recovery","Bundles","Cash handover")) TextButton({ route = if(route=="Import") "Stock" else "More" }) { Text(if(route=="Import") "Back to stock" else "Back to shop tools") }
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
                        "Sell" -> saleStates.SaveableStateProvider(current.id) { SellScreen(current.id, products, customers, remember(current.id, revision) { repo.sellingUnits(current.id) }, allowCredit = allowed(StaffPermission.SELL_CREDIT), allowDiscounts = allowed(StaffPermission.DISCOUNT),repository=repo,imageRevision=revision,
                            onRequestApproval=if(session!=null) ({ lines, operation, approved ->
                                try { priceApprovalRequest=repo.minimumPriceApprovalRequest(current.id,operation,lines);priceApprovalResponse=approved }
                                catch(e:Exception) {notice=e.message ?: "Price approval could not be requested."}
                            }) else null) { lines, cash, customer, operationId, dueDate, basketDiscount, externalPayments, cashGiven, note, priceApproval ->
                            try { repo.checkout(current.id, lines, cash, customer, operationId,dueDate,basketDiscount,externalPayments,cashGiven,note,priceApproval); revision++; null } catch (e: Exception) { e.message ?: "Sale not saved" }
                        } }
                        "Stock" -> StockScreen(repo, current.id, onChanged={ revision++ }, onImport={ route="Import" }, onCounts={route="Stock counts"}, onDispositions={route="Stock removed"}, onBulkUpdate={route="Bulk update"}, onPriceLists={route="Price lists"}, onMinimumPrices=if(allowed(StaffPermission.MANAGE_SETTINGS)) ({route="Minimum prices"}) else null, onRepacking={route="Repacking"}, onReorder={route="Reorder suggestions"}, onPurchaseCosts=if(allowed(StaffPermission.MANAGE_SUPPLIERS)) ({route="Purchase cost history"}) else null, onPhotoImport={route="Import product photos"}, onPromotions={route="Promotions"}, onLandedCosts=if(allowed(StaffPermission.MANAGE_MONEY)) ({route="Landed costs"}) else null, onVariants={route="Variant matrix"},onCosting=if(allowed(StaffPermission.VIEW_REPORTS)) ({route="Stock value & costs"}) else null)
                        "Reorder suggestions" -> ReorderSuggestionsScreen(repo,current.id)
                        "Purchase cost history" -> PurchaseCostHistoryScreen(repo,current.id)
                        "Landed costs" -> LandedCostsScreen(repo,current.id)
                        "Promotions" -> PromotionsScreen(repo,current.id) { revision++ }
                        "Import product photos" -> BulkProductPhotosScreen(repo,current.id) { revision++ }
                        "Stock counts" -> StockCountSessionsScreen(repo,current.id) {revision++}
                        "Stock removed" -> InventoryDispositionsScreen(repo,current.id) {revision++}
                        "Bulk update" -> BulkCatalogueUpdateScreen(repo,current.id) {revision++}
                        "Price lists" -> PriceListsScreen(repo,current.id) {revision++}
                        "Minimum prices" -> MinimumPricesScreen(repo,current.id) {revision++}
                        "Repacking" -> RepackingScreen(repo,current.id) {revision++}
                        "Customers" -> CustomerWorkspace(repo,current.id,onFollowUps={route="Repayment follow-ups"},onDisputes={disputeCustomerId=it;route="Customer disputes"},onAdvances={disputeCustomerId=it;route="Customer advances"}) { revision++ }
                        "Repayment follow-ups" -> RepaymentRemindersScreen(repo,current.id)
                        "Sales" -> SalesScreen(repo, current.id, allowReturns = allowed(StaffPermission.RETURN_SALE)) { revision++ }
                        "Credit review" -> CreditReviewScreen(repo,current.id)
                        "Reports" -> ReportsScreen(repo, current.id)
                        "Audit" -> AuditScreen(repo,current.id,staffAccess,session)
                        "Orders" -> CustomerOrdersScreen(repo,current.id,onDispatch={dispatchOrderId=it;route="Order dispatch"},onChanged={ revision++ })
                        "Bundles" -> BundlesScreen(repo,current.id,canManage=allowed(StaffPermission.MANAGE_STOCK)) {revision++}
                        "Cash handover" -> session?.let { CashHandoverScreen(repo,current.id,staffAccess,it) {revision++} } ?: Text("Sign in to hand over the drawer.")
                        "Stock value & costs" -> CostingScreen(repo,current.id) {revision++}
                        "Variant matrix" -> VariantMatrixScreen(repo,current.id) {revision++}
                        "Owner recovery" -> session?.let { OwnerRecoveryScreen(staffAccess,it) } ?: Text("Sign in as the owner to set up recovery.")
                        "Customer advances" -> CustomerAdvancesScreen(repo,current.id,customerId=disputeCustomerId) {revision++}
                        "Customer disputes" -> CustomerDisputesScreen(repo,current.id,customerId=disputeCustomerId) { revision++ }
                        "Order dispatch" -> OrderDispatchScreen(repo,current.id,onChanged={revision++},orderId=dispatchOrderId)
                        "Quotations" -> QuotationsScreen(repo,current.id,onChanged={revision++},canManage=allowed(StaffPermission.DISCOUNT))
                        "Suppliers" -> SuppliersScreen(repo, current.id) { revision++ }
                        "Backup" -> BackupPanel(repo, current,automaticBackups)
                        "Staff" -> session?.let { StaffScreen(staffAccess,it) { revision++ } } ?: Text("Staff management requires an owner sign-in.")
                        "Shop settings" -> ShopSettingsScreen(repo,current) {revision++}
                        "Import" -> ProductImportScreen(repo, current.id) { revision++ }
                        "Money" -> MoneyScreen(repo, current.id) { revision++ }
                        "Help" -> HelpScreen(practiceLauncher, isPractice = isolatedPreview || System.getenv("POS_PRACTICE_CHILD") == "1")
                        else -> ShopToolsScreen(::allowed) { route = it }

                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            PosIcon(PosIcons.Monitor, Modifier.padding(end = 8.dp)); Text("On this computer · Online services not connected", style = MaterialTheme.typography.bodySmall)
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
    var recovering by remember { mutableStateOf(false) }
    var staffId by remember { mutableStateOf<String?>(null) }
    var usePin by remember { mutableStateOf(false) }
    var configuringPin by remember { mutableStateOf(false) }
    var pinNotice by remember { mutableStateOf<String?>(null) }
    val signInMembers = remember(selected?.id) { selected?.let { runCatching { staffAccess.signInMembers(it.id) }.getOrDefault(emptyList()) }.orEmpty() }
    LaunchedEffect(selected?.id, staffId) { usePin = false; configuringPin = false; password = ""; pinNotice = null }
    if (configuringPin && selected != null && staffId != null) {
        PinSetupPanel(staffAccess, selected!!.id, staffId!!, onDismiss = { configuringPin = false }, onSaved = {
            configuringPin = false; password = ""
            error = null; pinNotice = "PIN updated on this computer. Use your PIN or full password to sign in."
        })
    }
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
    if (recovering && selected != null) OwnerRecoveryDialog(staffAccess, selected!!.id,
        onDismiss={recovering=false}, onRecovered={recovering=false;password="";pinNotice="Owner access recovered. Sign in with your new password."})
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(64.dp), horizontalArrangement=Arrangement.spacedBy(64.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Surface(color = PosPalette.AccentTint, shape = MaterialTheme.shapes.large) { Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { PosIcon(PosIcons.Store, Modifier.size(36.dp), PosPalette.Accent) } }
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
            if (staffId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!usePin, { usePin = false; password = ""; error = null }, label = { Text("Password") })
                    FilterChip(usePin, { usePin = true; password = ""; error = null }, label = { Text("PIN") })
                }
                if (usePin) Text("PIN works on this computer for up to 12 hours after full-password sign-in. Use your password if PIN access has expired.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(password,{password=it},label={Text(if(staffId==null) "Owner password" else if(usePin) "Staff PIN" else "Staff password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if (staffId != null) TextButton({ configuringPin = true }) { Text("Set up / remove staff PIN") }
            pinNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Button(shape=MaterialTheme.shapes.small,onClick={ try {
                if(selected==null) {
                    require(name.isNotBlank()) { "Enter your shop name" }; require(password.length>=8) { "Use at least 8 characters" }
                    val t=repo.createTenant(name.trim()) { access.register(it.id,password) }; tenants=repo.tenants(); onEnter(t,requireNotNull(staffAccess.unlockOwner(t.id,password)))
                } else {
                    val active = if(staffId==null) staffAccess.unlockOwner(selected!!.id,password) else if(usePin) staffAccess.unlockStaffPin(selected!!.id,staffId!!,password) else staffAccess.unlockStaff(selected!!.id,staffId!!,password)
                    require(active!=null) { "Account or credential not recognized, or sign-in is paused after five failed attempts. Wait 60 seconds, then try again. Use your full password if PIN access has expired." }
                    onEnter(selected!!,active);password=""
                }
            } catch(e:Exception) {error=e.message ?: "Could not open shop"}},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(selected==null) "Create shop workspace" else "Unlock shop") }
            if(selected!=null && staffId==null) TextButton({recovering=true}) {Text("Recover owner access")}
            if(selected!=null) TextButton(shape=MaterialTheme.shapes.small,onClick={selected=null;staffId=null;password=""}) { Text("Create another shop") }
            TextButton({ restoring=true }) { Text("Restore an encrypted backup") }
            Text("No online account is created. Use a trusted computer; local database encryption is not yet enabled.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}

/** Inspect event identity/window ownership only; never read or retain entered keys or text. */
private fun isRetailWindowEvent(event: java.awt.AWTEvent): Boolean {
    val component = event.source as? java.awt.Component ?: return false
    val window = (component as? java.awt.Window) ?: javax.swing.SwingUtilities.getWindowAncestor(component) ?: return false
    fun isRetailFrame(candidate: java.awt.Window) = candidate is java.awt.Frame && candidate.title.startsWith("Retail POS")
    var ancestor: java.awt.Window? = window
    while (ancestor != null) {
        if (isRetailFrame(ancestor)) return true
        ancestor = ancestor.owner
    }
    // System print/file dialogs opened by Swing with a null parent use its shared owner
    // frame. They belong to this JVM too, and must count as activity during a long export.
    return window is java.awt.Dialog && window.isModal &&
        window.owner?.javaClass?.name == "javax.swing.SwingUtilities\$SharedOwnerFrame" &&
        java.awt.Window.getWindows().any { it.isDisplayable && isRetailFrame(it) }
}
