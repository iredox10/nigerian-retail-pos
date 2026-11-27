package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.security.StaffAccess
import pos.security.WorkspaceAccess
import pos.ui.components.*
import java.util.UUID

private data class AndroidTool(val name: String, val description: String, val permission: StaffPermission)

/** Advanced tools remain contextual and explicitly distinguish reading from Android editing. */
@Composable
fun AndroidMoreScreen(repo: PosRepository, tenantId: String, access: StaffAccess, session: StaffSession, onChanged: () -> Unit, onCloud: () -> Unit = {}) = key(tenantId, session.token) {
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    val tools = listOf(
        AndroidTool("Connected devices", "Use your shop on a phone or computer", StaffPermission.BACKUP),
        AndroidTool("CSV import", "Review new goods or update the existing catalogue", StaffPermission.MANAGE_STOCK),
        AndroidTool("CSV exports", "Review and save local records as a spreadsheet file", StaffPermission.VIEW_REPORTS),
        AndroidTool("Reports", "Recorded sales, customer debt and actual drawer cash", StaffPermission.VIEW_REPORTS),
        AndroidTool("Stock counts", "Save and review multi-item physical counts", StaffPermission.MANAGE_STOCK),
        AndroidTool("Supplier credit notes", "Record and reconcile supplier-issued credit", StaffPermission.MANAGE_SUPPLIERS),
        AndroidTool("Purchase orders", "Draft orders, approve terms and record actual deliveries", StaffPermission.MANAGE_SUPPLIERS),
        AndroidTool("Suppliers", "Supplier profiles, buying receipt debt and cash payments", StaffPermission.MANAGE_SUPPLIERS),
        AndroidTool("Orders", "Agreed goods, deposits, stock holds and collection", StaffPermission.MANAGE_CUSTOMERS),
        AndroidTool("Quotations", "Prepare agreed prices and create customer orders", StaffPermission.VIEW_SALES),
        AndroidTool("Customer advances", "Receive, apply or refund customer-owned cash", StaffPermission.MANAGE_CUSTOMERS),
        AndroidTool("Customer disputes", "Review receipt disputes and record outcomes", StaffPermission.MANAGE_CUSTOMERS),
        AndroidTool("Reorder suggestions", "Review stock needs from saved local records", StaffPermission.MANAGE_STOCK),
        AndroidTool("Stock value & costs", "Read FIFO sources and incomplete cost coverage", StaffPermission.MANAGE_STOCK),
        AndroidTool("Bundles", "Review and sell saved groups of stock items", StaffPermission.SELL),
        AndroidTool("Variant matrices", "Read independently stocked option creation history", StaffPermission.MANAGE_STOCK),
        AndroidTool("Activity history", "Who recorded each local business change", StaffPermission.VIEW_REPORTS),
        AndroidTool("Shop & receipt details", "Shop identity and receipt wording", StaffPermission.MANAGE_SETTINGS),
        AndroidTool("Encrypted backup", "Save and restore local shop records", StaffPermission.BACKUP),
        AndroidTool("Owner recovery", "Enroll a single-use code for owner access", StaffPermission.MANAGE_STAFF),
        AndroidTool("Cash handover", "Count and pass the shared drawer with recipient consent", StaffPermission.MANAGE_MONEY),
        AndroidTool("Staff", "Owner-controlled local staff access", StaffPermission.MANAGE_STAFF),
    ).filter { session.role.allows(it.permission) && (it.name != "Stock value & costs" || session.role.allows(StaffPermission.VIEW_REPORTS)) }
    val selected = tools.singleOrNull { it.name == tool }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected != null) {
            TextButton({ tool = null }, Modifier.heightIn(min = 48.dp)) { Text("Back to shop tools") }
            when (selected.name) {
                "Owner recovery" -> if(session.role==StaffRole.OWNER) AndroidOwnerRecoveryScreen(access,session) else Text("Owner sign-in is required.")
                "Cash handover" -> AndroidHandoverScreen(repo,tenantId,access,session,onChanged)
                "Orders" -> AndroidOrdersScreen(repo,tenantId,onChanged)
                "Quotations" -> AndroidQuotationsScreen(repo,tenantId,onChanged,canManage=session.role.allows(StaffPermission.DISCOUNT))
                "CSV import" -> AndroidCsvImportScreen(repo,tenantId,onChanged)
                "CSV exports" -> AndroidCsvExportScreen(repo,tenantId)
                "Reports" -> AndroidLocalReports(repo, tenantId)
                "Stock counts" -> AndroidStockCountsScreen(repo,tenantId,onChanged)
                "Supplier credit notes" -> AndroidSupplierCreditsScreen(repo,tenantId,onChanged)
                "Purchase orders" -> AndroidPurchaseOrdersScreen(repo,tenantId,onChanged)
                "Reorder suggestions" -> AndroidReplenishmentScreen(repo,tenantId,onChanged)
                "Suppliers" -> AndroidSupplierTools(repo, tenantId, onChanged)
                "Staff" -> AndroidStaffTools(access, session, onChanged)
                "Customer advances" -> AndroidCustomerAdvancesScreen(repo, tenantId, onChanged)
                "Customer disputes" -> AndroidCustomerDisputesScreen(repo, tenantId, onChanged)
                "Bundles" -> AndroidBundlesScreen(repo, tenantId, onChanged)
                "Shop & receipt details" -> AndroidShopSettingsScreen(repo, tenantId, onChanged)
                "Encrypted backup" -> AndroidBackupScreen(repo, tenantId, WorkspaceAccess(), { onChanged() })
                else -> AndroidReadOnlyTools(repo, tenantId, selected.name)
            }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Your shop tools", "Purchasing, records and workspace settings.") }
            items(tools, key = { it.name }) { entry ->
                Surface(onClick = { if(entry.name=="Connected devices")onCloud() else tool = entry.name }, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                        Text("Open ${entry.name.lowercase()}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { InlineNotice("Android feature coverage", "Some advanced tools currently show saved records only. Their screens say when Android editing is unavailable. Cloud access is configured separately by the owner under Connected devices. Payment verification is separate from synchronization.", NoticeTone.Info) }
        }
    }
}

@Composable
private fun AndroidLocalReports(repo:PosRepository,tenantId:String) = AndroidReportsScreen(repo,tenantId)

private data class AndroidSupplierData(val profiles: List<SupplierProfile>, val receipts: List<Pair<StockReceipt, Long>>, val credits: List<SupplierCreditBalance>)

@Composable
private fun AndroidSupplierTools(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf(false) }
    var archived by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<SupplierProfile?>(null)}
    var history by remember {mutableStateOf<SupplierProfile?>(null)}
    var statement by remember {mutableStateOf<SupplierProfile?>(null)}
    var paying by remember { mutableStateOf<Pair<StockReceipt, Long>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) {
        AndroidSupplierData(repo.supplierProfiles(tenantId,true), repo.stockReceipts(tenantId).map { it to repo.supplierOutstanding(tenantId, it.id) }, repo.supplierCreditBalances(tenantId))
    }
    fun changed(message: String) { revision++; notice = message; onChanged() }
    if (add) AddAndroidSupplier(repo, tenantId, { add = false }) { add = false; changed("Supplier saved on this device") }
    editing?.let {profile ->AndroidSupplierProfileEditor(repo,tenantId,profile,{editing=null}) {editing=null;changed("Supplier changes saved with history")}}
    history?.let {profile ->AndroidSupplierProfileHistory(repo,tenantId,profile,{history=null})}
    statement?.let {profile ->AndroidSupplierStatement(repo,tenantId,profile,{statement=null})}
    paying?.let { (receipt, owed) -> AndroidSupplierPayment(repo, tenantId, receipt, owed, { paying = null }) { paying = null; changed("Supplier cash payment saved") } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Suppliers", "Profiles, purchase balances and cash payments.") }
        item { PosButton("Add supplier", { add = true }, Modifier.fillMaxWidth()) }
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!archived,{archived=false},label={Text("Active suppliers")})
            FilterChip(archived,{archived=true},label={Text("Archived suppliers")})
        }}
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { OperationsLoadState(loaded, "suppliers", { revision++ }) }
        loaded?.getOrNull()?.let { data ->
            if (data.profiles.isEmpty()) item { EmptyState("No supplier profiles yet", "Add a supplier to keep a stable account identity.") }
            items(data.profiles.filter {it.archived==archived}, key = { it.id }) { supplier -> OperationsCard {
                Text(supplier.details.name, style = MaterialTheme.typography.titleMedium)
                supplier.details.phone?.let { Text(it) }
                supplier.details.contactPerson?.let { Text("Contact · $it") }
                supplier.details.paymentTerms?.let { Text("Terms · $it") }
                Text("Profile revision ${supplier.version}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={editing=supplier},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Manage supplier")}
                TextButton(onClick={history=supplier}) {Text("View profile history")}
                OutlinedButton(onClick={statement=supplier},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Dated supplier statement")}
            } }
            item { Text("Buying receipts", style = MaterialTheme.typography.titleMedium) }
            if (data.receipts.isEmpty()) item { Text("No buying receipts recorded") }
            items(data.receipts, key = { it.first.id }) { (receipt, owed) -> OperationsCard {
                Text(receipt.productName, style = MaterialTheme.typography.titleMedium)
                Text("${receipt.supplierName.ifBlank { "Cash purchase" }} · ${receipt.createdAt.take(10)}")
                LabelValueRow("Original buying total", money(receipt.totalCostMinor))
                LabelValueRow("Still owed on this receipt", money(owed), emphasized = true)
                if (owed > 0) PosButton("Record supplier payment", { paying = receipt to owed }, Modifier.fillMaxWidth())
            } }
            item { Text("Saved supplier credits", style = MaterialTheme.typography.titleMedium) }
            if (data.credits.isEmpty()) item { Text("No supplier credit notes recorded") }
            items(data.credits, key = { it.note.id }) { credit -> OperationsCard {
                Text(credit.note.reference, style = MaterialTheme.typography.titleSmall)
                LabelValueRow("Original note", money(credit.note.amountMinor))
                LabelValueRow("Available credit", money(credit.availableMinor))
                if (credit.voided) Text("Unused note voided · history retained")
            } }
        }
        item { Text("Purchase orders and supplier credit notes have their own tools. Use them to review deliveries and apply or reverse supplier credits. Open a supplier profile’s dated statement to review and export its linked history.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AddAndroidSupplier(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var terms by rememberSaveable { mutableStateOf("") }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog("Add supplier", dismiss, "Save supplier", action = {
        val normalized = phone.trim().ifBlank { null }?.let { requireNotNull(normalizeNigerianPhone(it)) { "Use a valid Nigerian phone number." } }
        withContext(Dispatchers.IO) { repo.addSupplierProfile(tenantId, SupplierDetails(name.trim(), normalized, contact.trim().ifBlank { null }, paymentTerms = terms.trim().ifBlank { null }), operation) }
        saved()
    }) {
        PosTextField(name, { name = it }, "Supplier name")
        PosTextField(phone, { phone = it }, "Phone number (optional)")
        PosTextField(contact, { contact = it }, "Contact person (optional)")
        PosTextField(terms, { terms = it }, "Payment terms (optional)")
    }
}

@Composable
private fun AndroidSupplierPayment(repo: PosRepository, tenantId: String, receipt: StockReceipt, owed: Long, dismiss: () -> Unit, saved: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog("Pay ${receipt.supplierName}", dismiss, if (reviewed) "Save cash payment" else "Review payment", action = {
        val value = requireNotNull(parseNaira(amount)) { "Enter the actual cash paid." }
        require(value > 0 && value <= owed) { "Pay a positive amount up to ${money(owed)}." }
        if (!reviewed) reviewed = true
        else {
            require(paid) { "Confirm you actually paid this cash." }
            withContext(Dispatchers.IO) {
                synchronized(repo) {
                    if (repo.supplierPayments(tenantId, receipt.id).none { it.id == operation }) require(repo.supplierOutstanding(tenantId, receipt.id) == owed) { "The supplier balance changed. Reopen and review this payment." }
                    repo.paySupplierReceipt(tenantId, receipt.id, value, operation)
                }
            }
            saved()
        }
    }) {
        Text("Buying receipt ${receipt.id.takeLast(8)} · ${receipt.productName}")
        LabelValueRow("Currently owed", money(owed))
        PosTextField(amount, { amount = it; reviewed = false; paid = false }, "Cash paid to supplier (₦)")
        if (reviewed) {
            LabelValueRow("Cash leaving drawer", money(requireNotNull(parseNaira(amount))))
            LabelValueRow("Remaining receipt debt", money(owed - requireNotNull(parseNaira(amount))))
            OperationsCheck(paid, { paid = it }, "I counted and paid this cash to the supplier.")
        }
    }
}

private data class AndroidReadRow(val id: String, val heading: String, val lines: List<Pair<String, String>>, val detail: String? = null)

@Composable
private fun AndroidReadOnlyTools(repo: PosRepository, tenantId: String, tool: String) = key(tool) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded = operationsLoad(repo, tenantId, revision) { readAndroidTool(repo, tenantId, tool) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader(tool, "Saved records on this device") }
        item { InlineNotice("Read-only on Android", "You can inspect these saved records. Creating or editing this module is not available in Android yet.", NoticeTone.Info) }
        item { TextButton({ revision++ }) { Text("Refresh records") } }
        item { OperationsLoadState(loaded, tool.lowercase(), { revision++ }) }
        val rows = loaded?.getOrNull().orEmpty()
        if (loaded?.isSuccess == true && rows.isEmpty()) item { EmptyState("No saved records", "This module has no recorded activity in this shop.") }
        items(rows, key = { it.id }) { row -> OperationsCard {
            Text(row.heading, style = MaterialTheme.typography.titleMedium)
            row.lines.forEach { (label, value) -> LabelValueRow(label, value) }
            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
    }
}

private fun readAndroidTool(repo: PosRepository, tenantId: String, tool: String): List<AndroidReadRow> = when (tool) {
    "Orders" -> repo.customerOrders(tenantId).map { state -> AndroidReadRow(state.order.id, state.order.customerName,
        listOf("Agreed order total" to money(state.order.totalMinor), "Deposit remaining" to money(state.depositLeftMinor), "Still to pay" to money(state.balanceMinor), "Status" to if (state.cancelled) "Cancelled" else if (state.completed) "Collected" else "Open"),
        state.order.lines.joinToString { "${it.productName} · ${it.quantityLabel} ${it.sellingUnit}" }) }
    "Quotations" -> repo.quotations(tenantId).map { quote -> AndroidReadRow(quote.id, "Quotation ${quote.id.takeLast(8)}",
        listOf("Agreed total" to money(quote.totalMinor), "Status" to quote.status.name.lowercase().replaceFirstChar { it.uppercase() }, "Revision" to quote.version.toString(), "Expiry · WAT" to quote.expiresOn), quote.notes.ifBlank { null }) }
    "Customer advances" -> {
        val names = repo.customers(tenantId).associate { it.id to it.name }
        repo.customerAdvances(tenantId).map { event -> AndroidReadRow(event.id, names[event.customerId] ?: "Customer ${event.customerId.takeLast(8)}",
            listOf("Action" to event.kind, "Amount" to money(event.amountMinor), "Held after event" to money(event.afterMinor)), event.reason) }
    }
    "Customer disputes" -> {
        val names = repo.customers(tenantId).associate { it.id to it.name }
        repo.customerDisputes(tenantId).map { dispute -> AndroidReadRow(dispute.opened.disputeId, names[dispute.opened.customerId] ?: "Customer",
            listOf("Claim when opened" to money(dispute.opened.amountMinor), "Status" to if (dispute.open) "Open" else "Resolved", "Receipt" to dispute.opened.saleId.takeLast(8)), dispute.resolution?.reason ?: dispute.opened.reason) }
    }
    "Reorder suggestions" -> repo.reorderSuggestions(tenantId, 7).rows.map { row -> AndroidReadRow(row.product.id, row.product.name,
        listOf("On hand" to "${row.product.stockLabel} ${row.product.unit}", "Reserved" to "${quantityLabel(row.reserved, row.product.quantityPrecision)} ${row.product.unit}", "Suggested to buy" to "${quantityLabel(row.shortage, row.product.quantityPrecision)} ${row.product.unit}"),
        "Suggestions use saved lead times, safety stock and buying multiples, recent retained sales and approved incoming purchases. This is not a purchase order.") }
    "Stock value & costs" -> {
        val report = repo.inventoryCosting(tenantId)
        listOf(AndroidReadRow("coverage", "FIFO purchase-cost coverage", listOf("Stock value" to (report.stockValueMinor?.let(::money) ?: "Unavailable · incomplete coverage"), "Net sales COGS" to (report.netSalesCogsMinor?.let(::money) ?: "Unavailable · incomplete coverage")), "Purchase basis excludes separately recorded delivery allocations. This report does not claim complete profit.")) +
            report.items.map { row -> AndroidReadRow(row.product.id, row.product.name, listOf("Stock value" to (row.stockValueMinor?.let(::money) ?: "Unavailable"), "Net sales COGS" to (row.netSalesCogsMinor?.let(::money) ?: "Unavailable")), row.gaps.joinToString("\n").ifBlank { "Cost sources present under the saved FIFO purchase-basis policy." }) }
    }
    "Bundles" -> repo.bundleDefinitions(tenantId).map { bundle -> AndroidReadRow(bundle.id, bundle.terms.name,
        listOf("Revision" to bundle.version.toString(), "Status" to if (bundle.terms.active) "Active" else "Inactive", "Price" to (bundle.terms.fixedPriceMinor?.let(::money) ?: "Component prices at review")), bundle.terms.components.joinToString { "${it.productName} · ${quantityLabel(it.quantity.toLong(), it.quantityPrecision)} ${it.sellingUnit}" }) }
    "Variant matrices" -> repo.variantMatrixBatches(tenantId).map { matrix -> AndroidReadRow(matrix.id, matrix.definition.family,
        listOf("Independently stocked items" to matrix.rows.size.toString(), "First option" to matrix.definition.first.name, "Second option" to (matrix.definition.second?.name ?: "None")), matrix.reason) }
    "Activity history" -> repo.auditEvents(tenantId).map { event -> AndroidReadRow(event.id, event.kind.replace('.', ' ').replace('_', ' '), listOf("Recorded by" to (event.actor?.name ?: "Not recorded"), "Recorded UTC" to event.createdAt), event.description) }
    else -> emptyList()
}

@Composable
private fun AndroidStaffTools(access: StaffAccess, session: StaffSession, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<StaffMember?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loaded by remember(session.token, revision) { mutableStateOf<Result<List<StaffMember>>?>(null) }
    LaunchedEffect(session.token, revision) { loaded = withContext(Dispatchers.IO) { runCatching { access.members(session) } } }
    if (adding) AndroidStaffPasswordDialog(access, session, null, { adding = false }) { adding = false; revision++; notice = "Staff account saved"; onChanged() }
    resetting?.let { member -> AndroidStaffPasswordDialog(access, session, member, { resetting = null }) { resetting = null; revision++; notice = "Password reset; prior access revoked"; onChanged() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Staff", "Local accounts for this shop on this device") }
        item { PosButton("Add staff account", { adding = true }, Modifier.fillMaxWidth()) }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { OperationsLoadState(loaded, "staff", { revision++ }) }
        if (loaded?.isSuccess == true && loaded?.getOrNull().isNullOrEmpty()) item { EmptyState("No staff accounts yet", "The owner account remains separate.") }
        items(loaded?.getOrNull().orEmpty(), key = { it.id }) { member -> OperationsCard {
            Text(member.name, style = MaterialTheme.typography.titleMedium)
            Text("${member.role.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (member.active) "Active" else "Inactive"}")
            OutlinedButton({ resetting = member }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reset password") }
        } }
        item { Text("Owner recovery enrollment, staff role/active changes, PIN setup and full security history editing are not available in this Android screen yet.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AndroidStaffPasswordDialog(access: StaffAccess, session: StaffSession, member: StaffMember?, dismiss: () -> Unit, saved: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(StaffRole.CASHIER) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog(if (member == null) "Add staff account" else "Reset ${member.name}'s password", dismiss, if (member == null) "Save staff account" else "Reset password", action = {
        require(password == confirmation) { "The passwords do not match." }
        withContext(Dispatchers.IO) {
            if (member == null) access.addMember(session, name, role, password, operation)
            else access.resetPassword(session, member.id, password)
        }
        password = ""; confirmation = ""; saved()
    }) {
        if (member == null) {
            PosTextField(name, { name = it }, "Staff name")
            Text("Role", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(role == StaffRole.CASHIER, { role = StaffRole.CASHIER }, label = { Text("Cashier") })
                FilterChip(role == StaffRole.MANAGER, { role = StaffRole.MANAGER }, label = { Text("Manager") })
            }
        } else Text("Previous password/PIN sessions are invalidated. Give the new password directly to this person.")
        OutlinedTextField(password, { password = it }, label = { Text("New full password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
