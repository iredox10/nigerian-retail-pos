package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

/** Phone/tablet operations use the same durable, tenant-scoped transaction boundary as desktop. */
@Composable
fun AndroidStockScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var managing by remember { mutableStateOf<Product?>(null) }
    var add by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf<Product?>(null) }
    var count by remember { mutableStateOf<Product?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) { repo.products(tenantId, true) }
    fun changed(message: String) { revision++; notice = message; onChanged() }
    managing?.let { product -> AndroidCatalogueTools(repo, tenantId, product.id, { managing = null }) { changed("Catalogue saved on this device") } }
    if (add) AddAndroidItem(repo, tenantId, { add = false }) { add = false; changed("Item saved on this device") }
    receive?.let { product -> ReceiveAndroidStock(repo, tenantId, product, { receive = null }) { receive = null; changed("Stock received on this device") } }
    count?.let { product -> CountAndroidStock(repo, tenantId, product, { count = null }) { count = null; changed("Stock count saved with its correction history") } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Stock", "Know what is available and record what came in.") }
        item { PosButton("Add item", { add = true }, modifier = Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!archived, { archived = false }, label = { Text("Active items") })
            FilterChip(archived, { archived = true }, label = { Text("Archived items") })
        } }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { PosTextField(search, { search = it }, "Search for an item", hint = "Name, SKU, category or barcode") }
        item { OperationsLoadState(loaded, "stock", { revision++ }) }
        val products = loaded?.getOrNull().orEmpty().filter { it.archived == archived && it.matchesCatalogueSearch(search) }
        if (loaded?.isSuccess == true && products.isEmpty()) item { EmptyState("No matching items", if (archived) "Archived goods stay recorded here. Use Manage item to restore them." else "Add an item or change your search.") }
        items(products, key = { it.id }) { product ->
            OperationsCard {
                AndroidProductPicture(repo, tenantId, product.id, revision)
                Text(product.name, style = MaterialTheme.typography.titleMedium)
                if (!product.archived) AndroidProductImageEditor(repo, tenantId, product.id) { changed("Product photo saved") }
                LabelValueRow("On hand", "${product.stockLabel} ${product.unit}")
                LabelValueRow("Selling price", "${money(product.priceMinor)} / ${product.unit}")
                product.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall) }
                product.sku?.let { Text("SKU $it", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton({ managing = product }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage item") }
                if (!product.archived) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ receive = product }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Add stock") }
                    OutlinedButton({ count = product }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Count stock") }
                }
            }
        }
    }
}

@Composable
private fun AddAndroidItem(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("piece") }
    var price by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("0") }
    var precision by rememberSaveable { mutableStateOf("0") }
    var precisionMenu by remember { mutableStateOf(false) }
    var barcode by rememberSaveable { mutableStateOf("") }
    OperationsDialog("Add item", dismiss, "Save item", action = {
        val places = requireNotNull(precision.toIntOrNull()?.takeIf { it in 0..6 }) { "Choose how this item is counted from the quantity selector." }
        val quantity = requireNotNull(parseQuantity(opening, places)) { "Enter the opening count in the displayed precision." }
        val amount = requireNotNull(parseNaira(price)) { "Enter an exact selling price." }
        withContext(Dispatchers.IO) { repo.addProduct(tenantId, name, unit, amount, projectedQuantity(quantity.scaledUnits, places), barcode.trim().ifBlank { null }, places) }
        saved()
    }) {
        Text("Each item has its own stock unit. Enter the opening quantity you actually counted.")
        PosTextField(name, { name = it }, "Item name")
        PosTextField(unit, { unit = it }, "Stock and selling unit", hint = "piece, kg, metre…")
        Text("How is this item counted?", style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton({ precisionMenu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(quantityPrecisionChoiceLabel(precision.toIntOrNull()))
            }
            DropdownMenu(precisionMenu, { precisionMenu = false }, Modifier.heightIn(max = 360.dp)) {
                (0..6).forEach { places ->
                    DropdownMenuItem(text = { Text(quantityPrecisionChoiceLabel(places)) }, onClick = {
                        precision = places.toString(); precisionMenu = false
                    })
                }
            }
        }
        Text("Choose whole quantities for pieces or full bags. For measured goods, 3 decimal places allows 1.250 kg. This choice is fixed after saving.", style = MaterialTheme.typography.bodySmall)
        PosTextField(price, { price = it }, "Selling price per unit (₦)")
        PosTextField(opening, { opening = it }, "Counted opening quantity")
        PosTextField(barcode, { barcode = it }, "Barcode (optional)")
    }
}

internal fun quantityPrecisionChoiceLabel(places: Int?): String = when (places) {
    0 -> "Whole quantities (0 decimal places)"
    1 -> "1 decimal place · e.g. 1.5"
    2 -> "2 decimal places · e.g. 1.25"
    3 -> "3 decimal places · e.g. 1.250"
    4 -> "4 decimal places · e.g. 1.2500"
    5 -> "5 decimal places · e.g. 1.25000"
    6 -> "6 decimal places · e.g. 1.250000"
    else -> "Choose how quantities are counted"
}

private data class AndroidReceivingOptions(val units:List<PurchaseUnit>,val suppliers:List<SupplierProfile>)

@Composable
private fun ReceiveAndroidStock(repo:PosRepository,tenantId:String,product:Product,dismiss:()->Unit,saved:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val loaded=operationsLoad(repo,tenantId,revision) {AndroidReceivingOptions(repo.purchaseUnits(tenantId,product.id),repo.supplierProfiles(tenantId))}
    var unitId by rememberSaveable {mutableStateOf<String?>(null)}
    var supplierId by rememberSaveable {mutableStateOf<String?>(null)}
    var quantity by rememberSaveable {mutableStateOf("")}
    var cost by rememberSaveable {mutableStateOf("")}
    var paid by rememberSaveable {mutableStateOf("0")}
    var supplierName by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)}
    var confirmed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    val options=loaded?.getOrNull()
    val unit=options?.units?.singleOrNull {it.id==unitId}
    val supplier=options?.suppliers?.singleOrNull {it.id==supplierId}
    val unitName=unit?.name ?: product.unit
    val precision=if(unit==null)product.quantityPrecision else 0
    val amount=parseNaira(cost);val count=parseQuantity(quantity,precision)
    val total=runCatching {if(count!=null && amount!=null)count.lineTotalMinor(amount) else null}.getOrNull()
    fun edit() {reviewed=false;confirmed=false}
    fun chooseUnit(id:String?) {if(unitId!=id) {unitId=id;quantity="";cost="";edit()}}
    OperationsDialog("Add stock · ${product.name}",dismiss,if(reviewed)"Save stock received" else "Review stock received",action={
        requireNotNull(options) {"Wait for the buying units and supplier accounts to load, or retry."}
        require(unitId==null || unit!=null) {"Choose an available buying unit again."}
        require(supplierId==null || supplier!=null) {"Choose an active supplier again."}
        val scaled=requireNotNull(count) {"Enter a received quantity in $unitName, with $precision decimal places."}.scaledUnits
        require(scaled in 1..Int.MAX_VALUE.toLong()) {"Enter a positive received quantity within the supported range."}
        val price=requireNotNull(amount) {"Enter the buying price per $unitName."}
        val cash=requireNotNull(parseNaira(paid)) {"Enter actual cash paid, or zero."}
        val value=requireNotNull(total) {"Buying total exceeds the supported amount."}
        require(cash in 0..value) {"Cash paid must be from zero to the buying total."}
        require(cash==value || supplier!=null || supplierName.isNotBlank()) {"Choose or name the supplier for the unpaid balance."}
        if(!reviewed)reviewed=true else {
            require(confirmed) {"Confirm that the goods arrived and cash paid is actual."}
            withContext(Dispatchers.IO) {
                if(supplier!=null)repo.receiveStockForSupplier(tenantId,product.id,scaled.toInt(),price,cash,supplier.id,operation,unit?.id,unit?.baseUnitsPerUnit,supplier.version)
                else repo.receiveStock(tenantId,product.id,scaled.toInt(),price,cash,supplierName,operation,unit?.id,unit?.baseUnitsPerUnit)
            }
            saved()
        }
    }) {
        OperationsLoadState(loaded,"buying units and supplier accounts",{revision++})
        Text("How did these goods arrive?",style=MaterialTheme.typography.titleMedium)
        FilterChip(unitId==null,{chooseUnit(null)},label={Text(product.unit)})
        options?.units.orEmpty().forEach {choice ->FilterChip(unitId==choice.id,{chooseUnit(choice.id)},label={Text("${choice.name} · ${choice.baseUnitsPerUnit} ${choice.baseUnit} each")})}
        Text("Changing the buying unit clears quantity and buying price. Package quantities must be whole; stock remains in ${product.unit}.")
        PosTextField(quantity,{quantity=it;edit()},"Quantity received in $unitName")
        PosTextField(cost,{cost=it;edit()},"Buying price per $unitName (₦)")
        PosTextField(paid,{paid=it;edit()},"Cash paid from drawer (₦)")
        Text("Supplier account",style=MaterialTheme.typography.titleMedium)
        FilterChip(supplierId==null,{supplierId=null;edit()},label={Text("Name only · not linked")})
        options?.suppliers.orEmpty().forEach {profile ->FilterChip(supplierId==profile.id,{supplierId=profile.id;edit()},label={Text(profile.details.name)})}
        if(supplierId==null)PosTextField(supplierName,{supplierName=it;edit()},"Supplier name",hint="Required if unpaid; choose an account above to link it")
        total?.let {LabelValueRow("Buying total",money(it),emphasized=true)}
        if(reviewed) {
            LabelValueRow("Buying unit",if(unit==null)product.unit else "${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}")
            LabelValueRow("Supplier",supplier?.details?.name ?: supplierName.ifBlank {"Cash purchase"})
            LabelValueRow("Cash paid",money(parseNaira(paid) ?: 0))
            LabelValueRow("Still owed",money(requireNotNull(total)-(parseNaira(paid) ?: 0)))
            OperationsCheck(confirmed,{confirmed=it},"I received these goods and recorded the actual cash paid.")
        }
    }
}

@Composable
private fun CountAndroidStock(repo: PosRepository, tenantId: String, product: Product, dismiss: () -> Unit, saved: () -> Unit) {
    var counted by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog("Count stock · ${product.name}", dismiss, if (reviewed) "Save stock correction" else "Review difference", action = {
        val quantity = requireNotNull(parseQuantity(counted, product.quantityPrecision)) { "Enter the actual count in ${product.unit}." }
        require(quantity.scaledUnits <= Int.MAX_VALUE) { "Count exceeds the supported range." }
        require(reason.isNotBlank()) { "Explain this stock correction." }
        if (!reviewed) reviewed = true
        else {
            withContext(Dispatchers.IO) { repo.adjustStock(tenantId, product.id, quantity.scaledUnits.toInt(), reason, operation, product.stock) }
            saved()
        }
    }) {
        LabelValueRow("Previously on hand", "${product.stockLabel} ${product.unit}")
        PosTextField(counted, { counted = it; reviewed = false }, "Actual count in ${product.unit}")
        PosTextField(reason, { reason = it; reviewed = false }, "Reason for the correction")
        if (reviewed) {
            val quantity = requireNotNull(parseQuantity(counted, product.quantityPrecision))
            LabelValueRow("New on hand", "${quantityLabel(quantity.scaledUnits, product.quantityPrecision)} ${product.unit}")
            Text("Saving retains the previous count and reason. It records no sale, cash payment or expense.")
        }
    }
}

@Composable
fun AndroidCustomersScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var repaying by remember { mutableStateOf<Customer?>(null) }
    var history by remember { mutableStateOf<Customer?>(null) }
    var editing by remember { mutableStateOf<Customer?>(null) }
    var contactHistory by remember { mutableStateOf<Customer?>(null) }
    var statement by remember { mutableStateOf<Customer?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) { repo.customers(tenantId) }
    fun changed(message: String) { revision++; notice = message; onChanged() }
    if (adding) AddAndroidCustomer(repo, tenantId, { adding = false }) { adding = false; changed("Customer saved on this device") }
    repaying?.let { customer -> AndroidRepayment(repo, tenantId, customer, { repaying = null }) { repaying = null; changed("Cash payment saved against customer debt") } }
    history?.let { customer -> AndroidCustomerHistory(repo, tenantId, customer, { history = null }) }
    editing?.let { customer -> AndroidCustomerProfileEditor(repo, tenantId, customer, { editing = null }) { editing = null; changed("Customer details saved; existing debt retained") } }
    contactHistory?.let { customer -> AndroidCustomerProfileHistory(repo, tenantId, customer, { contactHistory = null }) }
    statement?.let { customer -> AndroidCustomerStatement(repo, tenantId, customer, { statement = null }) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Customers", "Keep customer debt separate from money you hold for them.") }
        item { PosButton("Add customer", { adding = true }, modifier = Modifier.fillMaxWidth()) }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { PosTextField(search, { search = it }, "Find a customer", hint = "Name or phone number") }
        item { OperationsLoadState(loaded, "customers", { revision++ }) }
        val rows = loaded?.getOrNull().orEmpty().filter { search.isBlank() || it.name.contains(search, true) || it.phone?.contains(search) == true }
        if (loaded?.isSuccess == true && rows.isEmpty()) item { EmptyState("No matching customers", "Add a customer or change your search.") }
        items(rows, key = { it.id }) { customer ->
            OperationsCard {
                Text(customer.name, style = MaterialTheme.typography.titleMedium)
                customer.phone?.let { Text(it) }
                LabelValueRow("Customer owes you", money(customer.balanceMinor), emphasized = true)
                LabelValueRow("Credit limit", customer.creditLimitMinor?.let(::money) ?: "No limit set")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ history = customer }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("History") }
                    Button({ repaying = customer }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = customer.balanceMinor > 0) { Text("Record payment") }
                }
                OutlinedButton({ editing = customer }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage customer") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ contactHistory = customer }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Contact history") }
                    OutlinedButton({ statement = customer }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Dated statement") }
                }
            }
        }
    }
}

@Composable
private fun AddAndroidCustomer(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    OperationsDialog("Add customer", dismiss, "Save customer", action = {
        val amount = if (limit.isBlank()) null else requireNotNull(parseNaira(limit)) { "Enter an exact credit limit or leave it blank." }
        withContext(Dispatchers.IO) { repo.addCustomer(tenantId, name, phone.trim().ifBlank { null }, address.trim().ifBlank { null }, amount) }
        saved()
    }) {
        PosTextField(name, { name = it }, "Customer name")
        PosTextField(phone, { phone = it }, "Phone number (optional)")
        PosTextField(address, { address = it }, "Address (optional)")
        PosTextField(limit, { limit = it }, "Credit limit (₦)", hint = "Blank means no limit; zero prevents new credit")
    }
}

@Composable
private fun AndroidRepayment(repo: PosRepository, tenantId: String, customer: Customer, dismiss: () -> Unit, saved: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog("Record payment · ${customer.name}", dismiss, if (reviewed) "Save cash received" else "Review payment", action = {
        val cash = requireNotNull(parseNaira(amount)) { "Enter the cash actually received." }
        require(cash > 0 && cash <= customer.balanceMinor) { "Enter a payment greater than zero, up to ${money(customer.balanceMinor)}." }
        if (!reviewed) reviewed = true
        else {
            require(received) { "Confirm you counted and received this cash." }
            withContext(Dispatchers.IO) {
                synchronized(repo) {
                    requireNotNull(repo.activeCashShift(tenantId)) { "Open the cash drawer before recording this cash payment." }
                    require(repo.customers(tenantId).single { it.id == customer.id }.balanceMinor == customer.balanceMinor) { "Customer debt changed after review. Reopen this payment and review it again." }
                    repo.recordRepayment(tenantId, customer.id, cash, operation)
                }
            }
            saved()
        }
    }) {
        LabelValueRow("Customer owes you", money(customer.balanceMinor))
        PosTextField(amount, { amount = it; reviewed = false; received = false }, "Cash received (₦)")
        if (reviewed) {
            LabelValueRow("Cash added to drawer", money(requireNotNull(parseNaira(amount))))
            LabelValueRow("Debt after this payment", money(customer.balanceMinor - requireNotNull(parseNaira(amount))))
            OperationsCheck(received, { received = it }, "I counted and received this cash.")
        }
        Text("This is a cash payment against existing debt. Bank transfers and money held in advance must use their own workflows.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AndroidCustomerHistory(repo: PosRepository, tenantId: String, customer: Customer, dismiss: () -> Unit) {
    val loaded = operationsLoad(repo, tenantId, customer.id) { repo.sales(tenantId).filter { it.customerId == customer.id } to repo.repayments(tenantId, customer.id) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(customer.name) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValueRow("Current debt", money(customer.balanceMinor), emphasized = true)
            OperationsLoadState(loaded, "customer history", null)
            loaded?.getOrNull()?.let { (sales, payments) ->
                Text("Credit receipts", style = MaterialTheme.typography.titleSmall)
                if (sales.isEmpty()) Text("No linked receipts")
                sales.forEach { sale ->
                    Text("Receipt ${sale.id.takeLast(8)} · ${sale.createdAt.take(10)}")
                    LabelValueRow("Original credit", money(sale.creditMinor))
                }
                Text("Debt repayments", style = MaterialTheme.typography.titleSmall)
                Text("Repayments may include applied advances. Open customer advances for funding history.", style = MaterialTheme.typography.bodySmall)
                if (payments.isEmpty()) Text("No debt repayments recorded")
                payments.forEach { payment -> LabelValueRow(payment.createdAt.take(10), money(payment.amountMinor)) }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Close") } })
}

private data class AndroidMoneyData(val active: CashShift?, val expected: Long?, val shifts: List<CashShift>, val entries: List<CashEntry>)

@Composable
fun AndroidMoneyScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) {
        val active = repo.activeCashShift(tenantId)
        AndroidMoneyData(active, active?.let { repo.expectedCash(tenantId, it.id) }, repo.cashShifts(tenantId), active?.let { repo.cashEntries(tenantId, it.id) }.orEmpty())
    }
    val data = loaded?.getOrNull()
    action?.let { kind -> AndroidMoneyAction(repo, tenantId, kind, data?.active, data?.expected, { action = null }) {
        action = null; revision++; notice = "Cash record saved on this device"; onChanged()
    } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Money", "Cash counted and recorded in this device's shared drawer.") }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { OperationsLoadState(loaded, "cash drawer", { revision++ }) }
        if (loaded?.isSuccess == true) {
            item {
                OperationsCard {
                    Text("Today's cash", style = MaterialTheme.typography.titleMedium)
                    if (data?.active == null) {
                        Text("Open the drawer to include new cash sales and repayments in its count. Cash payouts require an open drawer.")
                        PosButton("Open cash drawer", { action = "open" }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LabelValueRow("Cash expected", money(requireNotNull(data.expected)), emphasized = true)
                        LabelValueRow("Opening cash", money(data.active.openingMinor))
                        Text("Bank transfers and card terminal portions are excluded.", style = MaterialTheme.typography.bodySmall)
                        PosButton("Record expense", { action = "expense" }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton({ action = "close" }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Count and close cash") }
                    }
                }
            }
            if (data?.entries?.isNotEmpty() == true) item { Text("This drawer's cash movements", style = MaterialTheme.typography.titleMedium) }
            items(data?.entries.orEmpty(), key = { it.id }) { entry ->
                OperationsCard {
                    Text(cashActivity(entry.kind), style = MaterialTheme.typography.titleSmall)
                    LabelValueRow(entry.description, money(entry.amountMinor))
                    Text(entry.createdAt.take(19).replace('T', ' ') + " UTC", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text("Past cash shifts", style = MaterialTheme.typography.titleMedium) }
            val closed = data?.shifts.orEmpty().filter { it.closedAt != null }
            if (closed.isEmpty()) item { Text("No closed cash shifts yet") }
            items(closed, key = { it.id }) { shift ->
                OperationsCard {
                    Text(shift.closedAt.orEmpty().take(10), style = MaterialTheme.typography.titleSmall)
                    LabelValueRow("Expected", money(requireNotNull(shift.expectedMinor)))
                    LabelValueRow("Counted", money(requireNotNull(shift.countedMinor)))
                    LabelValueRow("Difference", money(requireNotNull(shift.countedMinor) - requireNotNull(shift.expectedMinor)))
                }
            }
        }
    }
}

@Composable
private fun AndroidMoneyAction(repo: PosRepository, tenantId: String, kind: String, shift: CashShift?, expected: Long?, dismiss: () -> Unit, saved: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    val title = when (kind) { "open" -> "Open cash drawer"; "close" -> "Count and close cash"; else -> "Record expense" }
    OperationsDialog(title, dismiss, if (reviewed) "Save cash record" else "Review cash record", action = {
        val value = requireNotNull(parseNaira(amount)) { "Enter an exact cash amount." }
        require(value >= 0 && (kind != "expense" || value > 0)) { "Enter a valid non-negative amount." }
        if (kind == "expense") require(category.isNotBlank() && description.isNotBlank()) { "Enter an expense category and description." }
        if (!reviewed) reviewed = true
        else {
            require(confirmed) { "Confirm this actual cash count or payment." }
            withContext(Dispatchers.IO) {
                when (kind) {
                    "open" -> repo.openCashShift(tenantId, value)
                    "close" -> {
                        synchronized(repo) {
                            val original = requireNotNull(shift) { "Reopen the cash count before continuing." }
                            val current = repo.cashShifts(tenantId).single { it.id == original.id }
                            if (current.closedAt == null) require(repo.activeCashShift(tenantId)?.id == original.id && repo.expectedCash(tenantId, original.id) == expected) { "Cash changed after review. Reopen the count and review it again." }
                            repo.closeCashShift(tenantId, original.id, value)
                        }
                    }
                    else -> repo.recordExpense(tenantId, category, description, value, operation)
                }
            }
            saved()
        }
    }) {
        if (kind == "expense") {
            PosTextField(category, { category = it; reviewed = false; confirmed = false }, "Expense category", hint = "Transport, fuel, rent…")
            PosTextField(description, { description = it; reviewed = false; confirmed = false }, "What was paid for?")
        }
        PosTextField(amount, { amount = it; reviewed = false; confirmed = false }, when (kind) { "open" -> "Opening cash counted (₦)"; "close" -> "Cash counted (₦)"; else -> "Cash paid (₦)" })
        if (reviewed) {
            val value = requireNotNull(parseNaira(amount))
            LabelValueRow(if (kind == "expense") "Cash leaving drawer" else "Actual cash counted", money(value), emphasized = true)
            if (kind == "close") {
                LabelValueRow("Cash expected", money(requireNotNull(expected)))
                LabelValueRow("Difference retained in history", money(value - expected))
                Text("Closing preserves any shortage or excess. It does not alter earlier sales or payments.")
            }
            OperationsCheck(confirmed, { confirmed = it }, if (kind == "expense") "I paid this cash expense." else "I counted this actual cash.")
        }
    }
}

@Composable
fun AndroidSalesScreen(repo: PosRepository, tenantId: String, onReceipt: (String) -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Sale?>(null) }
    var returning by remember { mutableStateOf<Sale?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) { repo.sales(tenantId) }
    selected?.let { sale ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Receipt ${sale.id.takeLast(8)}") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(sale.createdAt.take(19).replace('T', ' ') + " UTC")
                sale.lines.forEach { line -> LabelValueRow("${line.product.name} · ${quantityLabel(line.quantity.toLong(), line.quantityPrecision)} ${line.sellingUnit}", money(line.totalMinor)) }
                HorizontalDivider()
                LabelValueRow("Sale total", money(sale.totalMinor), emphasized = true)
                LabelValueRow("Cash applied", money(sale.cashMinor))
                sale.cashGivenMinor?.let { LabelValueRow("Cash given", money(it)); LabelValueRow("Change", money(requireNotNull(sale.changeMinor))) }
                if (sale.creditMinor > 0) LabelValueRow("Original customer credit", money(sale.creditMinor))
                if (sale.externalPayments.isNotEmpty()) {
                    LabelValueRow("External portions · not confirmed", money(sale.externalRecordedMinor))
                    Text("This app has not confirmed that the money arrived.")
                }
                sale.note?.let { Text(it) }
                OutlinedButton({ selected = null; onReceipt(sale.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Print or share receipt") }
                if (sale.externalPayments.isEmpty() && sale.creditMinor == 0L && sale.exchange == null && sale.orderPayment == null) {
                    OutlinedButton({ selected = null; returning = sale }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Return items") }
                } else Text("Returns for credit, external portions, exchanges and order collections are not available in this Android screen yet.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ selected = null }) { Text("Close") } })
    }
    returning?.let { sale -> AndroidCashReturn(repo, tenantId, sale, { returning = null }) {
        returning = null; revision++; notice = "Return saved with original receipt and refund history"
    } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Sales", "Saved receipts on this device.") }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        item { PosTextField(search, { search = it }, "Find a sale", hint = "Receipt ID, item, note or reference") }
        item { OperationsLoadState(loaded, "sales", { revision++ }) }
        val sales = loaded?.getOrNull().orEmpty().filter { sale -> search.isBlank() || sale.id.contains(search, true) || sale.note?.contains(search, true) == true || sale.lines.any { it.product.name.contains(search, true) } || sale.externalPayments.any { it.reference.contains(search, true) } }
        if (loaded?.isSuccess == true && sales.isEmpty()) item { EmptyState("No matching sales", "Complete a sale or change your search.") }
        items(sales, key = { it.id }) { sale ->
            Surface(onClick = { selected = sale }, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelValueRow("Receipt ${sale.id.takeLast(8)}", money(sale.totalMinor), emphasized = true)
                    Text(sale.createdAt.take(19).replace('T', ' ') + " UTC", style = MaterialTheme.typography.bodySmall)
                    Text(sale.lines.joinToString { it.product.name }, maxLines = 2)
                    if (sale.creditMinor > 0) Text("Original credit ${money(sale.creditMinor)}")
                    if (sale.externalPayments.isNotEmpty()) Text("Transfer/card portions not confirmed", color = MaterialTheme.colorScheme.error)
                    Text("Open receipt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AndroidCashReturn(repo: PosRepository, tenantId: String, sale: Sale, dismiss: () -> Unit, saved: () -> Unit) {
    val remaining = operationsLoad(repo, tenantId, sale.id) { repo.returnableQuantities(tenantId, sale.id) }
    val quantities = remember { mutableStateMapOf<String, String>() }
    var reason by rememberSaveable { mutableStateOf("") }
    var restock by rememberSaveable { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    fun selected(): Map<String, Int> = sale.lines.mapNotNull { line ->
        val input = quantities[line.key].orEmpty()
        if (input.isBlank()) null else {
            val count = requireNotNull(parseQuantity(input, line.quantityPrecision)) { "Enter an exact return quantity for ${line.product.name}." }.scaledUnits
            require(count <= Int.MAX_VALUE && count <= (remaining?.getOrThrow()?.get(line.key) ?: 0)) { "Return exceeds the remaining receipt quantity." }
            if (count == 0L) null else line.key to count.toInt()
        }
    }.toMap().also { require(it.isNotEmpty()) { "Choose at least one returned item." } }
    OperationsDialog("Return items · ${sale.id.takeLast(8)}", dismiss, if (reviewed) "Save return and cash refund" else "Review return", action = {
        val picked = selected()
        require(reason.isNotBlank()) { "Enter the reason for this return." }
        if (!reviewed) reviewed = true
        else {
            require(confirmed) { "Confirm the goods and actual cash refund." }
            withContext(Dispatchers.IO) {
                synchronized(repo) {
                    if (repo.saleReturns(tenantId, sale.id).none { it.id == operation }) require(repo.returnableQuantities(tenantId, sale.id) == remaining?.getOrThrow()) { "Returned quantities changed after review. Reopen this return before continuing." }
                    repo.returnItems(tenantId, sale.id, picked, restock, reason, operation)
                }
            }
            saved()
        }
    }) {
        Text("Cash-only receipt. The saved return retains its original sale and price.")
        OperationsLoadState(remaining, "return quantities", null)
        sale.lines.forEach { line ->
            PosTextField(quantities[line.key].orEmpty(), { quantities[line.key] = it; reviewed = false; confirmed = false }, "${line.product.name} · ${line.sellingUnit}", hint = "Remaining ${quantityLabel((remaining?.getOrNull()?.get(line.key) ?: 0).toLong(), line.quantityPrecision)}")
        }
        PosTextField(reason, { reason = it; reviewed = false; confirmed = false }, "Reason for return")
        OperationsCheck(restock, { restock = it; reviewed = false; confirmed = false }, "Return these goods to sellable stock")
        if (reviewed) {
            val picked = selected()
            val refund = sale.lines.fold(0L) { sum, line ->
                val already = line.quantity - requireNotNull(remaining?.getOrThrow()?.get(line.key))
                Math.addExact(sum, line.cumulativeRefundMinor(already + (picked[line.key] ?: 0)) - line.cumulativeRefundMinor(already))
            }
            LabelValueRow("Cash refund from drawer", money(refund), emphasized = true)
            Text(if (restock) "Selected goods will return to sellable stock." else "Selected goods will not return to sellable stock.")
            OperationsCheck(confirmed, { confirmed = it }, "I checked the returned goods and am paying this cash refund.")
        }
    }
}

@Composable
internal fun OperationsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun OperationsCheck(value: Boolean, change: (Boolean) -> Unit, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value=value,role=Role.Checkbox,onValueChange=change), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(value, onCheckedChange=null)
        Text(text, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
internal fun OperationsDialog(title: String, dismiss: () -> Unit, actionLabel: String, action: suspend () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            error?.let { InlineNotice("Nothing saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try { action() } catch (e: Exception) { error = e.message ?: "The record could not be saved. Review it and retry." }
                finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Working…" else actionLabel) }
    }, dismissButton = { TextButton({ if (!busy) dismiss() }, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun <T> operationsLoad(repo: PosRepository, tenantId: String, revision: Any?, load: () -> T): Result<T>? {
    var result by remember(repo, tenantId, revision) { mutableStateOf<Result<T>?>(null) }
    val cloudRevision=pos.cloud.ui.LocalCloudRevision.current
    LaunchedEffect(repo, tenantId, revision, cloudRevision) { result = withContext(Dispatchers.IO) { runCatching(load) } }
    return result
}

@Composable
internal fun <T> OperationsLoadState(loaded: Result<T>?, subject: String, retry: (() -> Unit)?) {
    when {
        loaded == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        loaded.isFailure -> {
            InlineNotice("Could not load $subject", loaded.exceptionOrNull()?.message ?: "Try again.", NoticeTone.Danger)
            if (retry != null) TextButton(retry) { Text("Retry") }
        }
    }
}

private fun cashActivity(kind: String): String = when (kind) {
    "sale" -> "Cash sale"
    "repayment" -> "Cash debt payment"
    "expense" -> "Cash expense"
    "stock_purchase" -> "Stock bought with cash"
    "supplier_payment" -> "Supplier cash payment"
    "return", "refund" -> "Sale cash refund"
    "customer_advance" -> "Customer advance received"
    "customer_advance_refund" -> "Customer advance refunded"
    "manual" -> "Money added or taken out"
    else -> "Recorded cash movement"
}
