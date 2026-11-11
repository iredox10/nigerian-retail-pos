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
            "CREATE TABLE IF NOT EXISTS repayments(id TEXT PRIMARY KEY,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))"
        ).forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    @Synchronized fun tenants(): List<Tenant> = query("SELECT id,name FROM tenants ORDER BY name") { Tenant(it.getString(1), it.getString(2)) }
    @Synchronized fun createTenant(name: String): Tenant = transaction {
        require(name.isNotBlank()) { "Enter your shop name." }
        Tenant(id(), name.trim()).also { execute("INSERT INTO tenants VALUES(?,?)", it.id, it.name) }
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
        require(priceMinor >= 0 && stock.isFinite() && stock >= 0) { "Price and stock cannot be negative." }
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
        require(operationId == null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        if (operationId != null) {
            val existing=sales(tenantId).find { it.id==operationId }
            if (existing!=null) {
                require(existing.cashMinor==cashMinor && existing.customerId==customerId && existing.lines.map { Triple(it.product.id,it.quantity,it.product.priceMinor) }.sortedBy { it.first }==lines.map { Triple(it.product.id,it.quantity,it.product.priceMinor) }.sortedBy { it.first }) { "This operation was already saved with different details." }
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
        enqueue(tenantId,"sale.completed",sale.id)
        sale
    }
    @Synchronized fun recordRepayment(tenantId: String, customerId: String, amountMinor: Long) = transaction {
        requireTenant(tenantId)
        require(amountMinor>0) { "Enter a payment greater than zero." }
        val changed=execute("UPDATE customers SET balance_minor=balance_minor-? WHERE tenant_id=? AND id=? AND balance_minor>=?",amountMinor,tenantId,customerId,amountMinor)
        require(changed==1) { "Customer not found in this shop, or payment exceeds debt." }
        val paymentId=id()
        execute("INSERT INTO repayments VALUES(?,?,?,?,?)",paymentId,tenantId,customerId,amountMinor,Instant.now().toString())
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
