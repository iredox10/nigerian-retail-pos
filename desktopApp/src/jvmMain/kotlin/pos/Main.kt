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
import pos.ui.theme.PosTheme

fun main() = application {
    val repository = remember { PosRepository(System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos") + "/pos.db") }
    Window(onCloseRequest = ::exitApplication, title = "Retail POS", state = rememberWindowState(width = 1440.dp, height = 900.dp)) {
        PosTheme { App(repository) }
    }
}

@Composable internal fun App(repo: PosRepository, initialTenant: Tenant? = null) {
    var tenant by remember { mutableStateOf<Tenant?>(initialTenant) }
    val access = remember { WorkspaceAccess() }
    var revision by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf("Sell") }
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
                    val selected = route == item
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
                    Spacer(Modifier.weight(1f))
                    Text("${current.name}  /  Counter 1", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f).padding(24.dp)) {
                    when (route) {
                        "Sell" -> saleStates.SaveableStateProvider(current.id) { SellScreen(current.id, products, customers) { lines, cash, customer, operationId ->
                            try { repo.checkout(current.id, lines, cash, customer, operationId); revision++; null } catch (e: Exception) { e.message ?: "Sale not saved" }
                        } }
                        "Stock" -> StockScreen(products) { name, unit, price, stock ->
                            try { repo.addProduct(current.id, name, unit, price, stock); revision++; null } catch(e: Exception) { e.message }
                        }
                        "Customers" -> CustomerScreen(customers, { name -> repo.addCustomer(current.id, name); revision++ }, { id, amount ->
                            try { repo.recordRepayment(current.id, id, amount); revision++; null } catch(e:Exception) { e.message ?: "Payment not saved" }
                        })
                        "Sales" -> {
                            val sales = remember(current.id, revision) { repo.sales(current.id) }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("Sales & receipts", style = MaterialTheme.typography.headlineSmall); Text("Saved on this computer · ${sales.size} sales") }
                                if(sales.isEmpty()) item { Empty("No sales yet", "Completed sales appear here after you take payment.") }
                                items(sales) { sale -> Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Row { Text("Receipt ${sale.id.takeLast(8).uppercase()}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(money(sale.totalMinor), fontWeight = FontWeight.SemiBold) }
                                    Text(sale.createdAt, style = MaterialTheme.typography.bodySmall)
                                    Text(if(sale.creditMinor > 0) "Customer owes ${money(sale.creditMinor)}" else "Paid in cash")
                                    sale.lines.forEach { Text("${it.quantity} ${it.product.unit} · ${it.product.name}") }
                                } } }
                            }
                        }
                        "Money" -> {
                            val sales = repo.sales(current.id)
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("Money recorded", style = MaterialTheme.typography.headlineSmall)
                                Text("All locally recorded sales · This shop only", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MoneyRow("Sales", sales.sumOf { it.totalMinor })
                                MoneyRow("Cash collected at checkout", sales.sumOf { it.cashMinor })
                                MoneyRow("Customers still owe", customers.sumOf { it.balanceMinor })
                                Text("Cash drawer counts, expenses and supplier payments are not implemented yet. These figures are not a cash reconciliation or profit report.", Modifier.widthIn(max = 680.dp))
                            }
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("Your shop tools", style = MaterialTheme.typography.headlineSmall)
                            Text("Available now: local sales, products, customer credit and repayments, receipts, tenant workspaces.")
                            Text("Coming next: staff roles, stock receiving, cash closing, returns, printing, backups, and authenticated cloud sync. These services are not connected yet.", Modifier.widthIn(max=680.dp))
                            Text("Each shop's records are scoped by tenant. This local owner login is not a substitute for server authorization or device encryption.", Modifier.widthIn(max=680.dp))
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Text("Local storage active · Online sync not configured", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text("${repo.pendingSyncCount(current.id)} changes awaiting online setup", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun WorkspacePicker(repo: PosRepository, access: WorkspaceAccess, onEnter: (Tenant)->Unit) {
    var tenants by remember { mutableStateOf(repo.tenants()) }
    var selected by remember { mutableStateOf<Tenant?>(null) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
                    val t=repo.createTenant(name.trim()); access.register(t.id,password); tenants=repo.tenants(); onEnter(t)
                } else { require(access.unlock(selected!!.id,password)) { "Password not recognized for this shop" }; onEnter(selected!!) }
            } catch(e:Exception) {error=e.message ?: "Could not open shop"}},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(selected==null) "Create shop workspace" else "Unlock shop") }
            if(selected!=null) TextButton(shape=MaterialTheme.shapes.small,onClick={selected=null;password=""}) { Text("Create another shop") }
            Text("No online account is created. Use a trusted computer; local database encryption is not yet enabled.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun Empty(title:String,body:String) { Column(Modifier.padding(vertical=40.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleLarge);Text(body) } }
@Composable private fun MoneyRow(label:String,amount:Long) { Row(Modifier.widthIn(max=680.dp).fillMaxWidth()) { Text(label,Modifier.weight(1f));Text(money(amount),fontWeight=FontWeight.SemiBold) } }

@Composable private fun StockScreen(products:List<Product>, add:(String,String,Long,Double)->String?) {
    var editing by remember { mutableStateOf(false) };var search by remember { mutableStateOf("") }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(search,{search=it},label={Text("Search stock")},modifier=Modifier.weight(1f));Spacer(Modifier.width(16.dp));Button(shape=MaterialTheme.shapes.small,onClick={editing=true}){Text("Add item")} }
        if(products.isEmpty()) Empty("Add your first item", "Give it a name, selling unit, price and opening stock to start selling.")
        LazyColumn { items(products.filter { it.name.contains(search,true) }) { p -> Surface(Modifier.fillMaxWidth().padding(bottom=1.dp)) { Row(Modifier.padding(16.dp)) { Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Medium); Text("${p.stock} ${p.unit} available",style=MaterialTheme.typography.bodySmall)};Text("${money(p.priceMinor)} / ${p.unit}") } } } }
    }
    if(editing) EntryDialog("Add item",listOf("Item name","Selling unit (e.g. bottle)","Selling price in naira","Opening quantity"),{editing=false}) { fields ->
        try { val price=java.math.BigDecimal(fields[2]).movePointRight(2).longValueExact(); val stock=fields[3].toDouble(); val error=add(fields[0],fields[1],price,stock); if(error==null)editing=false;error }catch(e:Exception){"Enter a valid price and opening quantity"}
    }
}

@Composable private fun CustomerScreen(customers:List<Customer>,add:(String)->Unit,repay:(String,Long)->String?) {
    var adding by remember { mutableStateOf(false) };var repayment by remember { mutableStateOf<Customer?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row { Text("Who owes you",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);Button(shape=MaterialTheme.shapes.small,onClick={adding=true}){Text("Add customer")} }
        if(customers.isEmpty()) Empty("No customers yet","Add a customer when you need to record credit or a repayment.")
        LazyColumn { items(customers){ c -> Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),shape=MaterialTheme.shapes.small) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text(c.name,fontWeight=FontWeight.SemiBold);Text("Customer owes you ${money(c.balanceMinor)}")};OutlinedButton(shape=MaterialTheme.shapes.small,onClick={repayment=c},enabled=c.balanceMinor>0){Text("Record payment")} } } } }
    }
    if(adding) EntryDialog("Add customer",listOf("Customer name"),{adding=false}) { f -> try {require(f[0].isNotBlank());add(f[0]);adding=false;null}catch(e:Exception){"Enter a customer name"} }
    repayment?.let { c -> EntryDialog("Record payment · ${c.name}",listOf("Cash received in naira"),{repayment=null}) { f -> try {val error=repay(c.id,java.math.BigDecimal(f[0]).movePointRight(2).longValueExact());if(error==null)repayment=null;error}catch(e:Exception){"Enter a valid amount"} } }
}

@Composable private fun EntryDialog(title:String,labels:List<String>,dismiss:()->Unit,save:(List<String>)->String?) {
    val fields=remember { mutableStateListOf<String>().apply { repeat(labels.size){add("")} } };var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){labels.forEachIndexed {i,label->OutlinedTextField(fields[i],{fields[i]=it},label={Text(label)},singleLine=true)};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={Button(shape=MaterialTheme.shapes.small,onClick={error=save(fields.toList())}){Text("Save")}},dismissButton={TextButton(dismiss){Text("Cancel")}})
}
