package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.DebtAgeBand
import pos.output.DebtAgeingService
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Assignment corrections append history and release coverage, never re-record received money. */
class AllocationReversalIntegrityTest {
    private data class Fixture(
        val shop: Tenant, val customer: Customer, val item: Product,
        val first: Sale, val second: Sale, val payment: CustomerRepayment,
        val assignment: RepaymentAllocation,
    )
    private fun fixture(repo: PosRepository): Fixture {
        val shop = repo.createTenant("Market shop")
        val customer = repo.addCustomer(shop.id, "Amina")
        val item = repo.addProduct(shop.id, "Malt", "bottle", 10_000, 20.0)
        val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
        repo.openCashShift(shop.id, 100_000)
        val first = repo.checkout(shop.id, listOf(CartLine(item, 2)), 0, customer.id, "credit-one", today.plusDays(1).toString())
        val second = repo.checkout(shop.id, listOf(CartLine(item, 2)), 0, customer.id, "credit-two", today.plusDays(100).toString())
        repo.recordRepayment(shop.id, customer.id, 10_000, "payment")
        val payment = repo.repayments(shop.id).single()
        val assignment = repo.allocateRepayment(shop.id, payment.id, first.id, 10_000, "assignment")
        return Fixture(shop, customer, item, first, second, payment, assignment)
    }

    @Test fun undoReleasesBothCapsAndReassignmentPreservesAllFinancialEvents() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val customers = repo.customers(f.shop.id)
            val cash = repo.cashEntries(f.shop.id)
            val stock = repo.products(f.shop.id)
            val payments = repo.repayments(f.shop.id)
            val sales = repo.sales(f.shop.id)
            val pending = repo.pendingSyncCount(f.shop.id)
            val correction = repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo")
            assertEquals(f.assignment.id, correction.allocationId)
            assertEquals(f.customer.id, correction.customerId)
            assertEquals("Wrong receipt selected", correction.reason)
            assertEquals(10_000L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
            assertEquals(20_000L, repo.unallocatedCredit(f.shop.id, f.first.id))
            assertTrue(repo.repaymentAllocations(f.shop.id).isEmpty())
            assertEquals(listOf(f.assignment), repo.repaymentAllocations(f.shop.id, includeReversed = true))
            assertEquals(pending + 1, repo.pendingSyncCount(f.shop.id))
            val replacement = repo.allocateRepayment(f.shop.id, f.payment.id, f.second.id, 10_000, "replacement")
            assertEquals(listOf(replacement), repo.repaymentAllocations(f.shop.id))
            assertEquals(0L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
            assertEquals(10_000L, repo.unallocatedCredit(f.shop.id, f.second.id))
            assertEquals(customers, repo.customers(f.shop.id))
            assertEquals(cash, repo.cashEntries(f.shop.id))
            assertEquals(stock, repo.products(f.shop.id))
            assertEquals(payments, repo.repayments(f.shop.id))
            assertEquals(sales, repo.sales(f.shop.id))
            assertEquals(2, repo.repaymentAllocations(f.shop.id, includeReversed = true).size)
        }
    }

    @Test fun reversalRetryIsStableAndOriginalAllocationRetryNeverReactivatesIt() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val correction = repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo")
            val replacement = repo.allocateRepayment(f.shop.id, f.payment.id, f.second.id, 10_000, "replacement")
            val pending = repo.pendingSyncCount(f.shop.id)
            assertEquals(correction, repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo"))
            assertEquals(f.assignment, repo.allocateRepayment(f.shop.id, f.payment.id, f.first.id, 10_000, "assignment"))
            assertEquals(listOf(replacement), repo.repaymentAllocations(f.shop.id))
            assertEquals(0L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
            assertEquals(1, repo.allocationReversals(f.shop.id).size)
            assertEquals(pending, repo.pendingSyncCount(f.shop.id))
            assertFails { repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Another reason", "undo") }
            assertFails { repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "different-undo") }
            assertFails { repo.reverseRepaymentAllocation(f.shop.id, replacement.id, "Wrong receipt selected", "undo") }
            assertEquals(pending, repo.pendingSyncCount(f.shop.id))
        }
    }

    @Test fun missingAssignmentsInvalidReasonsAndForeignShopReferencesCannotMutateHistory() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val other = repo.createTenant("Other shop")
            val anotherCustomer = repo.addCustomer(f.shop.id, "Other customer")
            val pending = repo.pendingSyncCount(f.shop.id)
            listOf("", "ab", "x".repeat(251), "Wrong\nreceipt").forEach { reason ->
                assertFails { repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, reason, "invalid-reason") }
            }
            assertFails { repo.reverseRepaymentAllocation(f.shop.id, "missing", "Wrong receipt", "missing") }
            assertFails { repo.reverseRepaymentAllocation(other.id, f.assignment.id, "Wrong receipt", "foreign") }
            assertFails { repo.allocationReversals(other.id, f.customer.id) }
            assertTrue(repo.allocationReversals(f.shop.id, anotherCustomer.id).isEmpty())
            assertEquals(listOf(f.assignment), repo.repaymentAllocations(f.shop.id))
            assertTrue(repo.allocationReversals(f.shop.id).isEmpty())
            assertEquals(pending, repo.pendingSyncCount(f.shop.id))
        }
    }

    @Test fun competingUndoRequestsCreateExactlyOneCorrection() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val pending = repo.pendingSyncCount(f.shop.id)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val attempts = (1..2).map { number -> pool.submit<Boolean> {
                    start.await()
                    runCatching { repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo-$number") }.isSuccess
                } }
                start.countDown()
                assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
                assertEquals(1, repo.allocationReversals(f.shop.id).size)
                assertTrue(repo.repaymentAllocations(f.shop.id).isEmpty())
                assertEquals(10_000L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
                assertEquals(pending + 1, repo.pendingSyncCount(f.shop.id))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun unassignedCorrectionSuppressesAgeingThenReassignmentChangesTheCoveredBand() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val asOf = LocalDate.parse(f.first.dueDate).plusDays(100)
            fun review() = DebtAgeingService.build(f.shop.id, repo.customers(f.shop.id), repo.sales(f.shop.id), repo.repayments(f.shop.id), asOf, repo.repaymentAllocations(f.shop.id)).single()
            val before = review()
            assertEquals(10_000L, before.ageing!!.amounts[DebtAgeBand.LATE_OVER_90])
            assertEquals(20_000L, before.ageing!!.amounts[DebtAgeBand.LATE_1_7])
            repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Covered wrong receipt", "undo")
            val unassigned = review()
            assertNull(unassigned.ageing)
            assertNull(unassigned.preciseOverdueMinor)
            assertEquals(10_000L, unassigned.unallocatedRepaymentsMinor)
            repo.allocateRepayment(f.shop.id, f.payment.id, f.second.id, 10_000, "replacement")
            val corrected = review()
            assertEquals(20_000L, corrected.ageing!!.amounts[DebtAgeBand.LATE_OVER_90])
            assertEquals(10_000L, corrected.ageing!!.amounts[DebtAgeBand.LATE_1_7])
            assertEquals(before.currentDebtMinor, corrected.currentDebtMinor)
            assertEquals(corrected.currentDebtMinor, corrected.ageing!!.totalMinor)
        }
    }

    @Test fun backupRestoresOriginalReversalAndReplacementWithEffectiveCaps() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val correction = repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo")
            val replacement = repo.allocateRepayment(f.shop.id, f.payment.id, f.second.id, 10_000, "replacement")
            val snapshot = repo.exportTenantSnapshot(f.shop.id)
            val restored = repo.restoreTenantSnapshot(snapshot, "Recovered shop")
            assertEquals(listOf(correction.copy(tenantId = restored.id)), repo.allocationReversals(restored.id))
            assertEquals(listOf(replacement.copy(tenantId = restored.id)), repo.repaymentAllocations(restored.id))
            assertEquals(2, repo.repaymentAllocations(restored.id, includeReversed = true).size)
            assertEquals(0L, repo.unallocatedRepayment(restored.id, f.payment.id))
            assertEquals(20_000L, repo.unallocatedCredit(restored.id, f.first.id))
            assertEquals(10_000L, repo.unallocatedCredit(restored.id, f.second.id))
            assertEquals(repo.customers(f.shop.id).single().balanceMinor, repo.customers(restored.id).single().balanceMinor)
            assertEquals(repo.cashEntries(f.shop.id).size, repo.cashEntries(restored.id).size)
        }
    }

    @Test fun backupRejectsMalformedReversalAndStillValidatesReversedOriginalSources() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val other = repo.addCustomer(f.shop.id, "Other customer")
            repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo")
            val snapshot = repo.exportTenantSnapshot(f.shop.id)
            val original = snapshot.tables.getValue("allocation_reversals").single()
            val variants = listOf(
                listOf(original + ("allocation_id" to "missing")),
                listOf(original + ("customer_id" to other.id)),
                listOf(original + ("reason" to "")),
                listOf(original + ("reason" to "Wrong\nreceipt")),
                listOf(original + ("created_at" to "not-a-timestamp")),
                listOf(original + ("created_at" to "2000-01-01T00:00:00Z")),
                listOf(original, original + ("id" to "second-undo")),
            )
            val count = repo.tenants().size
            variants.forEach { rows ->
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("allocation_reversals" to rows)), "Invalid recovery") }
                assertEquals(count, repo.tenants().size)
            }
            val allocation = snapshot.tables.getValue("repayment_allocations").single()
            listOf(
                allocation + ("customer_id" to other.id),
                allocation + ("amount_minor" to "10001"),
            ).forEach { bad ->
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("repayment_allocations" to listOf(bad))), "Invalid original") }
                assertEquals(count, repo.tenants().size)
            }
            assertEquals(10_000L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
            assertEquals(1, repo.allocationReversals(f.shop.id).size)
        }
    }

    @Test fun oldBackupWithoutReversalTableKeepsItsOriginalAssignments() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val snapshot = repo.exportTenantSnapshot(f.shop.id)
            val legacy = snapshot.copy(tables = snapshot.tables - "allocation_reversals")
            val restored = repo.restoreTenantSnapshot(legacy, "Old backup")
            assertTrue(repo.allocationReversals(restored.id).isEmpty())
            assertEquals(listOf(f.assignment.copy(tenantId = restored.id)), repo.repaymentAllocations(restored.id))
            assertEquals(0L, repo.unallocatedRepayment(restored.id, f.payment.id))
            assertEquals(30_000L, repo.customers(restored.id).single().balanceMinor)
        }
    }

    @Test fun upgradingExistingDatabaseAndReopeningPreserveAppendOnlyCorrection() {
        val file = Files.createTempFile("pos-reversal-upgrade-", ".db")
        try {
            val f = PosRepository(file.toString()).use { fixture(it) }
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE allocation_reversals") }
            }
            PosRepository(file.toString()).use { repo ->
                assertEquals(listOf(f.assignment), repo.repaymentAllocations(f.shop.id))
                assertTrue(repo.allocationReversals(f.shop.id).isEmpty())
                repo.reverseRepaymentAllocation(f.shop.id, f.assignment.id, "Wrong receipt selected", "undo")
            }
            PosRepository(file.toString()).use { repo ->
                assertTrue(repo.repaymentAllocations(f.shop.id).isEmpty())
                assertEquals(listOf(f.assignment), repo.repaymentAllocations(f.shop.id, includeReversed = true))
                assertEquals("Wrong receipt selected", repo.allocationReversals(f.shop.id).single().reason)
                assertEquals(10_000L, repo.unallocatedRepayment(f.shop.id, f.payment.id))
                assertEquals(30_000L, repo.customers(f.shop.id).single().balanceMinor)
                assertEquals(1, repo.repayments(f.shop.id).size)
                assertEquals(1, repo.cashEntries(f.shop.id).count { it.kind == "repayment" })
            }
        } finally { Files.deleteIfExists(file) }
    }
}
