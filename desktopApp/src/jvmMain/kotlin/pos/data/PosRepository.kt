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
            "CREATE TABLE IF NOT EXISTS outbox(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),kind TEXT NOT NULL,entity_id TEXT NOT NULL,created_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',PRIMARY KEY(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS repayments(id TEXT NOT NULL,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS stock_receipts(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),unit_cost_minor INTEGER NOT NULL CHECK(unit_cost_minor>=0),total_cost_minor INTEGER NOT NULL CHECK(total_cost_minor>=0),paid_cash_minor INTEGER NOT NULL CHECK(paid_cash_minor>=0 AND paid_cash_minor<=total_cost_minor),supplier_name TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS expenses(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),category TEXT NOT NULL,description TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS cash_shifts(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),opening_minor INTEGER NOT NULL CHECK(opening_minor>=0),opened_at TEXT NOT NULL,closed_at TEXT,expected_minor INTEGER,counted_minor INTEGER,PRIMARY KEY(tenant_id,id))",
            "CREATE UNIQUE INDEX IF NOT EXISTS one_active_cash_shift ON cash_shifts(tenant_id) WHERE closed_at IS NULL",
            "CREATE TABLE IF NOT EXISTS cash_entries(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),shift_id TEXT,kind TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor<>0),description TEXT NOT NULL,created_at TEXT NOT NULL,source_id TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,kind,source_id),FOREIGN KEY(tenant_id,shift_id) REFERENCES cash_shifts(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS sale_returns(id TEXT NOT NULL,tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,refund_minor INTEGER NOT NULL CHECK(refund_minor>=0),restock INTEGER NOT NULL CHECK(restock IN(0,1)),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS return_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,return_id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),unit_price_minor INTEGER NOT NULL CHECK(unit_price_minor>=0),FOREIGN KEY(tenant_id,return_id) REFERENCES sale_returns(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS supplier_payments(id TEXT NOT NULL,tenant_id TEXT NOT NULL,receipt_id TEXT NOT NULL,supplier_name TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS catalogue_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('edit','archive')),name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL CHECK(price_minor>=0),previous_price_minor INTEGER NOT NULL CHECK(previous_price_minor>=0),barcode TEXT,archived INTEGER NOT NULL CHECK(archived IN(0,1)),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS customer_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,previous_name TEXT NOT NULL,previous_phone TEXT,previous_address TEXT,previous_credit_limit_minor INTEGER CHECK(previous_credit_limit_minor>=0),name TEXT NOT NULL,phone TEXT,address TEXT,credit_limit_minor INTEGER CHECK(credit_limit_minor>=0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS purchase_units(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,name TEXT NOT NULL,base_unit TEXT NOT NULL,base_units_per_unit INTEGER NOT NULL CHECK(base_units_per_unit>0),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,name COLLATE NOCASE),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))",
            "CREATE TABLE IF NOT EXISTS stock_adjustments(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,previous_stock REAL NOT NULL CHECK(previous_stock>=0),counted_stock INTEGER NOT NULL CHECK(counted_stock>=0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))"
        ).forEach { sql -> db.createStatement().use { it.execute(sql) } }
        transaction {
            val customerColumns=query("PRAGMA table_info(customers)") { it.getString("name") }
            if("phone" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN phone TEXT") }
            if("address" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN address TEXT") }
            if("credit_limit_minor" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN credit_limit_minor INTEGER CHECK(credit_limit_minor>=0)") }
            val saleColumns=query("PRAGMA table_info(sales)") { it.getString("name") }
            if("due_date" !in saleColumns) db.createStatement().use { it.execute("ALTER TABLE sales ADD COLUMN due_date TEXT") }
            val productColumns=query("PRAGMA table_info(products)") { it.getString("name") }
            if("barcode" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN barcode TEXT") }
            if("archived" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1))") }
            val receiptColumns=query("PRAGMA table_info(stock_receipts)") { it.getString("name") }
            if("base_unit" !in receiptColumns) db.createStatement().use { it.execute("ALTER TABLE stock_receipts ADD COLUMN base_unit TEXT") }
            if("base_units_per_unit" !in receiptColumns) db.createStatement().use { it.execute("ALTER TABLE stock_receipts ADD COLUMN base_units_per_unit INTEGER NOT NULL DEFAULT 1 CHECK(base_units_per_unit>0)") }
            if("purchase_unit_id" !in receiptColumns) db.createStatement().use { it.execute("ALTER TABLE stock_receipts ADD COLUMN purchase_unit_id TEXT") }
            execute("UPDATE stock_receipts SET base_unit=unit WHERE base_unit IS NULL")
            execute("CREATE UNIQUE INDEX IF NOT EXISTS product_barcode_per_tenant ON products(tenant_id,barcode) WHERE barcode IS NOT NULL")
            if(query("PRAGMA table_info(repayments)") { it.getInt("pk") }.count { it>0 }==1) {
                execute("CREATE TABLE repayments_scoped(id TEXT NOT NULL,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))")
                execute("INSERT INTO repayments_scoped SELECT * FROM repayments")
                execute("DROP TABLE repayments")
                execute("ALTER TABLE repayments_scoped RENAME TO repayments")
            }
            if(query("PRAGMA table_info(outbox)") { it.getInt("pk") }.count { it>0 }==1) {
                execute("CREATE TABLE outbox_scoped(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),kind TEXT NOT NULL,entity_id TEXT NOT NULL,created_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',PRIMARY KEY(tenant_id,id))")
                execute("INSERT INTO outbox_scoped SELECT * FROM outbox")
                execute("DROP TABLE outbox")
                execute("ALTER TABLE outbox_scoped RENAME TO outbox")
            }
        }
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
    @Synchronized fun products(tenantId: String, includeArchived:Boolean=false): List<Product> {
        requireTenant(tenantId)
        return query("SELECT * FROM products WHERE tenant_id=?"+(if(includeArchived) "" else " AND archived=0")+" ORDER BY name", tenantId, map = ::product)
    }
    @Synchronized fun customers(tenantId: String): List<Customer> {
        requireTenant(tenantId)
        return query("SELECT * FROM customers WHERE tenant_id=? ORDER BY name", tenantId, map=::customer)
    }
    @Synchronized fun addProduct(tenantId: String, name: String, unit: String, priceMinor: Long, stock: Double, barcode:String?=null): Product = transaction {
        requireTenant(tenantId)
        require(name.isNotBlank() && unit.isNotBlank()) { "Enter the item name and selling unit." }
        require(priceMinor >= 0 && stock.isFinite() && stock >= 0 && stock <= 9_007_199_254_740_991.0) { "Enter a non-negative price and stock within the supported quantity range." }
        Product(id(), tenantId, name.trim(), unit.trim(), priceMinor, stock, normalizeBarcode(barcode)).also {
            requireBarcodeAvailable(tenantId,it.barcode)
            execute("INSERT INTO products(id,tenant_id,name,unit,price_minor,stock,barcode,archived) VALUES(?,?,?,?,?,?,?,0)", it.id,tenantId,it.name,it.unit,it.priceMinor,it.stock,it.barcode)
            enqueue(tenantId,"product.created",it.id)
        }
    }
    @Synchronized fun productByBarcode(tenantId:String,barcode:String):Product? {
        requireTenant(tenantId)
        val code=normalizeBarcode(barcode) ?: return null
        return query("SELECT * FROM products WHERE tenant_id=? AND barcode=? AND archived=0",tenantId,code,map=::product).singleOrNull()
    }
    /** Editing never rewrites a sale's saved name, unit or price. Selling units stay fixed. */
    @Synchronized fun updateProduct(tenantId:String,productId:String,name:String,unit:String,priceMinor:Long,barcode:String?,operationId:String?=null):Product = transaction {
        requireTenant(tenantId)
        require(name.isNotBlank() && unit.isNotBlank() && priceMinor>=0) { "Enter an item name, selling unit and non-negative price." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val code=normalizeBarcode(barcode)
        val item=requireProduct(tenantId,productId)
        val previous=operationId?.let { catalogueChange(tenantId,it) }
        if(previous!=null) {
            require(previous.product.id==productId && previous.kind=="edit" && previous.product.name==name.trim() && previous.product.unit==unit.trim() && previous.product.priceMinor==priceMinor && previous.product.barcode==code) { "This item edit was already saved with different details." }
            return@transaction previous.product.copy(stock=item.stock)
        }
        require(unit.trim()==item.unit) { "Selling units cannot be changed here. Create a separate item to keep quantity history clear." }
        requireBarcodeAvailable(tenantId,code,productId)
        val changed=item.copy(name=name.trim(),priceMinor=priceMinor,barcode=code)
        val changeId=operationId ?: id()
        execute("UPDATE products SET name=?,price_minor=?,barcode=? WHERE tenant_id=? AND id=?",changed.name,priceMinor,code,tenantId,productId)
        recordCatalogueChange(changeId,changed,item.priceMinor,"edit")
        enqueue(tenantId,"product.updated",changeId)
        changed
    }
    @Synchronized fun archiveProduct(tenantId:String,productId:String,archived:Boolean,operationId:String?=null) = transaction {
        requireTenant(tenantId)
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        val previous=operationId?.let { catalogueChange(tenantId,it) }
        if(previous!=null) {
            require(previous.product.id==productId && previous.kind=="archive" && previous.product.archived==archived) { "This archive action was already saved with different details." }
            return@transaction
        }
        val changeId=operationId ?: id()
        execute("UPDATE products SET archived=? WHERE tenant_id=? AND id=?",if(archived) 1 else 0,tenantId,productId)
        recordCatalogueChange(changeId,item.copy(archived=archived),item.priceMinor,"archive")
        enqueue(tenantId,if(archived) "product.archived" else "product.restored",changeId)
    }
    @Synchronized fun priceHistory(tenantId:String,productId:String?=null):List<PriceChange> {
        requireTenant(tenantId)
        if(productId!=null) requireProduct(tenantId,productId)
        return query("SELECT * FROM catalogue_changes WHERE tenant_id=? AND previous_price_minor<>price_minor"+(if(productId!=null) " AND product_id=?" else "")+" ORDER BY created_at DESC",*listOfNotNull(tenantId,productId).toTypedArray()) {
            PriceChange(it.getString("id"),tenantId,it.getString("product_id"),it.getLong("previous_price_minor"),it.getLong("price_minor"),it.getString("created_at"))
        }
    }
    /** A physical count creates an immutable correction. Optional expectedStock catches stale count forms. */
    @Synchronized fun adjustStock(tenantId:String,productId:String,countedStock:Int,reason:String,operationId:String?=null,expectedStock:Double?=null):StockAdjustment = transaction {
        requireTenant(tenantId)
        require(countedStock>=0 && reason.isNotBlank()) { "Enter a non-negative whole count and the reason for this correction." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        if(operationId!=null) {
            val previous=query("SELECT * FROM stock_adjustments WHERE tenant_id=? AND id=?",tenantId,operationId,map=::stockAdjustment).singleOrNull()
            if(previous!=null) {
                require(previous.productId==productId && previous.countedStock==countedStock && previous.reason==reason.trim()) { "This stock count was already saved with different details." }
                return@transaction previous
            }
        }
        require(!item.archived) { "Restore this archived item before correcting its stock." }
        require(expectedStock==null || expectedStock==item.stock) { "Stock changed while this count was open. Reopen the item and check the count again." }
        val adjustment=StockAdjustment(operationId?:id(),tenantId,productId,item.name,item.unit,item.stock,countedStock,reason.trim(),Instant.now().toString())
        execute("INSERT INTO stock_adjustments VALUES(?,?,?,?,?,?,?,?,?)",adjustment.id,tenantId,productId,item.name,item.unit,item.stock,countedStock,adjustment.reason,adjustment.createdAt)
        execute("UPDATE products SET stock=? WHERE tenant_id=? AND id=?",countedStock,tenantId,productId)
        enqueue(tenantId,"stock.adjusted",adjustment.id)
        adjustment
    }
    @Synchronized fun stockAdjustments(tenantId:String,productId:String?=null):List<StockAdjustment> {
        requireTenant(tenantId)
        if(productId!=null) requireProduct(tenantId,productId)
        return query("SELECT * FROM stock_adjustments WHERE tenant_id=?"+(if(productId!=null) " AND product_id=?" else "")+" ORDER BY created_at DESC",*listOfNotNull(tenantId,productId).toTypedArray(),map=::stockAdjustment)
    }
    @Synchronized fun repayments(tenantId:String,customerId:String?=null):List<CustomerRepayment> {
        requireTenant(tenantId)
        if(customerId!=null) require(query("SELECT id FROM customers WHERE tenant_id=? AND id=?",tenantId,customerId) { it.getString(1) }.isNotEmpty()) { "Customer does not belong to this shop." }
        return query("SELECT * FROM repayments WHERE tenant_id=?"+(if(customerId!=null) " AND customer_id=?" else "")+" ORDER BY created_at DESC",*listOfNotNull(tenantId,customerId).toTypedArray()) {
            CustomerRepayment(it.getString("id"),tenantId,it.getString("customer_id"),it.getLong("amount_minor"),it.getString("created_at"))
        }
    }
    @Synchronized fun addCustomer(tenantId: String, name: String, phone:String?=null, address:String?=null, creditLimitMinor:Long?=null): Customer = transaction {
        requireTenant(tenantId)
        require(name.isNotBlank()) { "Enter the customer's name." }
        Customer(id(),tenantId,name.trim(),0,normalizeNigerianPhone(phone),address?.trim()?.takeIf { it.isNotEmpty() },creditLimitMinor).also {
            execute("INSERT INTO customers(id,tenant_id,name,balance_minor,phone,address,credit_limit_minor) VALUES(?,?,?,0,?,?,?)",it.id,tenantId,it.name,it.phone,it.address,it.creditLimitMinor)
            enqueue(tenantId,"customer.created",it.id)
        }
    }
    @Synchronized fun updateCustomer(tenantId:String,customerId:String,name:String,phone:String?,address:String?,creditLimitMinor:Long?,operationId:String?=null):Customer = transaction {
        requireTenant(tenantId)
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val current=requireCustomer(tenantId,customerId)
        val changed=Customer(customerId,tenantId,name.trim(),current.balanceMinor,normalizeNigerianPhone(phone),address?.trim()?.takeIf { it.isNotEmpty() },creditLimitMinor)
        if(operationId!=null) {
            val previous=query("SELECT * FROM customer_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::customerChange).singleOrNull()
            if(previous!=null) {
                require(previous.customerId==customerId && previous.name==changed.name && previous.phone==changed.phone && previous.address==changed.address && previous.creditLimitMinor==changed.creditLimitMinor) { "This customer edit was already saved with different details." }
                return@transaction changed
            }
        }
        val changeId=operationId?:id()
        execute("UPDATE customers SET name=?,phone=?,address=?,credit_limit_minor=? WHERE tenant_id=? AND id=?",changed.name,changed.phone,changed.address,changed.creditLimitMinor,tenantId,customerId)
        execute("INSERT INTO customer_changes VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",changeId,tenantId,customerId,current.name,current.phone,current.address,current.creditLimitMinor,changed.name,changed.phone,changed.address,changed.creditLimitMinor,Instant.now().toString())
        enqueue(tenantId,"customer.updated",changeId)
        changed
    }
    @Synchronized fun customerChanges(tenantId:String,customerId:String?=null):List<CustomerChange> {
        requireTenant(tenantId)
        if(customerId!=null)requireCustomer(tenantId,customerId)
        return query("SELECT * FROM customer_changes WHERE tenant_id=?"+(if(customerId!=null) " AND customer_id=?" else "")+" ORDER BY created_at DESC,id DESC",*listOfNotNull(tenantId,customerId).toTypedArray(),map=::customerChange)
    }
    /** Add-only import. Validate the entire batch before writing; never overwrite existing stock. */
    @Synchronized fun importProducts(tenantId:String, rows:List<ProductImportRow>):List<Product> = transaction {
        requireTenant(tenantId)
        require(rows.isNotEmpty() && rows.size <= 10_000) { "Import between 1 and 10,000 items at a time." }
        fun key(name:String,unit:String) = name.trim().lowercase(java.util.Locale.ROOT) to unit.trim().lowercase(java.util.Locale.ROOT)
        val keys=rows.map { key(it.name,it.unit) }
        require(keys.distinct().size==keys.size) { "Item name and unit combinations must be unique in this file." }
        val existing=products(tenantId,includeArchived=true).map { key(it.name,it.unit) }.toSet()
        require(keys.none { it in existing }) { "Some items already exist in this shop. No items were imported; use Add stock for existing goods." }
        rows.forEach { require(it.name.isNotBlank() && it.unit.isNotBlank() && it.priceMinor>=0 && it.stock>=0) { "Check all item names, units, prices and quantities." } }
        rows.map { addProduct(tenantId,it.name,it.unit,it.priceMinor,it.stock.toDouble()) }
    }
    @Synchronized fun sales(tenantId: String): List<Sale> {
        requireTenant(tenantId)
        return query("SELECT * FROM sales WHERE tenant_id=? ORDER BY created_at DESC",tenantId,map=::sale)
    }
    /** cashMinor is applied cash, excluding change. Caller must explicitly choose a customer for credit. */
    @Synchronized fun checkout(tenantId: String, lines: List<CartLine>, cashMinor: Long, customerId: String? = null, operationId: String? = null, dueDate: String? = null): Sale = transaction {
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
                require(existing.cashMinor==cashMinor && existing.customerId==customerId && existing.dueDate==dueDate && existing.lines.map { it.product.id to Triple(it.quantity,it.product.priceMinor,it.product.unit) }.sortedBy { it.first }==requested.map { it.product.id to Triple(it.quantity,it.product.priceMinor,it.product.unit) }.sortedBy { it.first }) { "This operation was already saved with different details." }
                return@transaction existing
            }
        }
        val normalized = lines.groupBy { it.product.id }.map { (_, group) ->
            require(group.all { it.product.tenantId==tenantId && it.quantity>0 }) { "This item does not belong to this shop, or quantity is invalid." }
            val quantity=group.fold(0) { acc,l -> Math.addExact(acc,l.quantity) }
            val current=query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,group.first().product.id,map=::product).singleOrNull()
                ?: error("Item no longer exists in this shop.")
            require(!current.archived) { "${current.name} has been archived. Remove it from this sale." }
            require(group.all { it.product.priceMinor==current.priceMinor && it.product.unit==current.unit }) { "Item price or unit changed. Review the sale." }
            require(current.stock>=quantity) { "Not enough ${current.unit} of ${current.name} in stock." }
            CartLine(current,quantity)
        }
        val total=normalized.fold(0L) { acc,l -> Math.addExact(acc,Math.multiplyExact(l.product.priceMinor,l.quantity.toLong())) }
        require(cashMinor<=total) { "Record applied cash only; return extra cash as change." }
        val credit=total-cashMinor
        if (customerId!=null) require(query("SELECT id FROM customers WHERE tenant_id=? AND id=?",tenantId,customerId) { it.getString(1) }.isNotEmpty()) { "Customer does not belong to this shop." }
        require(credit==0L || customerId!=null) { "Choose who will pay the remaining balance." }
        if(dueDate!=null) {
            require(credit>0 && isValidCreditDueDate(dueDate)) { "Choose a valid due date in YYYY-MM-DD format for a credit sale." }
            require(!java.time.LocalDate.parse(dueDate).isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")))) { "A new credit sale's due date cannot be before today in West Africa Time." }
        }
        val newDebt=if(credit>0) {
            val customer=requireCustomer(tenantId,requireNotNull(customerId))
            Math.addExact(customer.balanceMinor,credit).also { debt ->
                require(customer.creditLimitMinor?.let { debt<=it } != false) { "This sale would exceed the customer's credit limit of ${customer.creditLimitMinor?.let(::money)}. Reduce the credit or review the customer limit." }
            }
        } else null
        val sale=Sale(operationId ?: id(),tenantId,normalized,total,cashMinor,credit,customerId,Instant.now().toString(),dueDate)
        execute("INSERT INTO sales(id,tenant_id,total_minor,cash_minor,credit_minor,customer_id,created_at,due_date) VALUES(?,?,?,?,?,?,?,?)",sale.id,tenantId,total,cashMinor,credit,customerId,sale.createdAt,dueDate)
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
    @Synchronized fun purchaseUnits(tenantId:String,productId:String?=null,includeArchived:Boolean=false):List<PurchaseUnit> {
        requireTenant(tenantId)
        if(productId!=null) requireProduct(tenantId,productId)
        return query("SELECT * FROM purchase_units WHERE tenant_id=?"+(if(productId!=null) " AND product_id=?" else "")+(if(includeArchived) "" else " AND archived=0")+" ORDER BY name",*listOfNotNull(tenantId,productId).toTypedArray(),map=::purchaseUnit)
    }
    /** Buying factors are immutable: archive an old definition rather than rewriting received history. */
    @Synchronized fun addPurchaseUnit(tenantId:String,productId:String,name:String,baseUnitsPerUnit:Int,operationId:String?=null):PurchaseUnit = transaction {
        requireTenant(tenantId)
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        val candidate=PurchaseUnit(operationId?:id(),tenantId,productId,name.trim(),item.unit,baseUnitsPerUnit,false,Instant.now().toString())
        if(operationId!=null) {
            val previous=query("SELECT * FROM purchase_units WHERE tenant_id=? AND id=?",tenantId,operationId,map=::purchaseUnit).singleOrNull()
            if(previous!=null) {
                require(previous.productId==productId && previous.name==candidate.name && previous.baseUnitsPerUnit==baseUnitsPerUnit) { "This buying unit was already saved with different details." }
                return@transaction previous
            }
        }
        require(!item.archived) { "Restore this item before adding buying units." }
        require(!candidate.name.equals(item.unit,ignoreCase=true)) { "This name is already the selling unit. Use it directly when receiving stock." }
        require(purchaseUnits(tenantId,productId,true).none { it.name.lowercase(java.util.Locale.ROOT)==candidate.name.lowercase(java.util.Locale.ROOT) }) { "This buying unit name is already used for the item, including archived units. Use a different name for a different package size." }
        execute("INSERT INTO purchase_units VALUES(?,?,?,?,?,?,?,?)",candidate.id,tenantId,productId,candidate.name,candidate.baseUnit,baseUnitsPerUnit,0,candidate.createdAt)
        enqueue(tenantId,"purchase_unit.created",candidate.id)
        candidate
    }
    @Synchronized fun archivePurchaseUnit(tenantId:String,unitId:String,archived:Boolean) = transaction {
        requireTenant(tenantId)
        val item=requirePurchaseUnit(tenantId,unitId)
        if(item.archived!=archived) {
            execute("UPDATE purchase_units SET archived=? WHERE tenant_id=? AND id=?",if(archived) 1 else 0,tenantId,unitId)
            enqueue(tenantId,if(archived) "purchase_unit.archived" else "purchase_unit.restored",unitId)
        }
    }
    @Synchronized fun stockReceipts(tenantId: String): List<StockReceipt> {
        requireTenant(tenantId)
        return query("SELECT * FROM stock_receipts WHERE tenant_id=? ORDER BY created_at DESC",tenantId,map=::stockReceipt)
    }
    @Synchronized fun receiveStock(tenantId:String,productId:String,quantity:Int,unitCostMinor:Long,paidCashMinor:Long,supplierName:String,operationId:String?=null,purchaseUnitId:String?=null,expectedBaseUnitsPerUnit:Int?=null):StockReceipt = transaction {
        requireTenant(tenantId)
        require(quantity>0 && unitCostMinor>=0) { "Enter a positive quantity and non-negative buying cost." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val total=Math.multiplyExact(unitCostMinor,quantity.toLong())
        require(paidCashMinor in 0..total) { "Cash paid must be between zero and the purchase total." }
        require(paidCashMinor==total || supplierName.isNotBlank()) { "Enter the supplier name for the unpaid purchase balance." }
        if(operationId!=null) {
            val previous=stockReceipts(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.productId==productId && previous.quantity==quantity && previous.unitCostMinor==unitCostMinor && previous.paidCashMinor==paidCashMinor && previous.supplierName==supplierName.trim() && previous.purchaseUnitId==purchaseUnitId && (expectedBaseUnitsPerUnit==null || previous.baseUnitsPerUnit==expectedBaseUnitsPerUnit)) { "This receipt was already saved with different details." }
                return@transaction previous
            }
        }
        val item=query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,productId,map=::product).singleOrNull()
            ?: throw IllegalArgumentException("Item not found in this shop.")
        require(!item.archived) { "Restore this archived item before adding stock." }
        val buyingUnit=purchaseUnitId?.let { requirePurchaseUnit(tenantId,it) }
        require(buyingUnit==null || (buyingUnit.productId==productId && buyingUnit.baseUnit==item.unit && !buyingUnit.archived)) { "Choose an active buying unit belonging to this item." }
        val factor=buyingUnit?.baseUnitsPerUnit ?: 1
        require(expectedBaseUnitsPerUnit==null || expectedBaseUnitsPerUnit==factor) { "The buying unit does not match the conversion reviewed. Choose it again." }
        val baseQuantity=Math.multiplyExact(quantity.toLong(),factor.toLong())
        require(baseQuantity<=9_007_199_254_740_991L) { "Converted stock quantity exceeds the supported exact range." }
        val exactStock=java.math.BigDecimal.valueOf(item.stock).add(java.math.BigDecimal.valueOf(baseQuantity))
        val newStock=exactStock.toDouble()
        require(newStock.isFinite() && newStock<=9_007_199_254_740_991.0 && java.math.BigDecimal.valueOf(newStock).compareTo(exactStock)==0) { "Stock quantity exceeds the supported exact range." }
        val shift=if(paidCashMinor>0) requireCashShift(tenantId) else null
        if(shift!=null) requireCashAvailable(tenantId,shift.id,paidCashMinor)
        val receipt=StockReceipt(operationId?:id(),tenantId,productId,item.name,buyingUnit?.name ?: item.unit,quantity,unitCostMinor,total,paidCashMinor,supplierName.trim(),Instant.now().toString(),item.unit,factor,purchaseUnitId)
        execute("INSERT INTO stock_receipts(id,tenant_id,product_id,product_name,unit,quantity,unit_cost_minor,total_cost_minor,paid_cash_minor,supplier_name,created_at,base_unit,base_units_per_unit,purchase_unit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",receipt.id,tenantId,productId,item.name,receipt.unit,quantity,unitCostMinor,total,paidCashMinor,receipt.supplierName,receipt.createdAt,receipt.baseUnit,factor,purchaseUnitId)
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
    @Synchronized fun saleReturns(tenantId:String,saleId:String?=null):List<SaleReturn> {
        requireTenant(tenantId)
        if(saleId!=null) requireSale(tenantId,saleId)
        val sql="SELECT * FROM sale_returns WHERE tenant_id=?"+(if(saleId!=null) " AND sale_id=?" else "")+" ORDER BY created_at DESC,id DESC"
        return if(saleId==null) query(sql,tenantId,map=::saleReturn) else query(sql,tenantId,saleId,map=::saleReturn)
    }
    @Synchronized fun returnableQuantities(tenantId:String,saleId:String):Map<String,Int> {
        val sale=requireSale(tenantId,saleId)
        val returned=saleReturns(tenantId,saleId).flatMap { it.lines }.groupBy { it.productId }
        return sale.lines.associate { line ->
            line.product.id to (line.quantity - (returned[line.product.id]?.fold(0) { sum,item -> Math.addExact(sum,item.quantity) } ?: 0))
        }
    }
    @Synchronized fun returnItems(tenantId:String,saleId:String,quantities:Map<String,Int>,restock:Boolean,reason:String,operationId:String?=null):SaleReturn = transaction {
        requireTenant(tenantId)
        require(reason.isNotBlank() && quantities.isNotEmpty() && quantities.all { it.key.isNotBlank() && it.value>0 }) { "Select returned quantities and enter a reason." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val sale=requireSale(tenantId,saleId)
        require(sale.creditMinor==0L && sale.cashMinor==sale.totalMinor) { "Credit-sale returns are not supported yet. No refund or stock change has been recorded." }
        if(operationId!=null) {
            val previous=saleReturns(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.saleId==saleId && previous.restock==restock && previous.reason==reason.trim() && previous.lines.associate { it.productId to it.quantity }==quantities) { "This return was already recorded with different details." }
                return@transaction previous
            }
        }
        val remaining=returnableQuantities(tenantId,saleId)
        require(quantities.all { (productId,quantity) -> quantity <= (remaining[productId] ?: 0) }) { "A return cannot exceed the quantity still returnable on this receipt." }
        val lines=quantities.entries.sortedBy { it.key }.map { (productId,quantity) ->
            val original=sale.lines.single { it.product.id==productId }
            ReturnLine(productId,original.product.name,original.product.unit,quantity,original.product.priceMinor)
        }
        val refund=lines.fold(0L) { total,line -> Math.addExact(total,Math.multiplyExact(line.unitPriceMinor,line.quantity.toLong())) }
        val shift=requireCashShift(tenantId)
        requireCashAvailable(tenantId,shift.id,refund)
        // Validate all stock updates before mutating the ledger; damaged goods never re-enter saleable stock.
        val stockUpdates=if(restock) lines.map { line ->
            val item=query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,line.productId,map=::product).singleOrNull()
                ?: throw IllegalArgumentException("Returned item is missing from this shop's stock records.")
            require(item.unit==line.unit) { "Selling unit changed since the sale. This return needs a reviewed stock correction." }
            val updated=item.stock+line.quantity
            require(updated.isFinite() && updated<=9_007_199_254_740_991.0) { "Returned stock exceeds the supported quantity range." }
            line.productId to updated
        } else emptyList()
        val result=SaleReturn(operationId?:id(),tenantId,saleId,lines,refund,restock,reason.trim(),Instant.now().toString())
        execute("INSERT INTO sale_returns VALUES(?,?,?,?,?,?,?)",result.id,tenantId,saleId,refund,if(restock) 1 else 0,result.reason,result.createdAt)
        lines.forEach { line -> execute("INSERT INTO return_lines(return_id,tenant_id,product_id,name,unit,quantity,unit_price_minor) VALUES(?,?,?,?,?,?,?)",result.id,tenantId,line.productId,line.name,line.unit,line.quantity,line.unitPriceMinor) }
        stockUpdates.forEach { (productId,quantity) -> execute("UPDATE products SET stock=? WHERE tenant_id=? AND id=?",quantity,tenantId,productId) }
        if(refund>0) appendCash(tenantId,shift.id,"refund",-refund,"Return for sale ${saleId.take(8)}: ${result.reason}",result.id)
        enqueue(tenantId,"sale.returned",result.id)
        result
    }
    @Synchronized fun supplierPayments(tenantId:String,receiptId:String?=null):List<SupplierPayment> {
        requireTenant(tenantId)
        if(receiptId!=null) requireStockReceipt(tenantId,receiptId)
        val sql="SELECT * FROM supplier_payments WHERE tenant_id=?"+(if(receiptId!=null) " AND receipt_id=?" else "")+" ORDER BY created_at DESC,id DESC"
        return if(receiptId==null) query(sql,tenantId,map=::supplierPayment) else query(sql,tenantId,receiptId,map=::supplierPayment)
    }
    @Synchronized fun supplierOutstanding(tenantId:String,receiptId:String):Long {
        val receipt=requireStockReceipt(tenantId,receiptId)
        val paid=supplierPayments(tenantId,receiptId).fold(0L) { total,payment -> Math.addExact(total,payment.amountMinor) }
        return Math.subtractExact(receipt.unpaidMinor,paid).also { require(it>=0) { "Supplier payment history exceeds the receipt balance." } }
    }
    @Synchronized fun paySupplierReceipt(tenantId:String,receiptId:String,amountMinor:Long,operationId:String?=null):SupplierPayment = transaction {
        requireTenant(tenantId)
        require(amountMinor>0) { "Enter a supplier payment greater than zero." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val receipt=requireStockReceipt(tenantId,receiptId)
        if(operationId!=null) {
            val previous=supplierPayments(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.receiptId==receiptId && previous.amountMinor==amountMinor) { "This supplier payment was already recorded with different details." }
                return@transaction previous
            }
        }
        require(amountMinor<=supplierOutstanding(tenantId,receiptId)) { "Payment exceeds what is still owed on this stock receipt." }
        val shift=requireCashShift(tenantId)
        requireCashAvailable(tenantId,shift.id,amountMinor)
        val result=SupplierPayment(operationId?:id(),tenantId,receiptId,receipt.supplierName,amountMinor,Instant.now().toString())
        execute("INSERT INTO supplier_payments VALUES(?,?,?,?,?,?)",result.id,tenantId,receiptId,receipt.supplierName,amountMinor,result.createdAt)
        appendCash(tenantId,shift.id,"supplier_payment",-amountMinor,"Payment to ${receipt.supplierName}",result.id)
        enqueue(tenantId,"supplier.payment",result.id)
        result
    }
    private fun sale(r:ResultSet):Sale {
        val tenantId=r.getString("tenant_id"); val saleId=r.getString("id")
        val lines=query("SELECT * FROM sale_lines WHERE tenant_id=? AND sale_id=? ORDER BY id",tenantId,saleId) { line ->
            CartLine(Product(line.getString("product_id"),tenantId,line.getString("name"),line.getString("unit"),line.getLong("price_minor"),0.0),line.getInt("quantity"))
        }
        return Sale(saleId,tenantId,lines,r.getLong("total_minor"),r.getLong("cash_minor"),r.getLong("credit_minor"),r.getString("customer_id"),r.getString("created_at"),r.getString("due_date"))
    }
    private fun requireSale(tenantId:String,saleId:String):Sale {
        requireTenant(tenantId)
        return query("SELECT * FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId,map=::sale).singleOrNull() ?: throw IllegalArgumentException("Sale not found in this shop.")
    }
    private fun requireStockReceipt(tenantId:String,receiptId:String):StockReceipt {
        requireTenant(tenantId)
        return query("SELECT * FROM stock_receipts WHERE tenant_id=? AND id=?",tenantId,receiptId,map=::stockReceipt).singleOrNull()
            ?: throw IllegalArgumentException("Stock receipt not found in this shop.")
    }
    private fun saleReturn(r:ResultSet):SaleReturn {
        val tenantId=r.getString("tenant_id"); val returnId=r.getString("id")
        val lines=query("SELECT * FROM return_lines WHERE tenant_id=? AND return_id=? ORDER BY id",tenantId,returnId) { line -> ReturnLine(line.getString("product_id"),line.getString("name"),line.getString("unit"),line.getInt("quantity"),line.getLong("unit_price_minor")) }
        return SaleReturn(returnId,tenantId,r.getString("sale_id"),lines,r.getLong("refund_minor"),r.getInt("restock")==1,r.getString("reason"),r.getString("created_at"))
    }
    private fun supplierPayment(r:ResultSet)=SupplierPayment(r.getString("id"),r.getString("tenant_id"),r.getString("receipt_id"),r.getString("supplier_name"),r.getLong("amount_minor"),r.getString("created_at"))

    private val snapshotTableOrder get() = listOf("products","purchase_units","customers","customer_changes","cash_shifts","sales","sale_lines","repayments","stock_receipts","expenses","sale_returns","return_lines","supplier_payments","cash_entries","catalogue_changes","stock_adjustments","outbox")
    @Synchronized fun exportTenantSnapshot(tenantId:String):TenantSnapshot = transaction {
        requireTenant(tenantId)
        val tenant=tenants().single { it.id==tenantId }
        val tables=snapshotTableOrder.associateWith { table ->
            query("SELECT * FROM $table WHERE tenant_id=?",tenantId) { row ->
                (1..row.metaData.columnCount).associate { column -> row.metaData.getColumnName(column) to row.getString(column) }
            }
        }
        TenantSnapshot(tenant,tables)
    }
    @Synchronized fun restoreTenantSnapshot(snapshot:TenantSnapshot,newName:String,provisionOwner:(Tenant)->Unit={}):Tenant = transaction {
        require(newName.isNotBlank()) { "Enter a name for the restored shop." }
        require(snapshot.tables.keys.all { it in snapshotTableOrder }) { "Backup contains an unsupported record table." }
        val tenant=Tenant(id(),newName.trim())
        // Validate the complete payload before creating even the tenant row.
        snapshotTableOrder.forEach { table ->
            val schema=query("PRAGMA table_info($table)") { Triple(it.getString("name"),it.getString("type"),it.getInt("notnull")==1) }.associateBy { it.first }
            snapshot.tables[table].orEmpty().forEach { row ->
                require(row.keys.all { it in schema.keys }) { "Backup contains an unsupported column in $table." }
                require(row["tenant_id"]==snapshot.tenant.id) { "Backup contains records from another shop." }
                schema.values.forEach { (name,type,required) ->
                    val value=row[name]
                    val generatedId=(table=="sale_lines" || table=="return_lines") && name=="id"
                    require(!required || value!=null || generatedId || (table=="products" && name=="archived" && name !in row) || (table=="stock_receipts" && name=="base_units_per_unit" && name !in row)) { "Backup is missing a required value in $table." }
                    if(value!=null && type=="INTEGER") require(value.toLongOrNull()!=null) { "Backup has invalid integer data in $table." }
                    if(value!=null && name=="quantity" && table in setOf("sale_lines","stock_receipts","return_lines")) {
                        val quantity=value.toLongOrNull()
                        require(quantity!=null && quantity in 1L..Int.MAX_VALUE.toLong()) { "Backup quantity exceeds the supported integer range." }
                    }
                    if(value!=null && name=="base_units_per_unit") require(value.toLongOrNull() in 1L..Int.MAX_VALUE.toLong()) { "Backup buying unit factor exceeds the supported integer range." }
                    if(value!=null && name=="counted_stock") require(value.toLongOrNull() in 0L..Int.MAX_VALUE.toLong()) { "Backup counted stock exceeds the supported integer range." }
                    if(value!=null && name=="previous_stock") require(value.toDoubleOrNull()?.let { it in 0.0..9_007_199_254_740_991.0 }==true) { "Backup previous stock exceeds the supported range." }
                    if(value!=null && type=="REAL") require(value.toDoubleOrNull()?.isFinite()==true) { "Backup has invalid numeric data in $table." }
                    if(value!=null && name=="stock") require(value.toDouble() in 0.0..9_007_199_254_740_991.0) { "Backup stock exceeds supported range." }
                }
            }
        }
        execute("INSERT INTO tenants VALUES(?,?)",tenant.id,tenant.name)
        snapshotTableOrder.forEach { table ->
            snapshot.tables[table].orEmpty().forEach { row ->
                val data=row.filterKeys { !((table=="sale_lines" || table=="return_lines") && it=="id") }.toMutableMap()
                data["tenant_id"]=tenant.id
                if(table=="stock_receipts" && "base_unit" !in data) data["base_unit"]=data["unit"]
                val columns=data.keys.toList()
                execute("INSERT INTO $table (${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})",*columns.map { data[it] }.toTypedArray())
            }
        }
        // Reconstruct domain records to enforce cross-field invariants as well as SQL constraints.
        products(tenant.id,includeArchived=true); customers(tenant.id); sales(tenant.id); stockReceipts(tenant.id)
        stockAdjustments(tenant.id); priceHistory(tenant.id); repayments(tenant.id); customerChanges(tenant.id)
        val restoredUnits=purchaseUnits(tenant.id,includeArchived=true)
        restoredUnits.forEach { unit -> require(unit.baseUnit==requireProduct(tenant.id,unit.productId).unit) { "Backup buying unit does not match the item's selling unit." } }
        require(restoredUnits.map { it.productId to it.name.lowercase(java.util.Locale.ROOT) }.distinct().size==restoredUnits.size) { "Backup contains duplicate buying unit names." }
        stockReceipts(tenant.id).forEach { receipt ->
            require(receipt.baseUnit==requireProduct(tenant.id,receipt.productId).unit) { "Backup stock receipt uses a different selling unit." }
            receipt.purchaseUnitId?.let { unitId ->
                val unit=requirePurchaseUnit(tenant.id,unitId)
                require(unit.productId==receipt.productId && unit.name==receipt.unit && unit.baseUnit==receipt.baseUnit && unit.baseUnitsPerUnit==receipt.baseUnitsPerUnit) { "Backup stock receipt conversion does not match its buying unit." }
            }
        }
        query("SELECT * FROM catalogue_changes WHERE tenant_id=?",tenant.id) { r -> Product(r.getString("product_id"),tenant.id,r.getString("name"),r.getString("unit"),r.getLong("price_minor"),0.0,r.getString("barcode"),r.getInt("archived")==1) }
        expenses(tenant.id); cashShifts(tenant.id); cashEntries(tenant.id); saleReturns(tenant.id); supplierPayments(tenant.id)
        customers(tenant.id).forEach { customer ->
            val granted=sales(tenant.id).filter { it.customerId==customer.id }.fold(java.math.BigInteger.ZERO) { sum,sale -> sum.add(java.math.BigInteger.valueOf(sale.creditMinor)) }
            val paid=query("SELECT amount_minor FROM repayments WHERE tenant_id=? AND customer_id=?",tenant.id,customer.id) { it.getLong(1) }.fold(java.math.BigInteger.ZERO) { sum,amount -> sum.add(java.math.BigInteger.valueOf(amount)) }
            val expected=granted.subtract(paid)
            require(expected.signum()>=0 && expected==java.math.BigInteger.valueOf(customer.balanceMinor)) { "Backup customer balance does not match credit sales and repayments." }
        }
        val expectedCashSources=linkedMapOf<Pair<String,String>,Long>()
        fun sources(table:String,kind:String,amountColumn:String,outflow:Boolean=false) {
            query("SELECT id,$amountColumn FROM $table WHERE tenant_id=? AND $amountColumn>0",tenant.id) { it.getString(1) to it.getLong(2) }.forEach { (source,amount) ->
                expectedCashSources[kind to source]=if(outflow) Math.negateExact(amount) else amount
            }
        }
        sources("sales","sale","cash_minor");sources("repayments","repayment","amount_minor")
        sources("expenses","expense","amount_minor",true);sources("stock_receipts","stock_purchase","paid_cash_minor",true)
        sources("sale_returns","refund","refund_minor",true);sources("supplier_payments","supplier_payment","amount_minor",true)
        val actualSources=linkedMapOf<Pair<String,String>,Long>()
        query("SELECT kind,source_id,amount_minor,shift_id FROM cash_entries WHERE tenant_id=?",tenant.id) { row ->
            val kind=row.getString(1)
            if(kind!="sale" && kind!="repayment") require(row.getString(4)!=null) { "Backup cash payouts or manual movements must belong to a shift." }
            if(kind!="manual") actualSources[kind to row.getString(2)]=row.getLong(3)
        }
        require(actualSources==expectedCashSources) { "Backup cash movements do not match their source records." }
        saleReturns(tenant.id).forEach { returned ->
            val original=requireSale(tenant.id,returned.saleId)
            require(original.creditMinor==0L && returned.lines.all { line -> original.lines.any { it.product.id==line.productId && it.product.name==line.name && it.product.unit==line.unit && it.product.priceMinor==line.unitPriceMinor } }) { "Backup return does not match its original cash sale." }
        }
        sales(tenant.id).forEach { sale -> require(returnableQuantities(tenant.id,sale.id).values.all { it>=0 }) { "Backup returns exceed original quantities." } }
        stockReceipts(tenant.id).forEach { supplierOutstanding(tenant.id,it.id) }
        cashShifts(tenant.id).forEach { shift ->
            val calculated=cashEntries(tenant.id,shift.id).fold(java.math.BigInteger.valueOf(shift.openingMinor)) { total,entry -> total.add(java.math.BigInteger.valueOf(entry.amountMinor)) }.longValueExact()
            require(calculated>=0 && (shift.expectedMinor==null || shift.expectedMinor==calculated)) { "Backup cash count snapshot does not match its ledger." }
        }
        provisionOwner(tenant)
        tenant
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
    private fun stockReceipt(r:ResultSet)=StockReceipt(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getInt("quantity"),r.getLong("unit_cost_minor"),r.getLong("total_cost_minor"),r.getLong("paid_cash_minor"),r.getString("supplier_name"),r.getString("created_at"),r.getString("base_unit") ?: r.getString("unit"),r.getInt("base_units_per_unit"),r.getString("purchase_unit_id"))
    private fun purchaseUnit(r:ResultSet)=PurchaseUnit(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("name"),r.getString("base_unit"),r.getInt("base_units_per_unit"),r.getInt("archived")==1,r.getString("created_at"))
    private fun requirePurchaseUnit(tenantId:String,unitId:String):PurchaseUnit=query("SELECT * FROM purchase_units WHERE tenant_id=? AND id=?",tenantId,unitId,map=::purchaseUnit).singleOrNull() ?: throw IllegalArgumentException("Buying unit not found in this shop.")
    private fun expense(r:ResultSet)=Expense(r.getString("id"),r.getString("tenant_id"),r.getString("category"),r.getString("description"),r.getLong("amount_minor"),r.getString("created_at"))
    private fun cashShift(r:ResultSet)=CashShift(r.getString("id"),r.getString("tenant_id"),r.getLong("opening_minor"),r.getString("opened_at"),r.getString("closed_at"),r.getObject("expected_minor")?.let { (it as Number).toLong() },r.getObject("counted_minor")?.let { (it as Number).toLong() })
    private fun cashEntry(r:ResultSet)=CashEntry(r.getString("id"),r.getString("tenant_id"),r.getString("shift_id"),r.getString("kind"),r.getLong("amount_minor"),r.getString("description"),r.getString("created_at"))

    private fun normalizeBarcode(barcode:String?):String? = barcode?.trim()?.takeIf { it.isNotEmpty() }?.also { code ->
        require(code.length<=128 && code.none { it.isISOControl() }) { "Use a barcode of at most 128 printable characters." }
    }
    private fun requireBarcodeAvailable(tenantId:String,barcode:String?,exceptId:String?=null) {
        if(barcode!=null) require(query("SELECT id FROM products WHERE tenant_id=? AND barcode=?",tenantId,barcode) { it.getString(1) }.all { it==exceptId }) { "This barcode is already assigned to another item in this shop, including archived items." }
    }
    private fun customer(r:ResultSet)=Customer(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getLong("balance_minor"),r.getString("phone"),r.getString("address"),r.getObject("credit_limit_minor")?.let { (it as Number).toLong() })
    private fun requireCustomer(tenantId:String,customerId:String):Customer=query("SELECT * FROM customers WHERE tenant_id=? AND id=?",tenantId,customerId,map=::customer).singleOrNull() ?: throw IllegalArgumentException("Customer not found in this shop.")
    private fun customerChange(r:ResultSet)=CustomerChange(r.getString("id"),r.getString("tenant_id"),r.getString("customer_id"),r.getString("previous_name"),r.getString("previous_phone"),r.getString("previous_address"),r.getObject("previous_credit_limit_minor")?.let { (it as Number).toLong() },r.getString("name"),r.getString("phone"),r.getString("address"),r.getObject("credit_limit_minor")?.let { (it as Number).toLong() },r.getString("created_at"))
    private fun requireProduct(tenantId:String,productId:String):Product = query("SELECT * FROM products WHERE tenant_id=? AND id=?",tenantId,productId,map=::product).singleOrNull() ?: throw IllegalArgumentException("Item not found in this shop.")
    private data class CatalogueChange(val product:Product,val kind:String)
    private fun catalogueChange(tenantId:String,operationId:String):CatalogueChange? = query("SELECT * FROM catalogue_changes WHERE tenant_id=? AND id=?",tenantId,operationId) { r ->
        CatalogueChange(Product(r.getString("product_id"),tenantId,r.getString("name"),r.getString("unit"),r.getLong("price_minor"),0.0,r.getString("barcode"),r.getInt("archived")==1),r.getString("kind"))
    }.singleOrNull()
    private fun recordCatalogueChange(changeId:String,item:Product,previousPriceMinor:Long,kind:String) {
        execute("INSERT INTO catalogue_changes VALUES(?,?,?,?,?,?,?,?,?,?,?)",changeId,item.tenantId,item.id,kind,item.name,item.unit,item.priceMinor,previousPriceMinor,item.barcode,if(item.archived) 1 else 0,Instant.now().toString())
    }
    private fun stockAdjustment(r:ResultSet)=StockAdjustment(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getDouble("previous_stock"),r.getInt("counted_stock"),r.getString("reason"),r.getString("created_at"))
    private fun requireTenant(tenantId: String) { require(query("SELECT id FROM tenants WHERE id=?",tenantId) { it.getString(1) }.isNotEmpty()) { "Shop not found." } }
    private fun product(r: ResultSet)=Product(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getString("unit"),r.getLong("price_minor"),r.getDouble("stock"),r.getString("barcode"),r.getInt("archived")==1)
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
