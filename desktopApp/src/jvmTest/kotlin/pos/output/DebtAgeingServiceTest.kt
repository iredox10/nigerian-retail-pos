package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class DebtAgeingServiceTest {
    private val today = LocalDate.parse("2026-11-12")
    private fun customer(balance: Long, id: String = "c", tenant: String = "shop") = Customer(id, tenant, "Amina", balance)
    private fun sale(id: String, amount: Long = 10000, due: String? = null, customer: String = "c", tenant: String = "shop") =
        Sale(id, tenant, listOf(CartLine(Product("item", tenant, "Soap", "piece", amount, 10.0), 1)), amount, 0, amount, customer, "2026-11-01T09:00:00Z", due)
    private fun repayment(amount: Long, id: String = "p", customer: String = "c", tenant: String = "shop") =
        CustomerRepayment(id, tenant, customer, amount, "2026-11-10T09:00:00Z")

    @Test fun distinguishesOriginalPastDueCreditFromPartiallyRepaidCurrentDebt() {
        val past = sale("past", 10000, "2026-11-11")
        val future = sale("future", 20000, "2026-11-20")
        val row = DebtAgeingService.build("shop", listOf(customer(25000)), listOf(past, future), listOf(repayment(5000)), today).single()
        assertEquals(25000L, row.currentDebtMinor)
        assertEquals(10000L, row.pastDueOriginalCreditMinor)
        assertEquals(5000L, row.repaymentsTotalMinor)
        assertEquals(listOf(past), row.pastDueSales)
        assertEquals(listOf(future), row.futureDueSales)
        assertEquals(LocalDate.parse("2026-11-11"), row.earliestRecordedDueDate)
        assertTrue(DebtAgeingService.ALLOCATION_NOTICE.contains("not allocated"))
    }

    @Test fun fullyRepaidCustomersDisappearWithoutPretendingToAllocatePayments() {
        assertTrue(DebtAgeingService.build("shop", listOf(customer(0)), listOf(sale("old", due="2026-11-02")), listOf(repayment(10000)), today).isEmpty())
    }

    @Test fun dueTodayIsSeparateFromPastDueAndLegacyUndatedCreditStaysVisible() {
        val sales = listOf(sale("today", due="2026-11-12"), sale("legacy"), sale("future", due="2026-11-13"))
        val row = DebtAgeingService.build("shop", listOf(customer(30000)), sales, emptyList(), today).single()
        assertTrue(row.pastDueSales.isEmpty())
        assertEquals(listOf("today"), row.dueTodaySales.map { it.id })
        assertEquals(listOf("legacy"), row.undatedSales.map { it.id })
        assertEquals(listOf("future"), row.futureDueSales.map { it.id })
        assertEquals(0L, row.pastDueOriginalCreditMinor)
    }

    @Test fun reviewDateClassifiesDueDatesButNeverBackdatesTheCurrentBalance() {
        val row = DebtAgeingService.build("shop", listOf(customer(7500)), listOf(sale("credit", due="2026-11-08")), listOf(repayment(2500)), LocalDate.parse("2026-11-01")).single()
        assertEquals(7500L, row.currentDebtMinor)
        assertTrue(row.pastDueSales.isEmpty())
        assertEquals(1, row.futureDueSales.size)
        assertEquals(2500L, row.repaymentsTotalMinor)
    }

    @Test fun rejectsForeignTenantsRepeatedRecordsMissingCustomersAndIncompleteBalances() {
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000,tenant="other")), emptyList(), emptyList(), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000)), listOf(sale("x",tenant="other")), emptyList(), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(0)), emptyList(), listOf(repayment(1,tenant="other")), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000), customer(10000)), listOf(sale("x")), emptyList(), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(20000)), listOf(sale("x"),sale("x")), emptyList(), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000)), listOf(sale("x",customer="missing")), emptyList(), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000)), listOf(sale("x")), listOf(repayment(1,customer="missing")), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(9000)), listOf(sale("x")), emptyList(), today) }
    }

    @Test fun checksMalformedDatesEvenWhenCustomerHasNoRemainingDebt() {
        assertFails { DebtAgeingService.build("shop", listOf(customer(0)), listOf(sale("x",due="2026-02-30")), listOf(repayment(10000)), today) }
        assertFails { DebtAgeingService.build("shop", listOf(customer(10000)), listOf(sale("x",due="2026-1-2")), emptyList(), today) }
    }

    @Test fun aggregateOverflowIsRejectedInsteadOfWrapped() {
        assertFails { DebtAgeingService.build("shop", listOf(customer(Long.MAX_VALUE)), listOf(sale("first",Long.MAX_VALUE,due="2026-11-01"),sale("second",1,due="2026-11-02")), listOf(repayment(1)), today) }
    }

    @Test fun sortsPastDueReviewFirstAndKeepsUndatedDebtorsWithoutInventedDates() {
        val sales=listOf(sale("undated", customer="a"),sale("past",due="2026-11-10",customer="b"),sale("future",due="2026-11-20",customer="d"))
        val rows=DebtAgeingService.build("shop",listOf(customer(10000,"a"),customer(10000,"b"),customer(10000,"d")),sales,emptyList(),today)
        assertEquals(listOf("b","d","a"),rows.map { it.customer.id })
        assertNull(rows.last().earliestRecordedDueDate)
        assertEquals(1,rows.last().undatedSales.size)
    }
}
