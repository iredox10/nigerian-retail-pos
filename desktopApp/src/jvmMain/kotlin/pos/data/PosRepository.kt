package pos.data

import pos.domain.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Local persistence boundary. All business record access requires an explicit tenant. */
class PosRepository(path: String = File(System.getProperty("user.home"), ".nigerian-retail-pos/pos.db").path) : AutoCloseable {
    private val db: Connection
    init {
        if (path != ":memory:") File(path).absoluteFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite:$path")
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=FULL")
        }
        listOf(
            "CREATE TABLE IF NOT EXISTS tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS products(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL CHECK(price_minor>=0),stock REAL NOT NULL CHECK(stock>=0),PRIMARY KEY(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS customers(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),name TEXT NOT NULL,balance_minor INTEGER NOT NULL DEFAULT 0 CHECK(balance_minor>=0),PRIMARY KEY(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS sales(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),total_minor INTEGER NOT NULL,cash_minor INTEGER NOT NULL,credit_minor INTEGER NOT NULL,customer_id TEXT,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS sale_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,sale_id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,tenant_id TEXT NOT NULL REFERENCES tenants(id),kind TEXT NOT NULL,entity_id TEXT NOT NULL,created_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending')",
            "CREATE TABLE IF NOT EXISTS repayments(id TEXT PRIMARY KEY,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS stock_receipts(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),unit_cost_minor INTEGER NOT NULL CHECK(unit_cost_minor>=0),total_cost_minor INTEGER NOT NULL CHECK(total_cost_minor>=0),paid_cash_minor INTEGER NOT NULL CHECK(paid_cash_minor>=0 AND paid_cash_minor<=total_cost_minor),supplier_name TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS expenses(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),category TEXT NOT NULL,description TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS cash_shifts(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),opening_minor INTEGER NOT NULL CHECK(opening_minor>=0),opened_at TEXT NOT NULL,closed_at TEXT,expected_minor INTEGER,counted_minor INTEGER,PRIMARY KEY(tenant_id,id))",
            "CREATE UNIQUE INDEX IF NOT EXISTS one_active_cash_shift ON cash_shifts(tenant_id) WHERE closed_at IS NULL",
            "CREATE TABLE IF NOT EXISTS cash_entries(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),shift_id TEXT,kind TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor<>0),description TEXT NOT NULL,created_at TEXT NOT NULL,source_id TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,kind,source_id),FOREIGN KEY(tenant_id,shift_id) REFERENCES cash_shifts(tenant_id,id))"
        ).forEach { sql -> db.createStatement().use { it.execute(sql) } }
        // Legacy cash stays unassigned: a new shift's opening count already includes physical cash.
        transaction {
            execute("INSERT OR IGNORE INTO cash_entries(id,tenant_id,shift_id,kind,amount_minor,description,created_at,source_id) SELECT 'legacy-sale-'||id,tenant_id,NULL,'sale',cash_minor,'Cash sale '||substr(id,1,8),created_at,id FROM sales WHERE cash_minor>0")
            execute("INSERT OR IGNORE INTO cash_entries(id,tenant_id,shift_id,kind,amount_minor,description,created_at,source_id) SELECT 'legacy-repayment-'||id,tenant_id,NULL,'repayment',amount_minor,'Customer repayment',created_at,id FROM repayments WHERE amount_minor>0")
        }
    }

    @Synchronized fun tenants(): List<Tenant> = query("SELECT id,name FROM tenants ORDER BY name") { Tenant(it.getString(1), it.getString(2)) }
    @Synchronized fun createTenant(name: String, provisionOwner: (Tenant) -> Unit = {}): Tenant = transaction {
        require(name.isNotBlank()) { "Enter your shop name." }
        Tenant(id(), name.trim()).also {
            execute("INSERT INTO tenants VALUES(?,?)", it.id, it.name)
            provisionOwner(it)
        }
    }
    @Synchronized fun products(tenantId: String): List<Product> {
        requireTenant(tenantId)
        return query("SELECT * FROM products WHERE tenant_id=? ORDER BY name", tenantId, map = ::product)
    }
    @Synchronized fun customers(tenantId: String): List<Customer> {
        requireTenant(tenantId)
        return query("SELECT * FROM customers WHERE tenant_id=? ORDER BY name", tenantId) { Customer(it.getString("id"), tenantId, it.getString("name"), it.getLong("balance_minor")) }
    }
    @Synchronized fun addProduct(tenantId: String, name: String, unit: String, priceMinor: Long, stock: Double): Product = transaction {
        requireTenant(tenantId)
        require(name.isNotBlank() && unit.isNotBlank()) { "Enter the item name and selling unit." }
        require(priceMinor >= 0 && stock.isFinite() && stock >= 0 && stock <= 9_007_199_254_740_991.0) { "Enter a non-negative price and stock within the supported quantity range." }
        Product(id(), tenantId, name.trim(), unit.trim(), priceMinor, stock).also {
            execute("INSERT INTO products VALUES(?,?,?,?,?,?)", it.id,tenantId,it.name,it.unit,it.priceMinor,it.stock)
            enqueue(tenantId,"product.created",it.id)
        }
    }
    @Synchronized fun addCustomer(tenantId: String, name: String): Customer = transaction {
        requireTenant(tenantId)
        require(name.isNotBlank()) { "Enter the customer's name." }
        Customer(id(),tenantId,name.trim(),0).also {
            execute("INSERT INTO customers VALUES(?,?,?,0)",it.id,tenantId,it.name)
            enqueue(tenantId,"customer.created",it.id)
        }
    }
    @Synchronized fun sales(tenantId: String): List<Sale> {
        requireTenant(tenantId)
        return query("SELECT * FROM sales WHERE tenant_id=? ORDER BY created_at DESC",tenantId) { r ->
            val saleId=r.getString("id")
            val lines=query("SELECT * FROM sale_lines WHERE tenant_id=? AND sale_id=? ORDER BY id",tenantId,saleId) { l ->
                CartLine(Product(l.getString("product_id"),tenantId,l.getString("name"),l.getString("unit"),l.getLong("price_minor"),0.0),l.getInt("quantity"))
            }
            Sale(saleId,tenantId,lines,r.getLong("total_minor"),r.getLong("cash_minor"),r.getLong("credit_minor"),r.getString("customer_id"),r.getString("created_at"))
        }
    }
    /** cashMinor is applied cash, excluding change. Caller must explicitly choose a customer for credit. */
    @Synchronized fun checkout(tenantId: String, lines: List<CartLine>, cashMinor: Long, customerId: String? = null, operationId: String? = null): Sale = transaction {
        requireTenant(tenantId)
        require(lines.isNotEmpty()) { "Choose an item before taking payment." }
        require(cashMinor >= 0) { "Cash cannot be negative." }
        require(lines.all { it.product.tenantId==tenantId && it.quantity>0 }) { "Items must belong to this shop with a positive quantity." }
        val requested=lines.groupBy { it.product.id }.values.map { group ->
            require(group.all { it.product.priceMinor==group.first().product.priceMinor && it.product.unit==group.first().product.unit }) { "Repeated item details disagree. Review the sale." }
            CartLine(group.first().product,group.fold(0) { sum,line -> Math.addExact(sum,line.quantity) })
        }
        require(operationId == null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        if (operationId != null) {
            val existing=sales(tenantId).find { it.id==operationId }
            if (existing!=null) {
                require(existing.cashMinor==cashMinor && existing.customerId==customerId && existing.lines.map { it.product.id to Triple(it.quantity,it.product.priceMinor,it.product.unit) }.sortedBy { it.first }==requested.map { it.product.id to Triple(it.quantity,it.product.priceMinor,it.product.unit) }.sortedBy { it.first }) { "This operation was already saved with different details." }
                return@transaction existing
            }
        }
        val normalized = lines.groupBy { it.product.id }.map { (_, group) ->
            require(group.all { it.product.tenantId==tenantId && it.quantity>0 }) { "This item does not belong to this shop, or quantity is invalid." }
            val quantity=group.fold(0) { acc,l -> Math.addExact(acc,l.quantity) }
            val current=query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,group.first().product.id,map=::product).singleOrNull()
                ?: error("Item no longer exists in this shop.")
            require(group.all { it.product.priceMinor==current.priceMinor && it.product.unit==current.unit }) { "Item price or unit changed. Review the sale." }
            require(current.stock>=quantity) { "Not enough ${current.unit} of ${current.name} in stock." }
            CartLine(current,quantity)
        }
        val total=normalized.fold(0L) { acc,l -> Math.addExact(acc,Math.multiplyExact(l.product.priceMinor,l.quantity.toLong())) }
        require(cashMinor<=total) { "Record applied cash only; return extra cash as change." }
        val credit=total-cashMinor
        if (customerId!=null) require(query("SELECT id FROM customers WHERE tenant_id=? AND id=?",tenantId,customerId) { it.getString(1) }.isNotEmpty()) { "Customer does not belong to this shop." }
        require(credit==0L || customerId!=null) { "Choose who will pay the remaining balance." }
        val newDebt=if(credit>0) {
            val balance=query("SELECT balance_minor FROM customers WHERE tenant_id=? AND id=?",tenantId,customerId) { it.getLong(1) }.single()
            Math.addExact(balance,credit)
        } else null
        val sale=Sale(operationId ?: id(),tenantId,normalized,total,cashMinor,credit,customerId,Instant.now().toString())
        execute("INSERT INTO sales VALUES(?,?,?,?,?,?,?)",sale.id,tenantId,total,cashMinor,credit,customerId,sale.createdAt)
        normalized.forEach { l ->
            execute("INSERT INTO sale_lines(sale_id,tenant_id,product_id,name,unit,price_minor,quantity) VALUES(?,?,?,?,?,?,?)",sale.id,tenantId,l.product.id,l.product.name,l.product.unit,l.product.priceMinor,l.quantity)
            val updated=execute("UPDATE products SET stock=stock-? WHERE tenant_id=? AND id=? AND stock>=?",l.quantity,tenantId,l.product.id,l.quantity)
            check(updated==1) { "Stock changed. Review the sale." }
        }
        if (newDebt!=null) execute("UPDATE customers SET balance_minor=? WHERE tenant_id=? AND id=?",newDebt,tenantId,customerId)
        if (cashMinor>0) appendCash(tenantId,activeCashShift(tenantId)?.id,"sale",cashMinor,"Cash sale ${sale.id.take(8)}",sale.id)
        enqueue(tenantId,"sale.completed",sale.id)
        sale
    }
    @Synchronized fun recordRepayment(tenantId: String, customerId: String, amountMinor: Long, operationId: String? = null) = transaction {
        requireTenant(tenantId)
        require(amountMinor>0) { "Enter a payment greater than zero." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val paymentId=if(operationId==null) id() else UUID.nameUUIDFromBytes(("repayment:"+tenantId+":"+operationId).toByteArray(Charsets.UTF_8)).toString()
        val previous=query("SELECT customer_id,amount_minor FROM repayments WHERE tenant_id=? AND id=?",tenantId,paymentId) { it.getString(1) to it.getLong(2) }.singleOrNull()
        if(previous!=null) {
            require(previous.first==customerId && previous.second==amountMinor) { "This repayment was already recorded with different details." }
            return@transaction
        }
        val changed=execute("UPDATE customers SET balance_minor=balance_minor-? WHERE tenant_id=? AND id=? AND balance_minor>=?",amountMinor,tenantId,customerId,amountMinor)
        require(changed==1) { "Customer not found in this shop, or payment exceeds debt." }
        execute("INSERT INTO repayments VALUES(?,?,?,?,?)",paymentId,tenantId,customerId,amountMinor,Instant.now().toString())
        appendCash(tenantId,activeCashShift(tenantId)?.id,"repayment",amountMinor,"Customer repayment",paymentId)
        enqueue(tenantId,"customer.repayment",paymentId)
    }
    @Synchronized fun pendingSyncCount(tenantId: String): Int {
        requireTenant(tenantId)
        return query("SELECT count(*) FROM outbox WHERE tenant_id=? AND status='pending'",tenantId) { it.getInt(1) }.single()
    }
    /** Explicit opt-in only: sample records never appear in a merchant's real tenant. */
    @Synchronized fun seedDemoTenants(): List<Tenant> = transaction {
        val existing=tenants().filter { it.id.startsWith("demo-") }
        if(existing.isNotEmpty()) existing else {
            listOf(Tenant("demo-adewale","Adewale Stores · Practice"),Tenant("demo-amina","Amina Provisions · Practice")).onEach { tenant ->
                execute("INSERT INTO tenants VALUES(?,?)",tenant.id,tenant.name)
                listOf(Triple("Malt drink","bottle",50000L),Triple("Golden Penny noodles","pack",20000L),Triple("Peak evaporated milk","tin",85000L),Triple("Rice 5 kg","bag",1250000L),Triple("Sunlight detergent","sachet",35000L),Triple("Groundnut oil 1 litre","bottle",320000L)).forEach { (name,unit,price) -> addProduct(tenant.id,name,unit,price,240.0) }
                addCustomer(tenant.id,"Chinedu Okafor")
                addCustomer(tenant.id,"Grace Bello")
            }
        }
    }
    @Synchronized fun stockReceipts(tenantId: String): List<StockReceipt> {
        requireTenant(tenantId)
        return query("SELECT * FROM stock_receipts WHERE tenant_id=? ORDER BY created_at DESC",tenantId,map=::stockReceipt)
    }
    @Synchronized fun receiveStock(tenantId:String,productId:String,quantity:Int,unitCostMinor:Long,paidCashMinor:Long,supplierName:String,operationId:String?=null):StockReceipt = transaction {
        requireTenant(tenantId)
        require(quantity>0 && unitCostMinor>=0) { "Enter a positive quantity and non-negative buying cost." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val total=Math.multiplyExact(unitCostMinor,quantity.toLong())
        require(paidCashMinor in 0..total) { "Cash paid must be between zero and the purchase total." }
        require(paidCashMinor==total || supplierName.isNotBlank()) { "Enter the supplier name for the unpaid purchase balance." }
        if(operationId!=null) {
            val previous=stockReceipts(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.productId==productId && previous.quantity==quantity && previous.unitCostMinor==unitCostMinor && previous.paidCashMinor==paidCashMinor && previous.supplierName==supplierName.trim()) { "This receipt was already saved with different details." }
                return@transaction previous
            }
        }
        val item=query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,productId,map=::product).singleOrNull()
            ?: throw IllegalArgumentException("Item not found in this shop.")
        val newStock=item.stock+quantity
        require(newStock.isFinite() && newStock<=9_007_199_254_740_991.0) { "Stock quantity exceeds the supported range." }
        val shift=if(paidCashMinor>0) requireCashShift(tenantId) else null
        if(shift!=null) requireCashAvailable(tenantId,shift.id,paidCashMinor)
        val receipt=StockReceipt(operationId?:id(),tenantId,productId,item.name,item.unit,quantity,unitCostMinor,total,paidCashMinor,supplierName.trim(),Instant.now().toString())
        execute("INSERT INTO stock_receipts VALUES(?,?,?,?,?,?,?,?,?,?,?)",receipt.id,tenantId,productId,item.name,item.unit,quantity,unitCostMinor,total,paidCashMinor,receipt.supplierName,receipt.createdAt)
        execute("UPDATE products SET stock=? WHERE tenant_id=? AND id=?",newStock,tenantId,productId)
        if(paidCashMinor>0) appendCash(tenantId,shift!!.id,"stock_purchase",-paidCashMinor,if(receipt.supplierName.isBlank()) "Cash stock purchase" else "Stock from ${receipt.supplierName}",receipt.id)
        enqueue(tenantId,"stock.received",receipt.id)
        receipt
    }
    @Synchronized fun expenses(tenantId:String):List<Expense> {
        requireTenant(tenantId)
        return query("SELECT * FROM expenses WHERE tenant_id=? ORDER BY created_at DESC",tenantId) { expense(it) }
    }
    @Synchronized fun recordExpense(tenantId:String,category:String,description:String,amountMinor:Long,operationId:String?=null):Expense = transaction {
        requireTenant(tenantId)
        require(category.isNotBlank() && description.isNotBlank() && amountMinor>0) { "Enter category, description and an amount greater than zero." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        if(operationId!=null) {
            val previous=expenses(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.category==category.trim() && previous.description==description.trim() && previous.amountMinor==amountMinor) { "This expense was already saved with different details." }
                return@transaction previous
            }
        }
        val shift=requireCashShift(tenantId)
        requireCashAvailable(tenantId,shift.id,amountMinor)
        val result=Expense(operationId?:id(),tenantId,category.trim(),description.trim(),amountMinor,Instant.now().toString())
        execute("INSERT INTO expenses VALUES(?,?,?,?,?,?)",result.id,tenantId,result.category,result.description,amountMinor,result.createdAt)
        appendCash(tenantId,shift.id,"expense",-amountMinor,result.description,result.id)
        enqueue(tenantId,"expense.recorded",result.id)
        result
    }
    @Synchronized fun cashShifts(tenantId:String):List<CashShift> {
        requireTenant(tenantId)
        return query("SELECT * FROM cash_shifts WHERE tenant_id=? ORDER BY opened_at DESC",tenantId,map=::cashShift)
    }
    @Synchronized fun activeCashShift(tenantId:String):CashShift? {
        requireTenant(tenantId)
        return query("SELECT * FROM cash_shifts WHERE tenant_id=? AND closed_at IS NULL",tenantId,map=::cashShift).singleOrNull()
    }
    @Synchronized fun openCashShift(tenantId:String,openingMinor:Long):CashShift = transaction {
        requireTenant(tenantId)
        require(openingMinor>=0) { "Opening cash cannot be negative." }
        require(activeCashShift(tenantId)==null) { "This shop already has an open cash shift." }
        val shift=CashShift(id(),tenantId,openingMinor,Instant.now().toString(),null,null,null)
        execute("INSERT INTO cash_shifts(id,tenant_id,opening_minor,opened_at) VALUES(?,?,?,?)",shift.id,tenantId,openingMinor,shift.openedAt)
        enqueue(tenantId,"cash.shift_opened",shift.id)
        shift
    }
    /** null shiftId lists all entries, including cash recorded before a shift was opened. */
    @Synchronized fun cashEntries(tenantId:String,shiftId:String?=null):List<CashEntry> {
        requireTenant(tenantId)
        if(shiftId!=null) require(query("SELECT id FROM cash_shifts WHERE tenant_id=? AND id=?",tenantId,shiftId) { it.getString(1) }.isNotEmpty()) { "Cash shift not found in this shop." }
        val sql="SELECT * FROM cash_entries WHERE tenant_id=?"+(if(shiftId!=null) " AND shift_id=?" else "")+" ORDER BY created_at DESC,id DESC"
        return if(shiftId==null) query(sql,tenantId,map=::cashEntry) else query(sql,tenantId,shiftId,map=::cashEntry)
    }
    @Synchronized fun expectedCash(tenantId:String,shiftId:String):Long {
        requireTenant(tenantId)
        val shift=query("SELECT * FROM cash_shifts WHERE tenant_id=? AND id=?",tenantId,shiftId,map=::cashShift).singleOrNull()
            ?: throw IllegalArgumentException("Cash shift not found in this shop.")
        return shift.expectedMinor ?: cashEntries(tenantId,shiftId).fold(java.math.BigInteger.valueOf(shift.openingMinor)) { balance,entry -> balance.add(java.math.BigInteger.valueOf(entry.amountMinor)) }.longValueExact().also { require(it>=0) { "Cash balance cannot be negative." } }
    }
    @Synchronized fun recordCashMovement(tenantId:String,amountMinor:Long,description:String,operationId:String?=null):CashEntry = transaction {
        requireTenant(tenantId)
        require(amountMinor!=0L && amountMinor!=Long.MIN_VALUE && description.isNotBlank()) { "Enter a nonzero cash amount and reason." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        if(operationId!=null) {
            val previous=query("SELECT * FROM cash_entries WHERE tenant_id=? AND kind='manual' AND source_id=?",tenantId,operationId,map=::cashEntry).singleOrNull()
            if(previous!=null) {
                require(previous.amountMinor==amountMinor && previous.description==description.trim()) { "This cash movement was already saved with different details." }
                return@transaction previous
            }
        }
        val shift=requireCashShift(tenantId)
        if(amountMinor<0) requireCashAvailable(tenantId,shift.id,-amountMinor)
        val result=appendCash(tenantId,shift.id,"manual",amountMinor,description.trim(),operationId?:id())
        enqueue(tenantId,"cash.movement",result.id)
        result
    }
    @Synchronized fun closeCashShift(tenantId:String,shiftId:String,countedMinor:Long):CashShift = transaction {
        requireTenant(tenantId)
        require(countedMinor>=0) { "Counted cash cannot be negative." }
        val shift=query("SELECT * FROM cash_shifts WHERE tenant_id=? AND id=?",tenantId,shiftId,map=::cashShift).singleOrNull()
            ?: throw IllegalArgumentException("Cash shift not found in this shop.")
        if(shift.closedAt!=null) {
            require(shift.countedMinor==countedMinor) { "This shift is already closed. Its count cannot be overwritten." }
            return@transaction shift
        }
        val result=shift.copy(closedAt=Instant.now().toString(),expectedMinor=expectedCash(tenantId,shiftId),countedMinor=countedMinor)
        execute("UPDATE cash_shifts SET closed_at=?,expected_minor=?,counted_minor=? WHERE tenant_id=? AND id=? AND closed_at IS NULL",result.closedAt,result.expectedMinor,countedMinor,tenantId,shiftId)
        enqueue(tenantId,"cash.shift_closed",shiftId)
        result
    }
    private fun requireCashShift(tenantId:String)=activeCashShift(tenantId) ?: throw IllegalArgumentException("Open today's cash shift before recording money taken from the drawer.")
    private fun requireCashAvailable(tenantId:String,shiftId:String,amountMinor:Long) { require(expectedCash(tenantId,shiftId)>=amountMinor) { "This payment exceeds the cash expected in the drawer." } }
    private fun appendCash(tenantId:String,shiftId:String?,kind:String,amountMinor:Long,description:String,sourceId:String):CashEntry {
        if(shiftId!=null) {
            val shift=activeCashShift(tenantId)
            require(shift?.id==shiftId) { "The cash shift is no longer open." }
            val after=Math.addExact(expectedCash(tenantId,shiftId),amountMinor)
            require(after>=0) { "Cash expected cannot be negative." }
        }
        val entry=CashEntry(id(),tenantId,shiftId,kind,amountMinor,description,Instant.now().toString())
        execute("INSERT INTO cash_entries VALUES(?,?,?,?,?,?,?,?)",entry.id,tenantId,shiftId,kind,amountMinor,description,entry.createdAt,sourceId)
        return entry
    }
    private fun stockReceipt(r:ResultSet)=StockReceipt(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getInt("quantity"),r.getLong("unit_cost_minor"),r.getLong("total_cost_minor"),r.getLong("paid_cash_minor"),r.getString("supplier_name"),r.getString("created_at"))
    private fun expense(r:ResultSet)=Expense(r.getString("id"),r.getString("tenant_id"),r.getString("category"),r.getString("description"),r.getLong("amount_minor"),r.getString("created_at"))
    private fun cashShift(r:ResultSet)=CashShift(r.getString("id"),r.getString("tenant_id"),r.getLong("opening_minor"),r.getString("opened_at"),r.getString("closed_at"),r.getObject("expected_minor")?.let { (it as Number).toLong() },r.getObject("counted_minor")?.let { (it as Number).toLong() })
    private fun cashEntry(r:ResultSet)=CashEntry(r.getString("id"),r.getString("tenant_id"),r.getString("shift_id"),r.getString("kind"),r.getLong("amount_minor"),r.getString("description"),r.getString("created_at"))

    private fun requireTenant(tenantId: String) { require(query("SELECT id FROM tenants WHERE id=?",tenantId) { it.getString(1) }.isNotEmpty()) { "Shop not found." } }
    private fun product(r: ResultSet)=Product(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getString("unit"),r.getLong("price_minor"),r.getDouble("stock"))
    private fun enqueue(tenantId:String,kind:String,entityId:String) { execute("INSERT INTO outbox(id,tenant_id,kind,entity_id,created_at) VALUES(?,?,?,?,?)",id(),tenantId,kind,entityId,Instant.now().toString()) }
    private fun id()=UUID.randomUUID().toString()
    private fun execute(sql:String,vararg args:Any?):Int=db.prepareStatement(sql).use { s -> args.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeUpdate() }
    private fun <T> query(sql:String,vararg args:Any?,map:(ResultSet)->T):List<T> = db.prepareStatement(sql).use { s -> args.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeQuery().use { r -> buildList { while(r.next()) add(map(r)) } } }
    private fun <T> transaction(block:()->T):T {
        if(!db.autoCommit) return block()
        db.autoCommit=false
        try { val value=block();db.commit();return value } catch(e:Throwable) { db.rollback();throw e } finally { db.autoCommit=true }
    }
    @Synchronized override fun close() { db.close() }
}
