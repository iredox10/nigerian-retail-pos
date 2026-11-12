package pos.output

import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.*
import pos.data.PosRepository
import pos.domain.CartLine

class BackupServiceTest {
    private val password = "correct backup password"
    private fun destination() = Files.createTempDirectory("retail-backup-test-").resolve("shop.retailbackup")

    @Test fun encryptedBackupContainsOnlySelectedShopAndNoVisibleBusinessNames() {
        PosRepository(":memory:").use { repo ->
            val selected = repo.createTenant("Private Merchant Name Selected")
            val other = repo.createTenant("Another Merchant Must Stay Private")
            repo.addProduct(selected.id, "Selected Confidential Malt Product", "bottle", 50000, 12.0)
            repo.addProduct(other.id, "Other Confidential Rice Product", "bag", 500000, 5.0)
            val file = destination()
            BackupService.create(repo, selected.id, password, file)
            val ciphertext = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
            assertFalse(ciphertext.contains(selected.name))
            assertFalse(ciphertext.contains("Selected Confidential Malt Product"))
            assertFalse(ciphertext.contains(other.name))
            assertFalse(ciphertext.contains("Other Confidential Rice Product"))
            val snapshot = BackupService.read(file, password)
            assertEquals(selected, snapshot.tenant)
            assertEquals(listOf("Selected Confidential Malt Product"), snapshot.tables.getValue("products").map { it["name"] })
            snapshot.tables.values.flatten().forEach { assertEquals(selected.id, it["tenant_id"]) }
        }
    }

    @Test fun wrongPasswordAndTamperedCiphertextCannotBeRead() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Protected shop")
            val file = destination()
            BackupService.create(repo, tenant.id, password, file)
            assertFailsWith<IllegalArgumentException> { BackupService.read(file, "incorrect password") }
            val bytes = Files.readAllBytes(file)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(file, bytes)
            assertFailsWith<IllegalArgumentException> { BackupService.read(file, password) }
            assertEquals(listOf(tenant), repo.tenants())
        }
    }

    @Test fun restoreUsesNewTenantAndPreservesHistoricalLinks() {
        PosRepository(":memory:").use { repo ->
            val original = repo.createTenant("Original shop")
            val product = repo.addProduct(original.id, "Malt", "bottle", 50000, 10.0)
            val customer = repo.addCustomer(original.id, "Amina")
            val shift = repo.openCashShift(original.id, 200000)
            val sale = repo.checkout(original.id, listOf(CartLine(product, 2)), 50000, customer.id)
            repo.recordRepayment(original.id, customer.id, 20000)
            val receipt = repo.receiveStock(original.id, product.id, 3, 10000, 0, "Wholesaler")
            repo.closeCashShift(original.id, shift.id, 270000)
            val file = destination()
            BackupService.create(repo, original.id, password, file)
            val snapshot = BackupService.read(file, password)
            val restored = repo.restoreTenantSnapshot(snapshot, "Recovered shop")
            assertNotEquals(original.id, restored.id)
            assertEquals("Recovered shop", restored.name)
            assertEquals(11.0, repo.products(restored.id).single().stock)
            val restoredSale = repo.sales(restored.id).single()
            assertEquals(sale.id, restoredSale.id)
            assertEquals(customer.id, restoredSale.customerId)
            assertEquals(product.id, restoredSale.lines.single().product.id)
            assertEquals(restored.id, restoredSale.tenantId)
            assertEquals(restored.id, restoredSale.lines.single().product.tenantId)
            assertEquals(30000L, repo.customers(restored.id).single().balanceMinor)
            assertEquals(receipt.id, repo.stockReceipts(restored.id).single().id)
            assertEquals(270000L, repo.cashShifts(restored.id).single().expectedMinor)
            assertEquals(11.0, repo.products(original.id).single().stock)
            // Existing historical identifiers must not collide across tenants or repeated restores.
            val second = repo.restoreTenantSnapshot(snapshot, "Second recovery")
            assertEquals(sale.id, repo.sales(second.id).single().id)
        }
    }

    @Test fun existingDestinationIsNeverOverwritten() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val file = destination()
            val original = "Existing valuable file".toByteArray()
            Files.write(file, original)
            assertFailsWith<IllegalArgumentException> { BackupService.create(repo, tenant.id, password, file) }
            assertContentEquals(original, Files.readAllBytes(file))
        }
    }

    @Test fun truncatedTrailingAndMaliciousLengthFilesFailClosed() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val file = destination()
            BackupService.create(repo, tenant.id, password, file)
            val original = Files.readAllBytes(file)
            for (cutoff in listOf(0, 8, original.size - 1)) {
                Files.write(file, original.copyOf(cutoff))
                assertFails { BackupService.read(file, password) }
            }
            Files.write(file, original + byteArrayOf(1))
            assertFails { BackupService.read(file, password) }
            val malicious = original.copyOf()
            val lengthOffset = "RETAILPOS-BACKUP-1".toByteArray(Charsets.US_ASCII).size + 24 + 12
            ByteBuffer.wrap(malicious).putInt(lengthOffset, Int.MAX_VALUE)
            Files.write(file, malicious)
            assertFailsWith<IllegalArgumentException> { BackupService.read(file, password) }
        }
    }

    @Test fun foreignTenantRowsCannotBeRestoredAndDoNotCreateWorkspace() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            repo.addProduct(tenant.id, "Malt", "bottle", 50000, 10.0)
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables.toMutableMap()
            tables["products"] = tables.getValue("products").map { it + ("tenant_id" to "foreign-shop") }
            val before = repo.tenants()
            assertFailsWith<IllegalArgumentException> { repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Must not exist") }
            assertEquals(before, repo.tenants())
        }
    }

    @Test fun invalidDomainRowsRollBackEntireRestoreIncludingEarlierRows() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            repo.addProduct(tenant.id, "Valid product", "piece", 50000, 10.0)
            repo.addCustomer(tenant.id, "Valid customer")
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables.toMutableMap()
            // Empty customer name passes SQL NOT NULL, but must fail domain validation after inserts.
            tables["customers"] = tables.getValue("customers").map { it + ("name" to "") }
            val before = repo.tenants()
            assertFailsWith<IllegalArgumentException> { repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Must roll back") }
            assertEquals(before, repo.tenants())
            assertEquals(1, repo.products(tenant.id).size)
            assertEquals("Valid customer", repo.customers(tenant.id).single().name)
        }
    }

    @Test fun restoredLineQuantityCannotTruncateFromLongToInt() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val product = repo.addProduct(tenant.id, "Malt", "bottle", 50000, 10.0)
            repo.checkout(tenant.id, listOf(CartLine(product, 1)), 50000)
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables.toMutableMap()
            tables["sale_lines"] = tables.getValue("sale_lines").map { it + ("quantity" to "4294967297") }
            val before = repo.tenants()
            assertFailsWith<IllegalArgumentException> { repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Corrupt quantity") }
            assertEquals(before, repo.tenants())
        }
    }
}
