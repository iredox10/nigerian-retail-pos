package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

class CustomerCreditTest {
    private fun tomorrow()=LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(1).toString()

    @Test fun customerContactIsNormalizedAndEditsPreserveImmutableBeforeAfterHistory() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val c=repo.addCustomer(shop.id,"Amina","0801 234 5678","  Shop 12, market  ",500000)
            assertEquals("+2348012345678",c.phone);assertEquals("Shop 12, market",c.address)
            val next=repo.updateCustomer(shop.id,c.id,"Amina Stores","+234 (802) 345-6789","New stall",900000,"edit-customer")
            assertEquals("+2348023456789",next.phone)
            val pending=repo.pendingSyncCount(shop.id)
            assertEquals(next,repo.updateCustomer(shop.id,c.id,next.name,"08023456789",next.address,900000,"edit-customer"))
            assertEquals(pending,repo.pendingSyncCount(shop.id))
            val change=repo.customerChanges(shop.id,c.id).single()
            assertEquals(c.phone,change.previousPhone);assertEquals(c.creditLimitMinor,change.previousCreditLimitMinor)
            assertEquals(next.phone,change.phone);assertEquals(next.creditLimitMinor,change.creditLimitMinor)
            assertFails {repo.updateCustomer(shop.id,c.id,"Different",null,null,null,"edit-customer")}
            assertFails {repo.addCustomer(shop.id,"Bad number","1234")}
            assertFails {repo.addCustomer(shop.id,"Negative limit",creditLimitMinor=-1)}
            assertEquals(1,repo.customers(shop.id).size)
        }
    }

    @Test fun repositoryEnforcesLiveLimitAgainstAStaleCustomerSnapshotWithoutPartialWrites() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val p=repo.addProduct(shop.id,"Malt","bottle",10000,10.0)
            val stale=repo.addCustomer(shop.id,"Buyer",creditLimitMinor=null)
            repo.updateCustomer(shop.id,stale.id,stale.name,null,null,15000,"set-limit")
            repo.checkout(shop.id,listOf(CartLine(p,1)),0,stale.id,"first",tomorrow())
            val pending=repo.pendingSyncCount(shop.id);val stock=repo.products(shop.id).single().stock
            assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),0,stale.id,"above-limit",tomorrow())}
            assertEquals(stock,repo.products(shop.id).single().stock);assertEquals(1,repo.sales(shop.id).size)
            assertEquals(10000L,repo.customers(shop.id).single().balanceMinor);assertEquals(pending,repo.pendingSyncCount(shop.id))
            repo.checkout(shop.id,listOf(CartLine(p,1)),5000,stale.id,"at-limit",tomorrow())
            assertEquals(15000L,repo.customers(shop.id).single().balanceMinor)
            repo.recordRepayment(shop.id,stale.id,10000)
            repo.checkout(shop.id,listOf(CartLine(p,1)),0,stale.id,"after-repayment",tomorrow())
            assertEquals(15000L,repo.customers(shop.id).single().balanceMinor)
        }
    }

    @Test fun zeroLimitBlocksNewDebtWhileCashAndExistingDebtRepaymentStillWork() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val p=repo.addProduct(shop.id,"Soap","piece",10000,10.0)
            val buyer=repo.addCustomer(shop.id,"Buyer")
            repo.checkout(shop.id,listOf(CartLine(p,1)),0,buyer.id,"debt")
            repo.updateCustomer(shop.id,buyer.id,buyer.name,null,null,0,"block-credit")
            assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),0,buyer.id)}
            repo.checkout(shop.id,listOf(CartLine(p,1)),10000,buyer.id)
            repo.recordRepayment(shop.id,buyer.id,10000)
            assertEquals(0L,repo.customers(shop.id).single().balanceMinor)
            assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),9999,buyer.id)}
            repo.updateCustomer(shop.id,buyer.id,buyer.name,null,null,null,"unlimited")
            repo.checkout(shop.id,listOf(CartLine(p,2)),0,buyer.id)
            assertEquals(20000L,repo.customers(shop.id).single().balanceMinor)
        }
    }

    @Test fun dueDateIsRealFutureOrTodayForNewCreditAndPartOfRetryIdentity() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val p=repo.addProduct(shop.id,"Malt","bottle",10000,10.0);val c=repo.addCustomer(shop.id,"Buyer")
            val today=LocalDate.now(ZoneId.of("Africa/Lagos"))
            listOf("2025-02-29","2026-04-31","12/12/2026",today.minusDays(1).toString()).forEach { date ->
                assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),0,c.id,dueDate=date)}
            }
            assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),10000,c.id,dueDate=tomorrow())}
            assertTrue(repo.sales(shop.id).isEmpty())
            val sale=repo.checkout(shop.id,listOf(CartLine(p,1)),0,c.id,"credit",today.toString())
            repo.updateCustomer(shop.id,c.id,c.name,null,null,0)
            assertEquals(sale.id,repo.checkout(shop.id,listOf(CartLine(p,1)),0,c.id,"credit",today.toString()).id)
            assertFails {repo.checkout(shop.id,listOf(CartLine(p,1)),0,c.id,"credit",tomorrow())}
            assertEquals(today.toString(),repo.sales(shop.id).single().dueDate)
        }
    }

    @Test fun customerEditsAndCreditSalesRejectForeignCustomersAndDoNotMixHistory() {
        PosRepository(":memory:").use { repo ->
            val a=repo.createTenant("A");val b=repo.createTenant("B");val c=repo.addCustomer(a.id,"Buyer")
            val p=repo.addProduct(b.id,"Soap","piece",10000,2.0)
            assertFails {repo.updateCustomer(b.id,c.id,"Bad",null,null,0,"edit")}
            assertFails {repo.checkout(b.id,listOf(CartLine(p,1)),0,c.id,dueDate=tomorrow())}
            assertFails {repo.customerChanges(b.id,c.id)}
            assertTrue(repo.customerChanges(b.id).isEmpty());assertTrue(repo.sales(b.id).isEmpty())
        }
    }

    @Test fun snapshotsKeepContactLimitsAndHistoricDueDatesAndRejectMalformedDetails() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val p=repo.addProduct(shop.id,"Soap","piece",10000,2.0)
            val c=repo.addCustomer(shop.id,"Buyer","08012345678","Stall 9",20000)
            repo.checkout(shop.id,listOf(CartLine(p,1)),0,c.id,"sale",tomorrow())
            repo.updateCustomer(shop.id,c.id,"Buyer new",c.phone,c.address,0,"edit")
            val saved=repo.exportTenantSnapshot(shop.id)
            // A date can now be historical: restore must not apply the new-sale date restriction.
            val historical=saved.copy(tables=saved.tables+("sales" to saved.tables.getValue("sales").map {it+("due_date" to "2020-01-01")}))
            val restored=repo.restoreTenantSnapshot(historical,"Recovered")
            assertEquals("2020-01-01",repo.sales(restored.id).single().dueDate)
            assertEquals(0L,repo.customers(restored.id).single().creditLimitMinor)
            assertEquals(c.phone,repo.customers(restored.id).single().phone)
            assertEquals(1,repo.customerChanges(restored.id).size)
            val before=repo.tenants().size
            listOf("sales" to ("due_date" to "2026-02-30"),"customers" to ("phone" to "08012345678"),"customer_changes" to ("credit_limit_minor" to "-1")).forEach { (table,field) ->
                val forged=saved.copy(tables=saved.tables+(table to saved.tables.getValue(table).map {it+field}))
                assertFails {repo.restoreTenantSnapshot(forged,"Malformed")};assertEquals(before,repo.tenants().size)
            }
            val legacy=saved.copy(tables=saved.tables.filterKeys {it!="customer_changes"}.mapValues { (table,rows) -> rows.map { row -> row.filterKeys { column -> !(table=="customers" && column in setOf("phone","address","credit_limit_minor")) && !(table=="sales" && column=="due_date") } } })
            val legacyShop=repo.restoreTenantSnapshot(legacy,"Legacy")
            assertNull(repo.customers(legacyShop.id).single().creditLimitMinor);assertNull(repo.sales(legacyShop.id).single().dueDate)
        }
    }

    @Test fun olderDatabaseMigratesCustomersAndSalesWithoutDroppingSavedRows() {
        val file=Files.createTempFile("pos-old-customers-",".db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TABLE tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
                s.execute("CREATE TABLE customers(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,balance_minor INTEGER NOT NULL,PRIMARY KEY(tenant_id,id))")
                s.execute("INSERT INTO tenants VALUES('shop','Shop')")
                s.execute("INSERT INTO customers VALUES('buyer','shop','Original',10000)")
                s.execute("CREATE TABLE sales(id TEXT NOT NULL,tenant_id TEXT NOT NULL,total_minor INTEGER NOT NULL,cash_minor INTEGER NOT NULL,credit_minor INTEGER NOT NULL,customer_id TEXT,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
                s.execute("CREATE TABLE sale_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,sale_id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,quantity INTEGER NOT NULL)")
                s.execute("INSERT INTO sales VALUES('sale','shop',10000,0,10000,'buyer','2026-11-01T10:00:00Z')")
                s.execute("INSERT INTO sale_lines(sale_id,tenant_id,product_id,name,unit,price_minor,quantity) VALUES('sale','shop','product','Soap','piece',10000,1)")
            } }
            PosRepository(file.toString()).use { repo ->
                assertNull(repo.customers("shop").single().creditLimitMinor)
                assertNull(repo.sales("shop").single().dueDate)
                assertEquals(10000L,repo.customers("shop").single().balanceMinor)
                repo.updateCustomer("shop","buyer","Updated","08012345678","Stall",10000,"edit")
            }
            PosRepository(file.toString()).use { repo ->
                assertEquals("+2348012345678",repo.customers("shop").single().phone)
                assertEquals(1,repo.customerChanges("shop").size)
            }
        } finally { Files.deleteIfExists(file) }
    }
}
