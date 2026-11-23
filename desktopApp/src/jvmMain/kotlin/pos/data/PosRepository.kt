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
    // A null callback is for trusted local tooling/tests. The desktop installs a genuine-session
    // validator, including while locked; this adapter is not a server authorization boundary.
    private var permissionCheck: ((String, StaffPermission) -> Unit)? = null
    private var actorProvider: ((String) -> AuditActor?)? = null

    /** Desktop wiring supplies a genuine current identity; trusted tools can be unattributed. */
    @Synchronized fun setActorProvider(provider: ((String) -> AuditActor?)?) {
        actorProvider = provider
    }

    @Synchronized fun setPermissionCheck(check: ((String, StaffPermission) -> Unit)?) {
        permissionCheck = check
    }

    private fun requirePermission(tenantId: String, permission: StaffPermission) {
        permissionCheck?.invoke(tenantId, permission)
    }

    /** Feature modules share this connection, transaction, session guard and actor history. */
    @Synchronized internal fun <T> featureWrite(tenantId:String,permission:StaffPermission,block:PosRepository.()->T):T = transaction {
        requirePermission(tenantId,permission)
        requireTenant(tenantId)
        block()
    }
    @Synchronized internal fun <T> featureRead(tenantId:String,permission:StaffPermission,block:PosRepository.()->T):T = transaction {
        requirePermission(tenantId,permission)
        requireTenant(tenantId)
        block()
    }
    internal fun featureExecute(sql:String,vararg args:Any?):Int = execute(sql,*args)
    internal fun featureSchema(sql:String) { db.createStatement().use { it.execute(sql) } }
    internal fun <T> featureQuery(sql:String,vararg args:Any?,map:(ResultSet)->T):List<T> = query(sql,*args,map=map)
    internal fun featureEvent(tenantId:String,kind:String,entityId:String) = enqueue(tenantId,kind,entityId)
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
        execute("CREATE TABLE IF NOT EXISTS selling_units(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,name TEXT NOT NULL,base_unit TEXT NOT NULL,base_units_per_unit INTEGER NOT NULL CHECK(base_units_per_unit>0),price_minor INTEGER NOT NULL CHECK(price_minor>=0),barcode TEXT,archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
        transaction {
            val cashShiftColumns=query("PRAGMA table_info(cash_shifts)") { it.getString("name") }
            listOf("opening_count_sheet TEXT", "closing_count_sheet TEXT", "closing_blind INTEGER NOT NULL DEFAULT 0 CHECK(closing_blind IN(0,1))").forEach { definition ->
                if(definition.substringBefore(" ") !in cashShiftColumns) db.createStatement().use { it.execute("ALTER TABLE cash_shifts ADD COLUMN $definition") }
            }
            mapOf("sale_lines" to listOf("discount_per_unit_minor INTEGER NOT NULL DEFAULT 0 CHECK(discount_per_unit_minor>=0)", "discount_reason TEXT", "selling_unit_id TEXT", "selling_unit TEXT", "unit_price_minor INTEGER", "base_units_per_unit INTEGER NOT NULL DEFAULT 1 CHECK(base_units_per_unit>0)"), "return_lines" to listOf("selling_unit_id TEXT", "base_unit TEXT", "base_units_per_unit INTEGER NOT NULL DEFAULT 1 CHECK(base_units_per_unit>0)")).forEach { (table,definitions) ->
                val columns=query("PRAGMA table_info($table)") { it.getString("name") }
                definitions.forEach { definition -> if(definition.substringBefore(" ") !in columns) db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $definition") } }
            }
            execute("UPDATE sale_lines SET selling_unit=unit WHERE selling_unit IS NULL")
            execute("UPDATE sale_lines SET unit_price_minor=price_minor WHERE unit_price_minor IS NULL")
            execute("UPDATE return_lines SET base_unit=unit WHERE base_unit IS NULL")
            val customerColumns=query("PRAGMA table_info(customers)") { it.getString("name") }
            if("phone" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN phone TEXT") }
            if("address" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN address TEXT") }
            if("credit_limit_minor" !in customerColumns) db.createStatement().use { it.execute("ALTER TABLE customers ADD COLUMN credit_limit_minor INTEGER CHECK(credit_limit_minor>=0)") }
            val saleColumns=query("PRAGMA table_info(sales)") { it.getString("name") }
            if("due_date" !in saleColumns) db.createStatement().use { it.execute("ALTER TABLE sales ADD COLUMN due_date TEXT") }
            val productColumns=query("PRAGMA table_info(products)") { it.getString("name") }
            if("barcode" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN barcode TEXT") }
            if("archived" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1))") }
            if("reorder_level" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN reorder_level INTEGER CHECK(reorder_level>=0)") }
            if("opening_stock" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN opening_stock REAL CHECK(opening_stock>=0)") }
            if("created_at" !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN created_at TEXT") }
            listOf("sku TEXT", "category TEXT", "brand TEXT", "aliases TEXT NOT NULL DEFAULT ''", "favourite INTEGER NOT NULL DEFAULT 0 CHECK(favourite IN(0,1))").forEach { definition ->
                if(definition.substringBefore(" ") !in productColumns) db.createStatement().use { it.execute("ALTER TABLE products ADD COLUMN $definition") }
            }
            execute("CREATE UNIQUE INDEX IF NOT EXISTS product_sku_per_tenant ON products(tenant_id,sku COLLATE NOCASE) WHERE sku IS NOT NULL")
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
        // Create after the legacy repayment-key migration so every reference has a scoped parent key.
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS repayment_allocations(id TEXT NOT NULL,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,repayment_id TEXT NOT NULL,sale_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id),FOREIGN KEY(tenant_id,repayment_id) REFERENCES repayments(tenant_id,id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))") }
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS allocation_reversals(id TEXT NOT NULL,tenant_id TEXT NOT NULL,customer_id TEXT NOT NULL,allocation_id TEXT NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,allocation_id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id),FOREIGN KEY(tenant_id,allocation_id) REFERENCES repayment_allocations(tenant_id,id))") }
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS stock_settings_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,previous_reorder_level INTEGER CHECK(previous_reorder_level>=0),reorder_level INTEGER CHECK(reorder_level>=0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))") }
        // Legacy cash stays unassigned: a new shift's opening count already includes physical cash.
        transaction {
            execute("INSERT OR IGNORE INTO cash_entries(id,tenant_id,shift_id,kind,amount_minor,description,created_at,source_id) SELECT 'legacy-sale-'||id,tenant_id,NULL,'sale',cash_minor,'Cash sale '||substr(id,1,8),created_at,id FROM sales WHERE cash_minor>0")
            execute("INSERT OR IGNORE INTO cash_entries(id,tenant_id,shift_id,kind,amount_minor,description,created_at,source_id) SELECT 'legacy-repayment-'||id,tenant_id,NULL,'repayment',amount_minor,'Customer repayment',created_at,id FROM repayments WHERE amount_minor>0")
        }
        // This table is created only after the legacy outbox key has been migrated.
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS activity_audit(id TEXT NOT NULL,tenant_id TEXT NOT NULL,kind TEXT NOT NULL,entity_id TEXT NOT NULL,created_at TEXT NOT NULL,actor_id TEXT,actor_name TEXT,actor_role TEXT CHECK(actor_role IN('OWNER','MANAGER','CASHIER')),description TEXT,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,id) REFERENCES outbox(tenant_id,id))") }
        createMetadataHistory()
        initializeSupplierProfiles()
        initializePurchaseOrders()
        initializeInventoryDispositions()
        initializeStockCountSessions()
        initializeShopProfiles()
        initializeExternalPayments()
        initializePriceLists()
        initializeBulkCatalogueUpdates()
        initializeSupplierInvoices()
        initializeCreditReturns()
        initializeQuotations()
        initializeLocalImages()
        initializeOwnerCash()
        initializeMinimumPrices()
        initializeRepacking()
        initializeExpenseAttachments()
        initializeSaleTender()
        initializeSaleNotes()
        initializeFrequentItems()
        initializeRepaymentReminders()
        initializeProductVariants()
        initializePurchaseReturns()
        initializeDebtAdjustments()
        initializeCashExchanges()
        execute("CREATE TRIGGER IF NOT EXISTS activity_audit_no_update BEFORE UPDATE ON activity_audit BEGIN SELECT RAISE(ABORT,'Activity history cannot be edited'); END")
        execute("CREATE TRIGGER IF NOT EXISTS activity_audit_no_delete BEFORE DELETE ON activity_audit BEGIN SELECT RAISE(ABORT,'Activity history cannot be removed'); END")
    }

    private fun createMetadataHistory() {
        transaction {
            val additions=mapOf(
                "products" to listOf("quantity_precision INTEGER NOT NULL DEFAULT 0 CHECK(quantity_precision BETWEEN 0 AND 6)","stock_scaled INTEGER CHECK(stock_scaled>=0)"),
                "sales" to listOf("basket_discount_kind TEXT", "basket_discount_value INTEGER", "basket_discount_reason TEXT"),
                "sale_lines" to listOf("basket_discount_minor INTEGER NOT NULL DEFAULT 0 CHECK(basket_discount_minor>=0)","quantity_precision INTEGER NOT NULL DEFAULT 0 CHECK(quantity_precision BETWEEN 0 AND 6)","base_precision INTEGER NOT NULL DEFAULT 0 CHECK(base_precision BETWEEN 0 AND 6)"),
                "stock_receipts" to listOf("quantity_precision INTEGER NOT NULL DEFAULT 0 CHECK(quantity_precision BETWEEN 0 AND 6)","base_precision INTEGER NOT NULL DEFAULT 0 CHECK(base_precision BETWEEN 0 AND 6)"),
                "return_lines" to listOf("quantity_precision INTEGER NOT NULL DEFAULT 0 CHECK(quantity_precision BETWEEN 0 AND 6)","base_precision INTEGER NOT NULL DEFAULT 0 CHECK(base_precision BETWEEN 0 AND 6)","refund_override_minor INTEGER CHECK(refund_override_minor>=0)"),
                "stock_adjustments" to listOf("quantity_precision INTEGER NOT NULL DEFAULT 0 CHECK(quantity_precision BETWEEN 0 AND 6)")
            )
            additions.forEach { (table,definitions) ->
                val columns=query("PRAGMA table_info($table)") { it.getString("name") }
                definitions.forEach { definition -> if(definition.substringBefore(" ") !in columns) db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $definition") } }
            }
            execute("UPDATE products SET stock_scaled=CAST(stock AS INTEGER) WHERE stock_scaled IS NULL AND quantity_precision=0 AND stock=CAST(stock AS INTEGER)")
        }
        execute("CREATE TABLE IF NOT EXISTS product_metadata_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,previous_sku TEXT,previous_category TEXT,previous_brand TEXT,previous_aliases TEXT NOT NULL,previous_favourite INTEGER NOT NULL CHECK(previous_favourite IN(0,1)),sku TEXT,category TEXT,brand TEXT,aliases TEXT NOT NULL,favourite INTEGER NOT NULL CHECK(favourite IN(0,1)),created_at TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,sequence),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
        execute("CREATE TRIGGER IF NOT EXISTS metadata_history_no_update BEFORE UPDATE ON product_metadata_changes BEGIN SELECT RAISE(ABORT,'Catalogue history cannot be edited'); END")
        execute("CREATE TRIGGER IF NOT EXISTS metadata_history_no_delete BEFORE DELETE ON product_metadata_changes BEGIN SELECT RAISE(ABORT,'Catalogue history cannot be removed'); END")
    }

    @Synchronized fun updateProductMetadata(tenantId:String,productId:String,metadata:ProductMetadata,operationId:String,expected:ProductMetadata?=null):Product = transaction {
        requirePermission(tenantId,StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        val previous=query("SELECT * FROM product_metadata_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::metadataChange).singleOrNull()
        if(previous!=null) {
            require(previous.productId==productId && previous.current==metadata) { "This catalogue edit was already saved with different details." }
            return@transaction item
        }
        require(!item.archived) { "Restore this item before changing catalogue details." }
        require(expected==null || expected==item.metadata()) { "Catalogue details changed since this form was opened. Reopen the item and review them." }
        if(metadata==item.metadata()) return@transaction item
        if(metadata.sku!=null) require(query("SELECT id FROM products WHERE tenant_id=? AND sku=? COLLATE NOCASE AND id<>?",tenantId,metadata.sku,productId) {it.getString(1)}.isEmpty()) { "This SKU is already used by another item in this shop, including archived items." }
        val old=item.metadata()
        execute("UPDATE products SET sku=?,category=?,brand=?,aliases=?,favourite=? WHERE tenant_id=? AND id=?",metadata.sku,metadata.category,metadata.brand,encodeProductAliases(metadata.aliases),if(metadata.favourite)1 else 0,tenantId,productId)
        execute("INSERT INTO product_metadata_changes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",operationId,tenantId,productId,old.sku,old.category,old.brand,encodeProductAliases(old.aliases),if(old.favourite)1 else 0,metadata.sku,metadata.category,metadata.brand,encodeProductAliases(metadata.aliases),if(metadata.favourite)1 else 0,Instant.now().toString(),productMetadataChanges(tenantId,productId).size.toLong()+1)
        enqueue(tenantId,"product.metadata_changed",operationId)
        requireProduct(tenantId,productId)
    }
    @Synchronized fun productMetadataChanges(tenantId:String,productId:String):List<ProductMetadataChange> {
        requireTenant(tenantId); requireProduct(tenantId,productId)
        return query("SELECT * FROM product_metadata_changes WHERE tenant_id=? AND product_id=? ORDER BY sequence",tenantId,productId,map=::metadataChange)
    }
    private fun metadataChange(row:ResultSet):ProductMetadataChange {
        fun fields(prefix:String)=ProductMetadata(row.getString(prefix+"sku"),row.getString(prefix+"category"),row.getString(prefix+"brand"),decodeProductAliases(row.getString(prefix+"aliases")),row.getInt(prefix+"favourite")==1)
        return ProductMetadataChange(row.getString("id"),row.getString("tenant_id"),row.getString("product_id"),fields("previous_"),fields(""),row.getString("created_at"),row.getLong("sequence"))
    }

    /** New writes only: legacy events are not retrospectively attributed to the current owner. */
    @Synchronized fun auditEvents(tenantId: String): List<AuditEvent> {
        requirePermission(tenantId, StaffPermission.VIEW_REPORTS)
        requireTenant(tenantId)
        return query("SELECT * FROM activity_audit WHERE tenant_id=? ORDER BY created_at DESC,id DESC", tenantId, map=::auditEvent)
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
    @Synchronized fun addProduct(tenantId: String, name: String, unit: String, priceMinor: Long, stock: Double, barcode:String?=null, quantityPrecision:Int=0): Product = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(name.isNotBlank() && unit.isNotBlank()) { "Enter the item name and selling unit." }
        require(priceMinor >= 0 && stock.isFinite() && stock >= 0 && stock <= 9_007_199_254_740_991.0) { "Enter a non-negative price and stock within the supported quantity range." }
        require(quantityPrecision in 0..6) { "Choose from zero to six quantity decimal places." }
        if (quantityPrecision > 0) {
            val scaled = java.math.BigDecimal.valueOf(stock).movePointRight(quantityPrecision).longValueExact()
            require(scaled <= 9_007_199_254_740_991L && exactProjection(scaled, quantityPrecision)) { "Opening stock exceeds the exact supported range for these decimal places." }
        }
        Product(id(), tenantId, name.trim(), unit.trim(), priceMinor, stock, normalizeBarcode(barcode),openingStock=stock,createdAt=Instant.now().toString(),quantityPrecision=quantityPrecision,stockScaled=if(quantityPrecision==0 && stock!=kotlin.math.floor(stock)) null else java.math.BigDecimal.valueOf(stock).movePointRight(quantityPrecision).longValueExact()).also {
            requireBarcodeAvailable(tenantId,it.barcode)
            execute("INSERT INTO products(id,tenant_id,name,unit,price_minor,stock,barcode,archived,opening_stock,created_at,quantity_precision,stock_scaled) VALUES(?,?,?,?,?,?,?,0,?,?,?,?)", it.id,tenantId,it.name,it.unit,it.priceMinor,it.stock,it.barcode,it.openingStock,it.createdAt,it.quantityPrecision,it.stockScaled)
            enqueue(tenantId,"product.created",it.id)
        }
    }
    /** Reminder only: changing a threshold never changes available quantity. */
    @Synchronized fun setReorderLevel(tenantId:String,productId:String,level:Int?,operationId:String?=null):Product = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(level==null || level>=0) { "Enter a whole reorder level from 0, or leave it disabled." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        if(operationId!=null) {
            val previous=query("SELECT * FROM stock_settings_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::stockSettingsChange).singleOrNull()
            if(previous!=null) {
                require(previous.productId==productId && previous.reorderLevel==level) { "This reminder change was already saved with different details." }
                return@transaction item
            }
        }
        require(!item.archived) { "Restore this archived item before changing its stock reminder." }
        val change=StockSettingsChange(operationId?:id(),tenantId,productId,item.reorderLevel,level,Instant.now().toString())
        execute("UPDATE products SET reorder_level=? WHERE tenant_id=? AND id=?",level,tenantId,productId)
        execute("INSERT INTO stock_settings_changes VALUES(?,?,?,?,?,?)",change.id,tenantId,productId,change.previousReorderLevel,level,change.createdAt)
        enqueue(tenantId,"stock.reminder_changed",change.id)
        item.copy(reorderLevel=level)
    }
    @Synchronized fun stockSettingsChanges(tenantId:String,productId:String?=null):List<StockSettingsChange> {
        requireTenant(tenantId)
        if(productId!=null)requireProduct(tenantId,productId)
        return query("SELECT * FROM stock_settings_changes WHERE tenant_id=?"+(if(productId!=null) " AND product_id=?" else "")+" ORDER BY created_at,id",*listOfNotNull(tenantId,productId).toTypedArray(),map=::stockSettingsChange)
    }
    @Synchronized fun lowStockProducts(tenantId:String):List<Product> {
        requireTenant(tenantId)
        return query("SELECT * FROM products WHERE tenant_id=? AND archived=0 AND reorder_level IS NOT NULL AND stock<=reorder_level ORDER BY stock,name",tenantId,map=::product)
    }
    /** Derived from immutable transaction snapshots. Legacy unknown opening quantities are never invented. */
    @Synchronized fun inventoryMovements(tenantId:String,productId:String):List<InventoryMovement> {
        requireTenant(tenantId)
        val item=requireProduct(tenantId,productId)
        val movements=mutableListOf<InventoryMovement>()
        if(item.openingStock!=null && item.createdAt!=null) movements+=InventoryMovement("opening:$productId",tenantId,productId,"opening",requireNotNull(item.openingStock),productId,requireNotNull(item.createdAt),"Counted opening stock")
        query("SELECT * FROM stock_receipts WHERE tenant_id=? AND product_id=?",tenantId,productId,map=::stockReceipt).forEach { receipt ->
            movements+=InventoryMovement("purchase:${receipt.id}",tenantId,productId,"purchase",projectedQuantity(receipt.baseQuantity,receipt.basePrecision),receipt.id,receipt.createdAt,"Received ${receipt.quantityLabel} ${receipt.unit} from ${receipt.supplierName.ifBlank { "supplier not recorded" }}")
        }
        query("SELECT s.id AS source_id,s.created_at,SUM(l.quantity*l.base_units_per_unit*CAST(POWER(10,l.base_precision-l.quantity_precision) AS INTEGER)) AS quantity_scaled,MAX(l.base_precision) AS precision FROM sale_lines l JOIN sales s ON s.tenant_id=l.tenant_id AND s.id=l.sale_id WHERE l.tenant_id=? AND l.product_id=? GROUP BY s.id,s.created_at",tenantId,productId) { row ->
            InventoryMovement("sale:${row.getString("source_id")}",tenantId,productId,"sale",-projectedQuantity(row.getLong("quantity_scaled"),row.getInt("precision")),row.getString("source_id"),row.getString("created_at"),"Goods sold on receipt")
        }.also { movements+=it }
        query("SELECT r.id AS source_id,r.created_at,SUM(l.quantity*l.base_units_per_unit*CAST(POWER(10,l.base_precision-l.quantity_precision) AS INTEGER)) AS quantity_scaled,MAX(l.base_precision) AS precision,r.reason FROM return_lines l JOIN sale_returns r ON r.tenant_id=l.tenant_id AND r.id=l.return_id WHERE l.tenant_id=? AND l.product_id=? AND r.restock=1 GROUP BY r.id,r.created_at,r.reason",tenantId,productId) { row ->
            InventoryMovement("return:${row.getString("source_id")}",tenantId,productId,"return",projectedQuantity(row.getLong("quantity_scaled"),row.getInt("precision")),row.getString("source_id"),row.getString("created_at"),"Sellable return: ${row.getString("reason")}")
        }.also { movements+=it }
        stockAdjustments(tenantId,productId).forEach { count -> movements+=InventoryMovement("count:${count.id}",tenantId,productId,"count",count.difference,count.id,count.createdAt,"Count ${count.previousStock} → ${count.countedStockLabel}: ${count.reason}") }
        movements+=inventoryDispositionMovements(tenantId,productId)
        movements+=repackingMovements(tenantId,productId)
        movements+=purchaseReturnMovements(tenantId,productId)
        return movements.sortedWith(compareBy<InventoryMovement> { Instant.parse(it.createdAt) }.thenBy { it.id })
    }
    @Synchronized fun productByBarcode(tenantId:String,barcode:String):Product? {
        requireTenant(tenantId)
        val code=normalizeBarcode(barcode) ?: return null
        return query("SELECT * FROM products WHERE tenant_id=? AND barcode=? AND archived=0",tenantId,code,map=::product).singleOrNull()
    }
    /** Editing never rewrites a sale's saved name, unit or price. Selling units stay fixed. */
    @Synchronized fun updateProduct(tenantId:String,productId:String,name:String,unit:String,priceMinor:Long,barcode:String?,operationId:String?=null):Product = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(name.isNotBlank() && unit.isNotBlank() && priceMinor>=0) { "Enter an item name, selling unit and non-negative price." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val code=normalizeBarcode(barcode)
        val item=requireProduct(tenantId,productId)
        val previous=operationId?.let { catalogueChange(tenantId,it) }
        if(previous!=null) {
            require(previous.product.id==productId && previous.kind=="edit" && previous.product.name==name.trim() && previous.product.unit==unit.trim() && previous.product.priceMinor==priceMinor && previous.product.barcode==code) { "This item edit was already saved with different details." }
            return@transaction previous.product.copy(stock=item.stock,reorderLevel=item.reorderLevel,openingStock=item.openingStock,createdAt=item.createdAt,quantityPrecision=item.quantityPrecision,stockScaled=item.stockScaled)
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
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
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
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
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
        val adjustment=StockAdjustment(operationId?:id(),tenantId,productId,item.name,item.unit,item.stock,countedStock,reason.trim(),Instant.now().toString(),item.quantityPrecision)
        execute("INSERT INTO stock_adjustments(id,tenant_id,product_id,product_name,unit,previous_stock,counted_stock,reason,created_at,quantity_precision) VALUES(?,?,?,?,?,?,?,?,?,?)",adjustment.id,tenantId,productId,item.name,item.unit,item.stock,countedStock,adjustment.reason,adjustment.createdAt,item.quantityPrecision)
        execute("UPDATE products SET stock=?,stock_scaled=? WHERE tenant_id=? AND id=?",projectedQuantity(countedStock.toLong(),item.quantityPrecision),countedStock,tenantId,productId)
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
    @Synchronized fun repaymentAllocations(tenantId:String,customerId:String?=null,includeReversed:Boolean=false):List<RepaymentAllocation> {
        requireTenant(tenantId)
        if(customerId!=null) requireCustomer(tenantId,customerId)
        return query("SELECT a.* FROM repayment_allocations a WHERE a.tenant_id=?"+(if(customerId!=null) " AND a.customer_id=?" else "")+(if(includeReversed) "" else " AND NOT EXISTS(SELECT 1 FROM allocation_reversals r WHERE r.tenant_id=a.tenant_id AND r.allocation_id=a.id)")+" ORDER BY a.created_at,a.id",*listOfNotNull(tenantId,customerId).toTypedArray(),map=::repaymentAllocation)
    }
    @Synchronized fun allocationReversals(tenantId:String,customerId:String?=null):List<AllocationReversal> {
        requireTenant(tenantId)
        if(customerId!=null) requireCustomer(tenantId,customerId)
        return query("SELECT * FROM allocation_reversals WHERE tenant_id=?"+(if(customerId!=null) " AND customer_id=?" else "")+" ORDER BY created_at,id",*listOfNotNull(tenantId,customerId).toTypedArray(),map=::allocationReversal)
    }
    /** Undo only the assignment, retaining both original history and the already-received payment. */
    @Synchronized fun reverseRepaymentAllocation(tenantId:String,allocationId:String,reason:String,operationId:String?=null):AllocationReversal = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_CUSTOMERS)
        requireTenant(tenantId)
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val original=query("SELECT * FROM repayment_allocations WHERE tenant_id=? AND id=?",tenantId,allocationId,map=::repaymentAllocation).singleOrNull()
            ?: throw IllegalArgumentException("Payment assignment not found in this shop.")
        val now=Instant.now()
        val correction=AllocationReversal(operationId?:id(),tenantId,original.customerId,allocationId,reason.trim(),now.toString())
        if(operationId!=null) {
            val previous=query("SELECT * FROM allocation_reversals WHERE tenant_id=? AND id=?",tenantId,operationId,map=::allocationReversal).singleOrNull()
            if(previous!=null) {
                require(previous.allocationId==allocationId && previous.reason==correction.reason) { "This correction was already saved with different details." }
                return@transaction previous
            }
        }
        require(query("SELECT id FROM allocation_reversals WHERE tenant_id=? AND allocation_id=?",tenantId,allocationId) { it.getString(1) }.isEmpty()) {
            "This assignment has already been undone. Refresh its history before continuing."
        }
        require(!now.isBefore(Instant.parse(original.createdAt))) { "This computer's clock is earlier than the saved assignment time. Correct the clock before recording this correction." }
        checkDebtAdjustmentClock(tenantId, original.customerId, now)
        validateCreditReturnAllocationReversal(tenantId,original)
        execute("INSERT INTO allocation_reversals(id,tenant_id,customer_id,allocation_id,reason,created_at) VALUES(?,?,?,?,?,?)",correction.id,tenantId,correction.customerId,allocationId,correction.reason,correction.createdAt)
        enqueue(tenantId,"repayment.allocation_reversed",correction.id)
        correction
    }
    /** Amount of this already-recorded payment which the owner has not assigned to a credit sale. */
    @Synchronized fun unallocatedRepayment(tenantId:String,repaymentId:String):Long {
        requireTenant(tenantId)
        val payment=requireRepayment(tenantId,repaymentId)
        val allocated=allocationTotal(tenantId,"repayment_id",repaymentId)
        return remainingAllocation(payment.amountMinor,allocated,"Payment allocations exceed the recorded repayment.")
    }
    /** Original credit after linked deductions and assignments. Unassigned payments may still cover this sale. */
    @Synchronized fun unallocatedCredit(tenantId:String,saleId:String):Long {
        val sale=requireSale(tenantId,saleId)
        val afterReturns = Math.subtractExact(sale.creditMinor, creditReturnedMinor(tenantId, saleId))
        val remainingCredit = Math.subtractExact(afterReturns, netWrittenOffByReceipt(tenantId, saleId))
        return remainingAllocation(remainingCredit, allocationTotal(tenantId,"sale_id",saleId),
            "Payment allocations exceed the receipt credit after returns and debt adjustments.")
    }
    /** Allocation changes the assignment only. The payment already reduced debt and recorded cash. */
    @Synchronized fun allocateRepayment(tenantId:String,repaymentId:String,saleId:String,amountMinor:Long,operationId:String?=null):RepaymentAllocation = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_CUSTOMERS)
        requireTenant(tenantId)
        require(amountMinor>0) { "Choose an allocation greater than zero." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val payment=requireRepayment(tenantId,repaymentId)
        val sale=requireSale(tenantId,saleId)
        require(sale.creditMinor>0 && sale.customerId==payment.customerId) { "Choose a credit receipt for the same customer as this repayment." }
        if(operationId!=null) {
            val previous=query("SELECT * FROM repayment_allocations WHERE tenant_id=? AND id=?",tenantId,operationId,map=::repaymentAllocation).singleOrNull()
            if(previous!=null) {
                require(previous.repaymentId==repaymentId && previous.saleId==saleId && previous.amountMinor==amountMinor) { "This allocation was already saved with different details." }
                return@transaction previous
            }
        }
        require(amountMinor<=unallocatedRepayment(tenantId,repaymentId)) { "Allocation exceeds the amount of this payment still available to assign." }
        require(amountMinor<=unallocatedCredit(tenantId,saleId)) { "Allocation exceeds the credit on this receipt still available to cover." }
        val now = Instant.now()
        checkDebtAdjustmentClock(tenantId, payment.customerId, now)
        val result=RepaymentAllocation(operationId?:id(),tenantId,payment.customerId,repaymentId,saleId,amountMinor,now.toString())
        execute("INSERT INTO repayment_allocations VALUES(?,?,?,?,?,?,?)",result.id,tenantId,result.customerId,repaymentId,saleId,amountMinor,result.createdAt)
        enqueue(tenantId,"repayment.allocated",result.id)
        result
    }
    @Synchronized fun addCustomer(tenantId: String, name: String, phone:String?=null, address:String?=null, creditLimitMinor:Long?=null): Customer = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_CUSTOMERS)
        requireTenant(tenantId)
        require(name.isNotBlank()) { "Enter the customer's name." }
        Customer(id(),tenantId,name.trim(),0,normalizeNigerianPhone(phone),address?.trim()?.takeIf { it.isNotEmpty() },creditLimitMinor).also {
            execute("INSERT INTO customers(id,tenant_id,name,balance_minor,phone,address,credit_limit_minor) VALUES(?,?,?,0,?,?,?)",it.id,tenantId,it.name,it.phone,it.address,it.creditLimitMinor)
            enqueue(tenantId,"customer.created",it.id)
        }
    }
    @Synchronized fun updateCustomer(tenantId:String,customerId:String,name:String,phone:String?,address:String?,creditLimitMinor:Long?,operationId:String?=null):Customer = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_CUSTOMERS)
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
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(rows.isNotEmpty() && rows.size <= 10_000) { "Import between 1 and 10,000 items at a time." }
        fun key(name:String,unit:String) = name.trim().lowercase(java.util.Locale.ROOT) to unit.trim().lowercase(java.util.Locale.ROOT)
        val keys=rows.map { key(it.name,it.unit) }
        require(keys.distinct().size==keys.size) { "Item name and unit combinations must be unique in this file." }
        val existing=products(tenantId,includeArchived=true).map { key(it.name,it.unit) }.toSet()
        require(keys.none { it in existing }) { "Some items already exist in this shop. No items were imported; use Add stock for existing goods." }
        rows.forEach { require(it.name.isNotBlank() && it.unit.isNotBlank() && it.priceMinor>=0 && it.stock>=0) { "Check all item names, units, prices and quantities." } }
        rows.map { addProduct(tenantId,it.name,it.unit,it.priceMinor,projectedQuantity(it.stock.toLong(),it.quantityPrecision),quantityPrecision=it.quantityPrecision) }
    }
    @Synchronized fun sales(tenantId: String): List<Sale> {
        requireTenant(tenantId)
        return query("SELECT * FROM sales WHERE tenant_id=? ORDER BY created_at DESC",tenantId,map=::sale)
    }
    /** cashMinor is applied cash, excluding change. Caller must explicitly choose a customer for credit. */
    @Synchronized fun checkout(tenantId: String, lines: List<CartLine>, cashMinor: Long, customerId: String? = null, operationId: String? = null, dueDate: String? = null,basketDiscount:BasketDiscount?=null,externalPayments:List<ExternalPayment> = emptyList(), cashGivenMinor: Long? = null, note: String? = null): Sale =
        checkoutWithPayment(tenantId, lines, cashMinor, customerId, operationId, dueDate, basketDiscount,
            externalPayments, cashGivenMinor, note, exchange = null)

    private fun checkoutWithPayment(
        tenantId: String, lines: List<CartLine>, cashMinor: Long, customerId: String?, operationId: String?,
        dueDate: String?, basketDiscount: BasketDiscount?, externalPayments: List<ExternalPayment>,
        cashGivenMinor: Long?, note: String?, exchange: ExchangePortion?,
    ): Sale = transaction {
        requirePermission(tenantId, StaffPermission.SELL)
        if (basketDiscount!=null || lines.any { it.discountPerUnitMinor > 0 || it.basketDiscountMinor>0 }) requirePermission(tenantId, StaffPermission.DISCOUNT)
        val externalRecorded=externalPaymentTotal(externalPayments)
        val recordedPayment = Math.addExact(Math.addExact(cashMinor, externalRecorded), exchange?.amountMinor ?: 0L)
        if (recordedPayment < cartTotal(lines)) requirePermission(tenantId, StaffPermission.SELL_CREDIT)
        requireTenant(tenantId)
        require(lines.isNotEmpty()) { "Choose an item before taking payment." }
        require(cashMinor >= 0) { "Cash cannot be negative." }
        require(lines.all { it.product.tenantId==tenantId && it.quantity>0 }) { "Items must belong to this shop with a positive quantity." }
        val requested=lines.groupBy { it.key }.values.map { group ->
            val first=group.first()
            require(group.size==1 || group.all {it.basketDiscountMinor==0L}) { "Combine basket-discounted items before checkout." }
            require(group.all { lineDetails(it.copy(quantity=first.quantity))==lineDetails(first) }) { "Repeated item details disagree. Review the sale." }
            first.copy(quantity=group.fold(0) { sum,line -> Math.addExact(sum,line.quantity) })
        }
        require(operationId == null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        if (operationId != null) {
            val existing=sales(tenantId).find { it.id==operationId }
            if (existing!=null) {
                require(existing.cashMinor==cashMinor && existing.cashGivenMinor==cashGivenMinor && existing.note==note && existing.exchange==exchange && existing.customerId==customerId && existing.dueDate==dueDate && existing.basketDiscount==basketDiscount && existing.externalPayments==externalPayments && existing.lines.sortedBy { it.key }.map(::lineDetails)==requested.sortedBy { it.key }.map(::lineDetails)) { "This operation was already saved with different details." }
                return@transaction existing
            }
        }
        val normalized = normalizeCheckoutLines(tenantId, requested, customerId)
        require(if(basketDiscount==null) normalized.all {it.basketDiscountMinor==0L} else allocateBasketDiscount(normalized,basketDiscount).map {it.basketDiscountMinor}==normalized.map {it.basketDiscountMinor}) { "Basket discount changed. Review the sale before payment." }
        val stockUpdates=normalized.groupBy { it.product.id }.map { (productId,group) ->
            val quantity=group.fold(0L) { sum,line -> Math.addExact(sum,line.baseQuantity) }
            productId to scaledStock(requireProduct(tenantId,productId),Math.negateExact(quantity))
        }
        validateMinimumPricesAtCheckout(tenantId,normalized)
        val total=cartTotal(normalized)
        require(recordedPayment<=total) { "Record applied cash only; return extra cash as change." }
        val credit=total-recordedPayment
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
        val now = Instant.now()
        if (credit > 0) checkDebtAdjustmentClock(tenantId, requireNotNull(customerId), now)
        val sale=Sale(operationId ?: id(),tenantId,normalized,total,cashMinor,credit,customerId,now.toString(),dueDate,basketDiscount,externalPayments,cashGivenMinor,note,exchange)
        execute("INSERT INTO sales(id,tenant_id,total_minor,cash_minor,credit_minor,customer_id,created_at,due_date,basket_discount_kind,basket_discount_value,basket_discount_reason,sale_note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",sale.id,tenantId,total,cashMinor,credit,customerId,sale.createdAt,dueDate,basketDiscount?.kind,basketDiscount?.value,basketDiscount?.reason,note)
        normalized.forEach { l ->
            execute("INSERT INTO sale_lines(sale_id,tenant_id,product_id,name,unit,price_minor,quantity,selling_unit_id,selling_unit,unit_price_minor,base_units_per_unit,discount_per_unit_minor,discount_reason,quantity_precision,base_precision,basket_discount_minor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",sale.id,tenantId,l.product.id,l.product.name,l.product.unit,l.product.priceMinor,l.quantity,l.sellingUnitId,l.sellingUnit,l.unitPriceMinor,l.baseUnitsPerUnit,l.discountPerUnitMinor,l.discountReason,l.quantityPrecision,l.product.quantityPrecision,l.basketDiscountMinor)
        }
        stockUpdates.forEach { (productId,stock) -> saveScaledStock(tenantId,productId,stock) }
        if (newDebt!=null) execute("UPDATE customers SET balance_minor=? WHERE tenant_id=? AND id=?",newDebt,tenantId,customerId)
        if (cashMinor>0) appendCash(tenantId,activeCashShift(tenantId)?.id,"sale",cashMinor,"Cash sale ${sale.id.take(8)}",sale.id)
        saveExternalPayments(tenantId,sale.id,externalPayments)
        captureSaleTender(sale)
        if (sale.note != null) featureEvent(tenantId, "sale.note_captured", sale.id)
        captureReceiptProfile(tenantId,sale.id)
        captureReceiptImage(tenantId,sale.id)
        captureMinimumPriceChecks(tenantId,sale.id,normalized)
        enqueue(tenantId,"sale.completed",sale.id)
        sale
    }
    @Synchronized fun recordRepayment(tenantId: String, customerId: String, amountMinor: Long, operationId: String? = null) = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_CUSTOMERS)
        requireTenant(tenantId)
        require(amountMinor>0) { "Enter a payment greater than zero." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val paymentId=if(operationId==null) id() else UUID.nameUUIDFromBytes(("repayment:"+tenantId+":"+operationId).toByteArray(Charsets.UTF_8)).toString()
        val previous=query("SELECT customer_id,amount_minor FROM repayments WHERE tenant_id=? AND id=?",tenantId,paymentId) { it.getString(1) to it.getLong(2) }.singleOrNull()
        if(previous!=null) {
            require(previous.first==customerId && previous.second==amountMinor) { "This repayment was already recorded with different details." }
            return@transaction
        }
        val now = Instant.now()
        checkDebtAdjustmentClock(tenantId, customerId, now)
        val changed=execute("UPDATE customers SET balance_minor=balance_minor-? WHERE tenant_id=? AND id=? AND balance_minor>=?",amountMinor,tenantId,customerId,amountMinor)
        require(changed==1) { "Customer not found in this shop, or payment exceeds debt." }
        execute("INSERT INTO repayments VALUES(?,?,?,?,?)",paymentId,tenantId,customerId,amountMinor,now.toString())
        appendCash(tenantId,activeCashShift(tenantId)?.id,"repayment",amountMinor,"Customer repayment",paymentId)
        enqueue(tenantId,"customer.repayment",paymentId)
    }
    @Synchronized fun pendingSyncCount(tenantId: String): Int {
        requireTenant(tenantId)
        return query("SELECT count(*) FROM outbox WHERE tenant_id=? AND status='pending'",tenantId) { it.getInt(1) }.single()
    }
    /** Explicit opt-in only: sample records never appear in a merchant's real tenant. */
    @Synchronized fun seedDemoTenants(): List<Tenant> = transaction {
        require(permissionCheck == null) { "Practice seeding is available only on an isolated trusted preview connection." }
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
    @Synchronized fun sellingUnits(tenantId:String,productId:String?=null,includeArchived:Boolean=false):List<SellingUnit> {
        requireTenant(tenantId)
        if(productId!=null) requireProduct(tenantId,productId)
        return query("SELECT * FROM selling_units WHERE tenant_id=?"+(if(productId!=null) " AND product_id=?" else "")+(if(includeArchived) "" else " AND archived=0")+" ORDER BY name",*listOfNotNull(tenantId,productId).toTypedArray(),map=::sellingUnit)
    }
    @Synchronized fun addSellingUnit(tenantId:String,productId:String,name:String,factor:Int,priceMinor:Long,barcode:String?=null,operationId:String?=null):SellingUnit = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(operationId==null || operationId.isNotBlank())
        val product=requireProduct(tenantId,productId)
        val unit=SellingUnit(operationId?:id(),tenantId,productId,name.trim(),product.unit,factor,priceMinor,normalizeBarcode(barcode),false,Instant.now().toString())
        if(operationId!=null) {
            val previous=query("SELECT * FROM selling_units WHERE tenant_id=? AND id=?",tenantId,operationId,map=::sellingUnit).singleOrNull()
            if(previous!=null) {
                require(previous.productId==productId && previous.name==unit.name && previous.baseUnit==unit.baseUnit && previous.baseUnitsPerUnit==factor && previous.priceMinor==priceMinor && previous.barcode==unit.barcode) { "This selling unit was already saved with different details." }
                return@transaction previous
            }
        }
        require(!product.archived) { "Restore this item before adding selling units." }
        require(!unit.name.equals(product.unit,true)) { "Choose a name different from the item's base unit." }
        require(sellingUnits(tenantId,productId).none { it.name.equals(unit.name,true) }) { "An active selling unit already uses this name. Archive it first to change the definition." }
        requireBarcodeAvailable(tenantId,unit.barcode)
        val key=productId+"~"+unit.id
        require(products(tenantId,true).none { it.id==key } && sellingUnits(tenantId,includeArchived=true).none { it.productId+"~"+it.id==key }) { "This selling unit identifier conflicts with an existing item or selling unit." }
        execute("INSERT INTO selling_units(id,tenant_id,product_id,name,base_unit,base_units_per_unit,price_minor,barcode,archived,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",unit.id,tenantId,productId,unit.name,unit.baseUnit,factor,priceMinor,unit.barcode,0,unit.createdAt)
        enqueue(tenantId,"selling_unit.created",unit.id)
        unit
    }
    @Synchronized fun archiveSellingUnit(tenantId:String,unitId:String,archived:Boolean) = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        val unit=requireSellingUnit(tenantId,unitId)
        if(unit.archived!=archived) {
            if(!archived) require(sellingUnits(tenantId,unit.productId).none { it.name.equals(unit.name,true) }) { "Another active selling unit uses this name." }
            execute("UPDATE selling_units SET archived=? WHERE tenant_id=? AND id=?",if(archived) 1 else 0,tenantId,unitId)
            enqueue(tenantId,if(archived) "selling_unit.archived" else "selling_unit.restored",unitId)
        }
    }
    private fun sellingUnit(r:ResultSet)=SellingUnit(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("name"),r.getString("base_unit"),r.getInt("base_units_per_unit"),r.getLong("price_minor"),r.getString("barcode"),r.getInt("archived")==1,r.getString("created_at"))
    private fun requireSellingUnit(tenantId:String,unitId:String)=query("SELECT * FROM selling_units WHERE tenant_id=? AND id=?",tenantId,unitId,map=::sellingUnit).singleOrNull() ?: throw IllegalArgumentException("Selling unit not found in this shop.")
    private fun normalizeCheckoutLines(tenantId: String, requested: List<CartLine>, customerId: String?): List<CartLine> =
        requested.map { line ->
            val current=requireProduct(tenantId,line.product.id)
            require(!current.archived) { "${current.name} has been archived. Remove it from this sale." }
            require(line.product.priceMinor==current.priceMinor && line.product.unit==current.unit && line.product.name==current.name) { "Item details changed. Review the sale." }
            val unit=line.sellingUnitId?.let { requireSellingUnit(tenantId,it) }
            require(unit==null || (!unit.archived && unit.productId==current.id && unit.baseUnit==current.unit)) { "Choose an active selling unit belonging to this item." }
            val normalizedLine=CartLine(current.copy(stockScaled=null),line.quantity,unit?.id,unit?.name ?: current.unit,unit?.priceMinor ?: current.priceMinor,unit?.baseUnitsPerUnit ?: 1,line.discountPerUnitMinor,line.discountReason,line.quantityPrecision,line.basketDiscountMinor)
            validatePriceListDiscount(tenantId,normalizedLine,customerId)
            require(lineDetails(line)==lineDetails(normalizedLine)) { "Selling unit details changed. Review the sale." }
            normalizedLine
        }

    private fun lineDetails(line:CartLine)=listOf(line.key,line.product.id,line.sellingUnitId,line.quantity,line.product.name,line.product.unit,line.product.priceMinor,line.sellingUnit,line.unitPriceMinor,line.baseUnitsPerUnit,line.discountPerUnitMinor,line.discountReason,line.quantityPrecision,line.product.quantityPrecision,line.basketDiscountMinor)
    private data class StockUpdate(val stock:Double,val scaled:Long?)
    private fun exactProjection(scaled:Long, precision:Int):Boolean =
        java.math.BigDecimal.valueOf(projectedQuantity(scaled,precision)).movePointRight(precision).compareTo(java.math.BigDecimal.valueOf(scaled))==0
    private fun scaledStock(item:Product,delta:Long):StockUpdate {
        if(item.stockScaled==null && item.quantityPrecision==0 && item.stock!=kotlin.math.floor(item.stock)) return StockUpdate(exactStock(item.stock,delta),null)
        val scaled=Math.addExact(item.exactStockScaled,delta)
        require(scaled>=0) { "There is not enough stock for this sale." }
        require(scaled<=9_007_199_254_740_991L) { "Stock exceeds the supported exact range." }
        require(exactProjection(scaled,item.quantityPrecision)) { "Stock exceeds the exact supported range for these decimal places." }
        return StockUpdate(projectedQuantity(scaled,item.quantityPrecision),scaled)
    }
    private fun saveScaledStock(tenantId:String,productId:String,stock:StockUpdate) {
        check(execute("UPDATE products SET stock=?,stock_scaled=? WHERE tenant_id=? AND id=?",stock.stock,stock.scaled,tenantId,productId)==1)
    }
    private fun exactStock(stock:Double,delta:Long):Double {
        val exact=java.math.BigDecimal.valueOf(stock).add(java.math.BigDecimal.valueOf(delta))
        val result=exact.toDouble()
        require(result.isFinite() && result in 0.0..9_007_199_254_740_991.0 && java.math.BigDecimal.valueOf(result).compareTo(exact)==0) { "Stock quantity exceeds the supported exact range or there is not enough stock." }
        return result
    }
    @Synchronized fun purchaseUnits(tenantId:String,productId:String?=null,includeArchived:Boolean=false):List<PurchaseUnit> {
        requireTenant(tenantId)
        if(productId!=null) requireProduct(tenantId,productId)
        return query("SELECT * FROM purchase_units WHERE tenant_id=?"+(if(productId!=null) " AND product_id=?" else "")+(if(includeArchived) "" else " AND archived=0")+" ORDER BY name",*listOfNotNull(tenantId,productId).toTypedArray(),map=::purchaseUnit)
    }
    /** Buying factors are immutable: archive an old definition rather than rewriting received history. */
    @Synchronized fun addPurchaseUnit(tenantId:String,productId:String,name:String,baseUnitsPerUnit:Int,operationId:String?=null):PurchaseUnit = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
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
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
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
        requirePermission(tenantId, StaffPermission.MANAGE_STOCK)
        requireTenant(tenantId)
        require(quantity>0 && unitCostMinor>=0) { "Enter a positive quantity and non-negative buying cost." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val item=requireProduct(tenantId,productId)
        val precision=if(purchaseUnitId==null) item.quantityPrecision else 0
        val total=if(precision==0) Math.multiplyExact(unitCostMinor,quantity.toLong()) else Quantity(quantity.toLong(),precision).lineTotalMinor(unitCostMinor)
        require(paidCashMinor in 0..total) { "Cash paid must be between zero and the purchase total." }
        require(paidCashMinor==total || supplierName.isNotBlank()) { "Enter the supplier name for the unpaid purchase balance." }
        if(operationId!=null) {
            val previous=stockReceipts(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.productId==productId && previous.quantity==quantity && previous.unitCostMinor==unitCostMinor && previous.paidCashMinor==paidCashMinor && previous.supplierName==supplierName.trim() && previous.purchaseUnitId==purchaseUnitId && (expectedBaseUnitsPerUnit==null || previous.baseUnitsPerUnit==expectedBaseUnitsPerUnit)) { "This receipt was already saved with different details." }
                return@transaction previous
            }
        }
        require(!item.archived) { "Restore this archived item before adding stock." }
        val buyingUnit=purchaseUnitId?.let { requirePurchaseUnit(tenantId,it) }
        require(buyingUnit==null || (buyingUnit.productId==productId && buyingUnit.baseUnit==item.unit && !buyingUnit.archived)) { "Choose an active buying unit belonging to this item." }
        val factor=buyingUnit?.baseUnitsPerUnit ?: 1
        require(expectedBaseUnitsPerUnit==null || expectedBaseUnitsPerUnit==factor) { "The buying unit does not match the conversion reviewed. Choose it again." }
        val baseQuantity=Quantity(quantity.toLong(),precision).rescale(item.quantityPrecision).timesWhole(factor.toLong()).scaledUnits
        val newStock=scaledStock(item,baseQuantity)
        val shift=if(paidCashMinor>0) requireCashShift(tenantId) else null
        if(shift!=null) requireCashAvailable(tenantId,shift.id,paidCashMinor)
        val receipt=StockReceipt(operationId?:id(),tenantId,productId,item.name,buyingUnit?.name ?: item.unit,quantity,unitCostMinor,total,paidCashMinor,supplierName.trim(),Instant.now().toString(),item.unit,factor,purchaseUnitId,precision,item.quantityPrecision)
        execute("INSERT INTO stock_receipts(id,tenant_id,product_id,product_name,unit,quantity,unit_cost_minor,total_cost_minor,paid_cash_minor,supplier_name,created_at,base_unit,base_units_per_unit,purchase_unit_id,quantity_precision,base_precision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",receipt.id,tenantId,productId,item.name,receipt.unit,quantity,unitCostMinor,total,paidCashMinor,receipt.supplierName,receipt.createdAt,receipt.baseUnit,factor,purchaseUnitId,precision,item.quantityPrecision)
        saveScaledStock(tenantId,productId,newStock)
        if(paidCashMinor>0) appendCash(tenantId,shift!!.id,"stock_purchase",-paidCashMinor,if(receipt.supplierName.isBlank()) "Cash stock purchase" else "Stock from ${receipt.supplierName}",receipt.id)
        enqueue(tenantId,"stock.received",receipt.id)
        receipt
    }
    @Synchronized fun expenses(tenantId:String):List<Expense> {
        requireTenant(tenantId)
        return query("SELECT * FROM expenses WHERE tenant_id=? ORDER BY created_at DESC",tenantId) { expense(it) }
    }
    @Synchronized fun recordExpense(tenantId:String,category:String,description:String,amountMinor:Long,operationId:String?=null):Expense = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_MONEY)
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
    @Synchronized fun openCashShift(tenantId:String,openingMinor:Long,openingCount:CashCount?=null):CashShift = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_MONEY)
        requireTenant(tenantId)
        require(openingMinor>=0) { "Opening cash cannot be negative." }
        require(openingCount==null || openingCount.totalMinor==openingMinor) { "Opening count sheet does not match the opening cash." }
        require(activeCashShift(tenantId)==null) { "This shop already has an open cash shift." }
        val shift=CashShift(id(),tenantId,openingMinor,Instant.now().toString(),null,null,null,openingCount=openingCount)
        execute("INSERT INTO cash_shifts(id,tenant_id,opening_minor,opened_at,opening_count_sheet) VALUES(?,?,?,?,?)",shift.id,tenantId,openingMinor,shift.openedAt,openingCount?.encode())
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
        requirePermission(tenantId, StaffPermission.MANAGE_MONEY)
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
    @Synchronized fun closeCashShift(tenantId:String,shiftId:String,countedMinor:Long,closingCount:CashCount?=null,blindClosing:Boolean=false):CashShift = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_MONEY)
        requireTenant(tenantId)
        require(countedMinor>=0) { "Counted cash cannot be negative." }
        require(closingCount==null || closingCount.totalMinor==countedMinor) { "Closing count sheet does not match the counted cash." }
        val shift=query("SELECT * FROM cash_shifts WHERE tenant_id=? AND id=?",tenantId,shiftId,map=::cashShift).singleOrNull()
            ?: throw IllegalArgumentException("Cash shift not found in this shop.")
        if(shift.closedAt!=null) {
            require(shift.countedMinor==countedMinor && shift.closingCount==closingCount && shift.blindClosing==blindClosing) { "This shift is already closed. Its count cannot be overwritten." }
            return@transaction shift
        }
        val result=shift.copy(closedAt=Instant.now().toString(),expectedMinor=expectedCash(tenantId,shiftId),countedMinor=countedMinor,closingCount=closingCount,blindClosing=blindClosing)
        execute("UPDATE cash_shifts SET closed_at=?,expected_minor=?,counted_minor=?,closing_count_sheet=?,closing_blind=? WHERE tenant_id=? AND id=? AND closed_at IS NULL",result.closedAt,result.expectedMinor,countedMinor,closingCount?.encode(),if(blindClosing)1 else 0,tenantId,shiftId)
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
        val returned=saleReturns(tenantId,saleId).flatMap { it.lines }.groupBy { it.key }
        return sale.lines.associate { line ->
            line.key to (line.quantity - (returned[line.key]?.fold(0) { sum,item -> Math.addExact(sum,item.quantity) } ?: 0))
        }
    }
    @Synchronized fun returnItems(tenantId:String,saleId:String,quantities:Map<String,Int>,restock:Boolean,reason:String,operationId:String?=null):SaleReturn =
        saveReturn(tenantId, saleId, quantities, restock, reason, operationId, exchange = null)

    private fun saveReturn(
        tenantId: String, saleId: String, quantities: Map<String, Int>, restock: Boolean,
        reason: String, operationId: String?, exchange: ExchangePortion?,
    ): SaleReturn = transaction {
        requirePermission(tenantId, StaffPermission.RETURN_SALE)
        requireTenant(tenantId)
        require(reason.isNotBlank() && quantities.isNotEmpty() && quantities.all { it.key.isNotBlank() && it.value>0 }) { "Select returned quantities and enter a reason." }
        require(operationId==null || operationId.isNotBlank()) { "Operation identifier cannot be blank." }
        val sale=requireSale(tenantId,saleId)
        require(sale.externalPayments.isEmpty()) { "Returns involving external payment portions are unavailable until original-method refund reconciliation is implemented. No refund, debt or stock change was recorded." }
        if(sale.creditMinor>0) requirePermission(tenantId,StaffPermission.MANAGE_CUSTOMERS)
        if(operationId!=null) {
            val previous=saleReturns(tenantId).find { it.id==operationId }
            if(previous!=null) {
                require(previous.saleId==saleId && previous.restock==restock && previous.reason==reason.trim() && previous.exchange==exchange && previous.lines.associate { it.key to it.quantity }==quantities) { "This return was already recorded with different details." }
                return@transaction previous
            }
        }
        val lines = prepareReturnLines(sale, quantities)
        val returnedValue=lines.fold(0L) { total,line -> Math.addExact(total,line.totalMinor) }
        val creditPlan=if(sale.creditMinor>0) planCreditReturn(tenantId,saleId,returnedValue) else null
        require(exchange == null || (creditPlan == null && exchange.amountMinor <= returnedValue))
        val refund=creditPlan?.cashRefundMinor ?: (returnedValue - (exchange?.amountMinor ?: 0L))
        val shift=if((exchange == null && sale.creditMinor==0L) || refund>0) requireCashShift(tenantId) else null
        if(refund>0) requireCashAvailable(tenantId,requireNotNull(shift).id,refund)
        // Validate all stock updates before mutating the ledger; damaged goods never re-enter saleable stock.
        val stockUpdates=if(restock) lines.groupBy { it.productId }.map { (productId,group) ->
            val item=requireProduct(tenantId,productId)
            require(group.all { item.unit==it.baseUnit }) { "Base unit changed since the sale. Review the stock correction." }
            productId to scaledStock(item,group.fold(0L) { sum,line -> Math.addExact(sum,line.baseQuantity) })
        } else emptyList()
        val result=SaleReturn(operationId?:id(),tenantId,saleId,lines,refund,restock,reason.trim(),creditPlan?.createdAt ?: Instant.now().toString(),creditPlan?.amountMinor ?: 0L, exchange)
        execute("INSERT INTO sale_returns VALUES(?,?,?,?,?,?,?)",result.id,tenantId,saleId,refund,if(restock) 1 else 0,result.reason,result.createdAt)
        lines.forEach { line -> execute("INSERT INTO return_lines(return_id,tenant_id,product_id,name,unit,quantity,unit_price_minor,selling_unit_id,base_units_per_unit,base_unit,quantity_precision,base_precision,refund_override_minor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",result.id,tenantId,line.productId,line.name,line.unit,line.quantity,line.unitPriceMinor,line.sellingUnitId,line.baseUnitsPerUnit,line.baseUnit,line.quantityPrecision,line.basePrecision,line.refundOverrideMinor) }
        stockUpdates.forEach { (productId,quantity) -> saveScaledStock(tenantId,productId,quantity) }
        if(creditPlan!=null) captureCreditReturn(tenantId,result,creditPlan)
        if(refund>0) appendCash(tenantId,requireNotNull(shift).id,"refund",-refund,"Return for sale ${saleId.take(8)}: ${result.reason}",result.id)
        enqueue(tenantId,"sale.returned",result.id)
        result
    }
    private fun prepareReturnLines(sale: Sale, quantities: Map<String, Int>): List<ReturnLine> {
        val remaining=returnableQuantities(sale.tenantId,sale.id)
        require(quantities.all { (productId,quantity) -> quantity <= (remaining[productId] ?: 0) }) { "A return cannot exceed the quantity still returnable on this receipt." }
        return quantities.entries.sortedBy { it.key }.map { (productId,quantity) ->
            val original=sale.lines.single { it.key==productId }
            val already=original.quantity-requireNotNull(remaining[productId])
            val previousAmount=original.cumulativeRefundMinor(already)
            val cumulative=original.cumulativeRefundMinor(Math.addExact(already,quantity))
            ReturnLine(original.product.id,original.product.name,original.sellingUnit,quantity,original.netUnitPriceMinor,original.sellingUnitId,original.baseUnitsPerUnit,original.product.unit,original.quantityPrecision,original.product.quantityPrecision,cumulative-previousAmount)
        }
    }

    @Synchronized fun reviewCashExchange(
        tenantId: String, saleId: String, quantities: Map<String, Int>, replacements: List<CartLine>,
        restock: Boolean, reason: String,
    ): CashExchangeReview = featureRead(tenantId, StaffPermission.RETURN_SALE) {
        requirePermission(tenantId, StaffPermission.SELL)
        val original = requireSale(tenantId, saleId)
        require(original.creditMinor == 0L && original.externalPayments.isEmpty()) {
            "Choose a receipt paid with cash or an earlier cash exchange."
        }
        require(quantities.isNotEmpty() && quantities.values.all { it > 0 }) { "Enter the quantity being returned." }
        val cleanReason = reason.trim()
        require(cleanReason.length in 3..250 && cleanReason.none { it.isISOControl() }) {
            "Enter a reason from 3 to 250 printable characters."
        }
        require(replacements.isNotEmpty() && replacements.map { it.key }.distinct().size == replacements.size &&
            replacements.all { it.product.tenantId == tenantId && it.discountTotalMinor == 0L }) {
            "Choose replacement items at their current selling prices."
        }
        val returned = prepareReturnLines(original, quantities)
        val replacementLines = normalizeCheckoutLines(tenantId, replacements, original.customerId)
        validateMinimumPricesAtCheckout(tenantId, replacementLines)
        val stockReturned = if (restock) returned.groupBy { it.productId }
            .mapValues { (_, group) -> group.fold(0L) { total, line -> Math.addExact(total, line.baseQuantity) } }
        else emptyMap()
        for ((productId, quantity) in stockReturned) scaledStock(requireProduct(tenantId, productId), quantity)
        for ((productId, lines) in replacementLines.groupBy { it.product.id }) {
            val needed = lines.fold(0L) { total, line -> Math.addExact(total, line.baseQuantity) }
            scaledStock(requireProduct(tenantId, productId), Math.subtractExact(stockReturned[productId] ?: 0L, needed))
        }
        val review = CashExchangeReview(saleId, returned, replacementLines, restock, cleanReason, null)
        require(review.appliedMinor > 0L) { "Both the returned goods and the replacement need a positive value." }
        if (review.cashToCollectMinor == 0L && review.cashToRefundMinor == 0L) return@featureRead review
        val shift = requireCashShift(tenantId)
        if (review.cashToRefundMinor > 0L) requireCashAvailable(tenantId, shift.id, review.cashToRefundMinor)
        review.copy(shiftId = shift.id)
    }

    @Synchronized fun saveCashExchange(
        tenantId: String, review: CashExchangeReview, operationId: String,
    ): CashExchange = featureWrite(tenantId, StaffPermission.RETURN_SALE) {
        requirePermission(tenantId, StaffPermission.SELL)
        require(operationId.isNotBlank() && operationId.length <= 150 && operationId.none { it.isISOControl() }) {
            "Exchange reference is invalid."
        }
        cashExchange(tenantId, operationId)?.let { saved ->
            val returned = saleReturns(tenantId, saved.originalReceiptId).single { it.id == saved.returnId }
            val replacement = requireSale(tenantId, saved.replacementReceiptId)
            require(saved.originalReceiptId == review.originalReceiptId && saved.appliedMinor == review.appliedMinor &&
                returned.lines == review.returned && returned.restock == review.restock && returned.reason == review.reason &&
                replacement.lines.map(::lineDetails) == review.replacements.map(::lineDetails)) {
                "This exchange was already saved with different details."
            }
            return@featureWrite saved
        }
        val original = requireSale(tenantId, review.originalReceiptId)
        val checked = reviewCashExchange(tenantId, original.id, review.returned.associate { it.key to it.quantity },
            review.replacements, review.restock, review.reason)
        require(checked == review) { "The exchange changed. Review the items, stock and payment again." }
        val returnId = UUID.nameUUIDFromBytes("exchange:return:$operationId".toByteArray()).toString()
        val replacementId = UUID.nameUUIDFromBytes("exchange:sale:$operationId".toByteArray()).toString()
        val returned = saveReturn(tenantId, original.id, review.returned.associate { it.key to it.quantity },
            review.restock, review.reason, returnId, ExchangePortion(operationId, replacementId, review.appliedMinor))
        val replacement = checkoutWithPayment(tenantId, review.replacements, review.cashToCollectMinor, original.customerId,
            replacementId, null, null, emptyList(), null, null, ExchangePortion(operationId, original.id, review.appliedMinor))
        require(Instant.parse(original.createdAt) < Instant.parse(returned.createdAt) &&
            Instant.parse(returned.createdAt) <= Instant.parse(replacement.createdAt)) {
            "Correct the computer clock before saving the exchange."
        }
        CashExchange(operationId, original.id, returned.id, replacement.id, review.appliedMinor, replacement.createdAt)
            .also { recordCashExchange(tenantId, it) }
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
        val credited=purchaseReturnCredits(tenantId,receiptId)
        return Math.subtractExact(Math.subtractExact(receipt.unpaidMinor,paid),credited).also {
            require(it>=0) { "Supplier payments and returns exceed the receipt balance." }
        }
    }
    @Synchronized fun paySupplierReceipt(tenantId:String,receiptId:String,amountMinor:Long,operationId:String?=null):SupplierPayment = transaction {
        requirePermission(tenantId, StaffPermission.MANAGE_SUPPLIERS)
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
        val now=Instant.now()
        purchaseReturnLatestTime(tenantId,receiptId)?.let {
            require(now>it) { "Correct the computer clock before paying this receipt." }
        }
        val result=SupplierPayment(operationId?:id(),tenantId,receiptId,receipt.supplierName,amountMinor,now.toString())
        execute("INSERT INTO supplier_payments VALUES(?,?,?,?,?,?)",result.id,tenantId,receiptId,receipt.supplierName,amountMinor,result.createdAt)
        appendCash(tenantId,shift.id,"supplier_payment",-amountMinor,"Payment to ${receipt.supplierName}",result.id)
        enqueue(tenantId,"supplier.payment",result.id)
        result
    }
    private fun sale(r:ResultSet):Sale {
        val tenantId=r.getString("tenant_id"); val saleId=r.getString("id")
        require(listOf(r.getString("basket_discount_kind"),r.getString("basket_discount_value"),r.getString("basket_discount_reason")).let { fields -> fields.all {it==null} || fields.all {it!=null} }) { "Saved basket discount metadata is incomplete." }
        val lines=query("SELECT * FROM sale_lines WHERE tenant_id=? AND sale_id=? ORDER BY id",tenantId,saleId) { line ->
            CartLine(Product(line.getString("product_id"),tenantId,line.getString("name"),line.getString("unit"),line.getLong("price_minor"),0.0,quantityPrecision=line.getInt("base_precision")),line.getInt("quantity"),line.getString("selling_unit_id"),line.getString("selling_unit") ?: line.getString("unit"),(line.getObject("unit_price_minor") as? Number)?.toLong() ?: line.getLong("price_minor"),line.getInt("base_units_per_unit"),line.getLong("discount_per_unit_minor"),line.getString("discount_reason"),line.getInt("quantity_precision"),line.getLong("basket_discount_minor"))
        }
        return Sale(saleId,tenantId,lines,r.getLong("total_minor"),r.getLong("cash_minor"),r.getLong("credit_minor"),r.getString("customer_id"),r.getString("created_at"),r.getString("due_date"),r.getString("basket_discount_kind")?.let {BasketDiscount(it,r.getLong("basket_discount_value"),requireNotNull(r.getString("basket_discount_reason")))},externalPaymentsUnchecked(tenantId,saleId),saleTenderUnchecked(tenantId,saleId),r.getString("sale_note"), exchangeForSale(tenantId,saleId))
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
        val lines=query("SELECT * FROM return_lines WHERE tenant_id=? AND return_id=? ORDER BY id",tenantId,returnId) { line -> ReturnLine(line.getString("product_id"),line.getString("name"),line.getString("unit"),line.getInt("quantity"),line.getLong("unit_price_minor"),line.getString("selling_unit_id"),line.getInt("base_units_per_unit"),line.getString("base_unit") ?: line.getString("unit"),line.getInt("quantity_precision"),line.getInt("base_precision"),(line.getObject("refund_override_minor") as? Number)?.toLong()) }
        return SaleReturn(returnId,tenantId,r.getString("sale_id"),lines,r.getLong("refund_minor"),r.getInt("restock")==1,r.getString("reason"),r.getString("created_at"),creditReturnAmount(tenantId,returnId), exchangeForReturn(tenantId,returnId))
    }
    private fun supplierPayment(r:ResultSet)=SupplierPayment(r.getString("id"),r.getString("tenant_id"),r.getString("receipt_id"),r.getString("supplier_name"),r.getLong("amount_minor"),r.getString("created_at"))

    private val snapshotTableOrder get() = listOf("products","product_metadata_changes","selling_units","stock_settings_changes","purchase_units","customers","customer_changes","cash_shifts","sales","sale_lines","repayments","repayment_allocations","allocation_reversals","stock_receipts","expenses","sale_returns","return_lines","supplier_payments","cash_entries","catalogue_changes","stock_adjustments","outbox","activity_audit") + supplierProfileSnapshotTables + purchaseOrderSnapshotTables + inventoryDispositionSnapshotTables + stockCountSessionSnapshotTables + shopProfileSnapshotTables + externalPaymentSnapshotTables + priceListSnapshotTables + bulkCatalogueUpdateSnapshotTables + supplierInvoiceSnapshotTables + creditReturnSnapshotTables + quotationSnapshotTables + localImageSnapshotTables + ownerCashSnapshotTables + minimumPriceSnapshotTables + repackingSnapshotTables + expenseAttachmentSnapshotTables + saleTenderSnapshotTables + reminderSnapshotTables + variantSnapshotTables + purchaseReturnSnapshotTables + debtAdjustmentSnapshotTables + cashExchangeSnapshotTables
    @Synchronized fun exportTenantSnapshot(tenantId:String):TenantSnapshot = transaction {
        requirePermission(tenantId, StaffPermission.BACKUP)
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
        // Login recovery uses its own explicit trusted connection. Never disable the running
        // workspace's guard just to import another shop.
        requirePermission(snapshot.tenant.id, StaffPermission.BACKUP)
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
                    require(!required || value!=null || generatedId || (name in setOf("quantity_precision","base_precision") && name !in row) || (table=="cash_shifts" && name=="closing_blind" && name !in row) || (table=="sales" && name in setOf("receipt_profile_captured","receipt_image_captured","minimum_price_captured") && name !in row) || (table=="products" && name in setOf("archived","aliases","favourite") && name !in row) || (table in setOf("stock_receipts","sale_lines","return_lines") && name=="base_units_per_unit" && name !in row) || (table=="sale_lines" && name in setOf("discount_per_unit_minor","basket_discount_minor") && name !in row)) { "Backup is missing a required value in $table." }
                    if(value!=null && type=="INTEGER") require(value.toLongOrNull()!=null) { "Backup has invalid integer data in $table." }
                    if(value!=null && name=="quantity" && table in setOf("sale_lines","stock_receipts","return_lines")) {
                        val quantity=value.toLongOrNull()
                        require(quantity!=null && quantity in 1L..Int.MAX_VALUE.toLong()) { "Backup quantity exceeds the supported integer range." }
                    }
                    if(value!=null && name=="base_units_per_unit") require(value.toLongOrNull() in 1L..Int.MAX_VALUE.toLong()) { "Backup buying unit factor exceeds the supported integer range." }
                    if(value!=null && name in setOf("reorder_level","previous_reorder_level")) require(value.toLongOrNull() in 0L..Int.MAX_VALUE.toLong()) { "Backup reorder level exceeds the supported integer range." }
                    if(value!=null && name=="opening_stock") require(value.toDoubleOrNull()?.let { it in 0.0..9_007_199_254_740_991.0 }==true) { "Backup opening stock exceeds the supported range." }
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
                if(table in setOf("stock_receipts","return_lines") && "base_unit" !in data) data["base_unit"]=data["unit"]
                if(table=="sale_lines") {
                    if("selling_unit" !in data) data["selling_unit"]=data["unit"]
                    if("unit_price_minor" !in data) data["unit_price_minor"]=data["price_minor"]
                }
                val columns=data.keys.toList()
                execute("INSERT INTO $table (${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})",*columns.map { data[it] }.toTypedArray())
            }
        }
        // Reconstruct domain records to enforce cross-field invariants as well as SQL constraints.
        products(tenant.id,includeArchived=true).forEach { item -> item.createdAt?.let { Instant.parse(it) }; require(item.stockScaled==null || (projectedQuantity(requireNotNull(item.stockScaled),item.quantityPrecision)==item.stock && exactProjection(requireNotNull(item.stockScaled),item.quantityPrecision))) { "Backup exact stock does not match its display quantity." }; require(item.quantityPrecision==0 || item.stockScaled!=null) { "Measured goods require exact saved stock." } }
        stockSettingsChanges(tenant.id).forEach { Instant.parse(it.createdAt) }
        products(tenant.id,includeArchived=true).forEach { item ->
            val history=productMetadataChanges(tenant.id,item.id)
            history.forEachIndexed { index,event ->
                Instant.parse(event.createdAt)
                require(event.sequence==index.toLong()+1) { "Backup catalogue history sequence is incomplete." }
                require(index==0 || event.previous==history[index-1].current) { "Backup catalogue history is inconsistent." }
            }
            require(history.isEmpty() || history.last().current==item.metadata()) { "Backup catalogue details do not match their latest history." }
        }
        customers(tenant.id); sales(tenant.id); stockReceipts(tenant.id)
        stockAdjustments(tenant.id).forEach { count -> require(count.quantityPrecision == requireProduct(tenant.id,count.productId).quantityPrecision) { "Backup stock count uses a different quantity precision." } }
        priceHistory(tenant.id); repayments(tenant.id); customerChanges(tenant.id)
        val restoredSellingUnits=sellingUnits(tenant.id,includeArchived=true)
        restoredSellingUnits.forEach { unit ->
            val item=requireProduct(tenant.id,unit.productId)
            require(unit.baseUnit==item.unit && !unit.name.equals(item.unit,true)) { "Backup selling unit does not match the item's base unit." }
            Instant.parse(unit.createdAt)
        }
        require(restoredSellingUnits.filter { !it.archived }.map { it.productId to it.name.lowercase(java.util.Locale.ROOT) }.distinct().size==restoredSellingUnits.count { !it.archived }) { "Backup contains duplicate active selling unit names." }
        val allKeys=products(tenant.id,true).map { it.id }+restoredSellingUnits.map { it.productId+"~"+it.id }
        require(allKeys.distinct().size==allKeys.size) { "Backup contains conflicting item or selling unit identifiers." }
        val allBarcodes=products(tenant.id,true).mapNotNull { it.barcode }+restoredSellingUnits.mapNotNull { it.barcode }
        require(allBarcodes.distinct().size==allBarcodes.size) { "Backup contains duplicate item or selling unit barcodes." }
        sales(tenant.id).forEach { sale ->
            sale.lines.groupBy { it.product.id }.values.forEach { group -> require(group.fold(0L) { sum,line -> Math.addExact(sum,line.baseQuantity) }<=9_007_199_254_740_991L) { "Backup sale exceeds the supported exact quantity range." } }
            sale.lines.forEach { line ->
                val item=requireProduct(tenant.id,line.product.id)
                require(line.product.unit==item.unit && line.product.quantityPrecision==item.quantityPrecision && line.quantityPrecision==(if(line.sellingUnitId==null) item.quantityPrecision else 0)) { "Backup sale base unit does not match its item." }
                line.sellingUnitId?.let { unitId ->
                    val unit=requireSellingUnit(tenant.id,unitId)
                    require(unit.productId==item.id && unit.baseUnit==line.product.unit && unit.name==line.sellingUnit && unit.priceMinor==line.unitPriceMinor && unit.baseUnitsPerUnit==line.baseUnitsPerUnit) { "Backup sale conversion does not match its selling unit." }
                }
                require(line.baseQuantity<=9_007_199_254_740_991L) { "Backup sale exceeds the supported exact quantity range." }
            }
        }
        val restoredUnits=purchaseUnits(tenant.id,includeArchived=true)
        restoredUnits.forEach { unit -> require(unit.baseUnit==requireProduct(tenant.id,unit.productId).unit) { "Backup buying unit does not match the item's selling unit." } }
        require(restoredUnits.map { it.productId to it.name.lowercase(java.util.Locale.ROOT) }.distinct().size==restoredUnits.size) { "Backup contains duplicate buying unit names." }
        stockReceipts(tenant.id).forEach { receipt ->
            require(receipt.baseUnit==requireProduct(tenant.id,receipt.productId).unit && receipt.basePrecision==requireProduct(tenant.id,receipt.productId).quantityPrecision) { "Backup stock receipt uses a different selling unit." }
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
            val reductions=query("SELECT amount_minor FROM credit_return_reductions WHERE tenant_id=? AND customer_id=?",tenant.id,customer.id) {it.getLong(1)}.fold(java.math.BigInteger.ZERO) {sum,amount ->sum.add(java.math.BigInteger.valueOf(amount))}
            val expected=granted.subtract(paid).subtract(reductions).add(debtAdjustmentDelta(tenant.id, customer.id))
            require(expected.signum()>=0 && expected==java.math.BigInteger.valueOf(customer.balanceMinor)) {
                "Backup customer balance does not match credit sales, repayments, credit returns and debt adjustments."
            }
        }
        val restoredAllocations=repaymentAllocations(tenant.id,includeReversed=true)
        restoredAllocations.forEach { allocation ->
            val payment=requireRepayment(tenant.id,allocation.repaymentId)
            val sale=requireSale(tenant.id,allocation.saleId)
            require(allocation.customerId==payment.customerId && allocation.customerId==sale.customerId && sale.creditMinor>0) { "Backup repayment allocation does not belong to the same customer's credit sale." }
            require(allocation.amountMinor<=payment.amountMinor && allocation.amountMinor<=sale.creditMinor) { "Backup repayment allocation exceeds its source payment or receipt." }
            val recorded=Instant.parse(allocation.createdAt)
            require(!recorded.isBefore(Instant.parse(payment.createdAt)) && !recorded.isBefore(Instant.parse(sale.createdAt))) { "Backup assignment predates its payment or sale." }
        }
        val allocationsById=restoredAllocations.associateBy { it.id }
        allocationReversals(tenant.id).forEach { correction ->
            val original=requireNotNull(allocationsById[correction.allocationId]) { "Backup correction refers to an unavailable payment assignment." }
            require(correction.customerId==original.customerId) { "Backup correction belongs to another customer." }
            require(!Instant.parse(correction.createdAt).isBefore(Instant.parse(original.createdAt))) { "Backup correction predates its original payment assignment." }
        }
        repayments(tenant.id).forEach { unallocatedRepayment(tenant.id,it.id) }
        sales(tenant.id).forEach { unallocatedCredit(tenant.id,it.id) }
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
            require(original.externalPayments.isEmpty() && returned.lines.all { line -> original.lines.any { it.key==line.key && it.product.name==line.name && it.sellingUnit==line.unit && it.netUnitPriceMinor==line.unitPriceMinor && it.baseUnitsPerUnit==line.baseUnitsPerUnit && it.product.unit==line.baseUnit && it.quantityPrecision==line.quantityPrecision && it.product.quantityPrecision==line.basePrecision } }) { "Backup return does not match its original sale." }
        }
        sales(tenant.id).forEach { sale ->
            sale.lines.forEach { original ->
                val returned=saleReturns(tenant.id,sale.id).flatMap {it.lines}.filter {it.key==original.key}
                val quantity=returned.fold(0L) {sum,line -> Math.addExact(sum,line.quantity.toLong())}
                val refund=returned.fold(0L) {sum,line -> Math.addExact(sum,line.totalMinor)}
                require(refund==original.cumulativeRefundMinor(Math.toIntExact(quantity))) { "Backup refunds do not reconcile with returned quantities and the original saved price." }
            }
        }
        sales(tenant.id).forEach { sale -> require(returnableQuantities(tenant.id,sale.id).values.all { it>=0 }) { "Backup returns exceed original quantities." } }
        stockReceipts(tenant.id).forEach { supplierOutstanding(tenant.id,it.id) }
        cashShifts(tenant.id).forEach { shift ->
            val calculated=cashEntries(tenant.id,shift.id).fold(java.math.BigInteger.valueOf(shift.openingMinor)) { total,entry -> total.add(java.math.BigInteger.valueOf(entry.amountMinor)) }.longValueExact()
            require(calculated>=0 && (shift.expectedMinor==null || shift.expectedMinor==calculated)) { "Backup cash count snapshot does not match its ledger." }
        }
        query("SELECT * FROM activity_audit WHERE tenant_id=?", tenant.id, map=::auditEvent).forEach { event ->
            Instant.parse(event.createdAt)
            val linked=query("SELECT kind,entity_id,created_at FROM outbox WHERE tenant_id=? AND id=?", tenant.id, event.id) {
                Triple(it.getString("kind"),it.getString("entity_id"),it.getString("created_at"))
            }.singleOrNull()
            require(linked == Triple(event.kind,event.entityId,event.createdAt)) { "Backup activity does not match its original saved event." }
        }
        validateSupplierProfileSnapshot(tenant.id)
        validatePurchaseOrderSnapshot(tenant.id)
        validateInventoryDispositions(tenant.id)
        validateStockCountSessions(tenant.id)
        validateShopProfileSnapshot(tenant.id)
        validateExternalPayments(tenant.id)
        validatePriceLists(tenant.id)
        validateBulkCatalogueUpdates(tenant.id)
        validateSupplierInvoiceSnapshot(tenant.id)
        validateCreditReturns(tenant.id)
        validateQuotations(tenant.id)
        validateLocalImages(tenant.id)
        validateOwnerCash(tenant.id)
        validateMinimumPrices(tenant.id)
        validateRepacking(tenant.id)
        validateExpenseAttachments(tenant.id)
        validateSaleTender(tenant.id)
        validateSaleNotes(tenant.id)
        validateRepaymentReminders(tenant.id)
        validateProductVariants(tenant.id)
        validatePurchaseReturns(tenant.id)
        validateDebtAdjustments(tenant.id)
        validateCashExchanges(tenant.id)
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
    private fun stockReceipt(r:ResultSet)=StockReceipt(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getInt("quantity"),r.getLong("unit_cost_minor"),r.getLong("total_cost_minor"),r.getLong("paid_cash_minor"),r.getString("supplier_name"),r.getString("created_at"),r.getString("base_unit") ?: r.getString("unit"),r.getInt("base_units_per_unit"),r.getString("purchase_unit_id"),r.getInt("quantity_precision"),r.getInt("base_precision"))
    private fun purchaseUnit(r:ResultSet)=PurchaseUnit(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("name"),r.getString("base_unit"),r.getInt("base_units_per_unit"),r.getInt("archived")==1,r.getString("created_at"))
    private fun requirePurchaseUnit(tenantId:String,unitId:String):PurchaseUnit=query("SELECT * FROM purchase_units WHERE tenant_id=? AND id=?",tenantId,unitId,map=::purchaseUnit).singleOrNull() ?: throw IllegalArgumentException("Buying unit not found in this shop.")
    private fun expense(r:ResultSet)=Expense(r.getString("id"),r.getString("tenant_id"),r.getString("category"),r.getString("description"),r.getLong("amount_minor"),r.getString("created_at"))
    private fun cashShift(r:ResultSet)=CashShift(r.getString("id"),r.getString("tenant_id"),r.getLong("opening_minor"),r.getString("opened_at"),r.getString("closed_at"),r.getObject("expected_minor")?.let { (it as Number).toLong() },r.getObject("counted_minor")?.let { (it as Number).toLong() },r.getString("opening_count_sheet")?.let(CashCount::decode),r.getString("closing_count_sheet")?.let(CashCount::decode),r.getInt("closing_blind")==1)
    private fun cashEntry(r:ResultSet)=CashEntry(r.getString("id"),r.getString("tenant_id"),r.getString("shift_id"),r.getString("kind"),r.getLong("amount_minor"),r.getString("description"),r.getString("created_at"))

    private fun normalizeBarcode(barcode:String?):String? = barcode?.trim()?.takeIf { it.isNotEmpty() }?.also { code ->
        require(code.length<=128 && code.none { it.isISOControl() }) { "Use a barcode of at most 128 printable characters." }
    }
    private fun requireBarcodeAvailable(tenantId:String,barcode:String?,exceptId:String?=null) {
        if(barcode!=null) {
            require(query("SELECT id FROM selling_units WHERE tenant_id=? AND barcode=?",tenantId,barcode) { it.getString(1) }.isEmpty()) { "This barcode is already assigned to a selling unit in this shop, including archived units." }
            require(query("SELECT id FROM products WHERE tenant_id=? AND barcode=?",tenantId,barcode) { it.getString(1) }.all { it==exceptId }) { "This barcode is already assigned to another item in this shop, including archived items." }
        }
    }
    private fun customer(r:ResultSet)=Customer(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getLong("balance_minor"),r.getString("phone"),r.getString("address"),r.getObject("credit_limit_minor")?.let { (it as Number).toLong() })
    private fun repaymentAllocation(r:ResultSet)=RepaymentAllocation(r.getString("id"),r.getString("tenant_id"),r.getString("customer_id"),r.getString("repayment_id"),r.getString("sale_id"),r.getLong("amount_minor"),r.getString("created_at"))
    private fun allocationReversal(r:ResultSet)=AllocationReversal(r.getString("id"),r.getString("tenant_id"),r.getString("customer_id"),r.getString("allocation_id"),r.getString("reason"),r.getString("created_at"))
    private fun requireRepayment(tenantId:String,repaymentId:String):CustomerRepayment=query("SELECT * FROM repayments WHERE tenant_id=? AND id=?",tenantId,repaymentId) {
        CustomerRepayment(it.getString("id"),tenantId,it.getString("customer_id"),it.getLong("amount_minor"),it.getString("created_at"))
    }.singleOrNull() ?: throw IllegalArgumentException("Repayment not found in this shop.")
    private fun allocationTotal(tenantId:String,column:String,sourceId:String):java.math.BigInteger {
        require(column=="repayment_id" || column=="sale_id")
        return query("SELECT a.amount_minor FROM repayment_allocations a WHERE a.tenant_id=? AND a.$column=? AND NOT EXISTS(SELECT 1 FROM allocation_reversals r WHERE r.tenant_id=a.tenant_id AND r.allocation_id=a.id)",tenantId,sourceId) { it.getLong(1) }.fold(java.math.BigInteger.ZERO) { total,amount -> total.add(java.math.BigInteger.valueOf(amount)) }
    }
    private fun remainingAllocation(original:Long,allocated:java.math.BigInteger,message:String):Long {
        val remaining=java.math.BigInteger.valueOf(original).subtract(allocated)
        require(remaining.signum()>=0) { message }
        return remaining.longValueExact()
    }
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
    private fun stockSettingsChange(r:ResultSet)=StockSettingsChange(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getObject("previous_reorder_level")?.let { (it as Number).toInt() },r.getObject("reorder_level")?.let { (it as Number).toInt() },r.getString("created_at"))
    private fun stockAdjustment(r:ResultSet)=StockAdjustment(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getDouble("previous_stock"),r.getInt("counted_stock"),r.getString("reason"),r.getString("created_at"),r.getInt("quantity_precision"))
    private fun requireTenant(tenantId: String) { require(query("SELECT id FROM tenants WHERE id=?",tenantId) { it.getString(1) }.isNotEmpty()) { "Shop not found." } }
    private fun product(r: ResultSet)=Product(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getString("unit"),r.getLong("price_minor"),r.getDouble("stock"),r.getString("barcode"),r.getInt("archived")==1,r.getObject("reorder_level")?.let { (it as Number).toInt() },r.getObject("opening_stock")?.let { (it as Number).toDouble() },r.getString("created_at"),r.getString("sku"),r.getString("category"),r.getString("brand"),decodeProductAliases(r.getString("aliases")),r.getInt("favourite")==1,r.getInt("quantity_precision"),(r.getObject("stock_scaled") as? Number)?.toLong())
    private fun auditEvent(row: ResultSet): AuditEvent {
        val tenantId=row.getString("tenant_id")
        val actorId=row.getString("actor_id")
        val actorName=row.getString("actor_name")
        val actorRole=row.getString("actor_role")
        val actor=if(actorId==null && actorName==null && actorRole==null) null else AuditActor(
            tenantId,actorId,requireNotNull(actorName) { "Saved actor has no name." },
            StaffRole.valueOf(requireNotNull(actorRole) { "Saved actor has no role." }),
        )
        return AuditEvent(row.getString("id"),tenantId,row.getString("kind"),row.getString("entity_id"),row.getString("created_at"),actor,row.getString("description"))
    }
    private fun enqueue(tenantId:String,kind:String,entityId:String) {
        // Every caller runs inside the durable mutation transaction. Identity failure rolls
        // back the business event, cash/stock/debt changes, outbox, and activity together.
        val event=AuditEvent(id(),tenantId,kind,entityId,Instant.now().toString(),actorProvider?.invoke(tenantId))
        execute("INSERT INTO outbox(id,tenant_id,kind,entity_id,created_at) VALUES(?,?,?,?,?)",event.id,tenantId,kind,entityId,event.createdAt)
        execute("INSERT INTO activity_audit(id,tenant_id,kind,entity_id,created_at,actor_id,actor_name,actor_role,description) VALUES(?,?,?,?,?,?,?,?,?)",event.id,tenantId,kind,entityId,event.createdAt,event.actor?.id,event.actor?.name,event.actor?.role?.name,event.description)
    }
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
