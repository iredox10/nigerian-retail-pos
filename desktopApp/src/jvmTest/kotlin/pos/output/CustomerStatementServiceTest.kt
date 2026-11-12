package pos.output

import kotlin.test.*
import pos.domain.*

class CustomerStatementServiceTest {
    private val customer = Customer("customer", "shop", "Amina", 125L)
    private fun sale(id: String, credit: Long = 200, customerId: String = "customer", tenantId: String = "shop", date: String = "2026-11-12T09:00:00Z") =
        Sale(id, tenantId, listOf(CartLine(Product("item", tenantId, "Soap", "piece", 300, 10.0), 1)), 300, 300 - credit, credit, customerId, date)
    private fun payment(id: String = "payment", amount: Long = 75, customerId: String = "customer", tenantId: String = "shop", date: String = "2026-11-12T10:00:00Z") =
        CustomerRepayment(id, tenantId, customerId, amount, date)

    @Test fun separatesOriginalCreditFromLaterPaymentsWithExactKobo() {
        val statement = CustomerStatementService.build("shop", customer, listOf(sale("sale")), listOf(payment()))
        assertEquals(300L, statement.originalSalesMinor)
        assertEquals(200L, statement.creditGrantedMinor)
        assertEquals(75L, statement.repaidMinor)
        assertEquals(125L, statement.customer.balanceMinor)
        assertEquals(listOf("sale", "payment"), statement.events.map { it.id })
        assertNotNull(statement.events[0].sale)
        assertNotNull(statement.events[1].repayment)
    }

    @Test fun customerSelectionDoesNotMixOtherCustomersHistory() {
        val statement = CustomerStatementService.build("shop", customer,
            listOf(sale("mine"), sale("other-sale", 150, "other")), listOf(payment(), payment("other-payment", 90, "other")))
        assertEquals(listOf("mine", "payment"), statement.events.map { it.id })
        assertEquals(200L, statement.creditGrantedMinor)
    }

    @Test fun rejectsForeignTenantAndDuplicateOrIncompleteHistory() {
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("elsewhere", customer, emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("shop", customer, listOf(sale("foreign", tenantId = "elsewhere")), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("shop", customer, listOf(sale("sale")), listOf(payment(tenantId = "elsewhere"))) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("shop", customer, listOf(sale("sale"), sale("sale")), listOf(payment())) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("shop", customer, listOf(sale("sale")), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.build("shop", customer.copy(balanceMinor = 0), listOf(sale("sale")), listOf(payment(amount = 201))) }
    }

    @Test fun exportsFormulaSafeNamesAndExactNumericAmounts() {
        val statement = CustomerStatementService.build("shop", customer.copy(name = "=SUM(A1:A2)"), listOf(sale("sale")), listOf(payment()))
        val csv = CustomerStatementService.csv(statement)
        assertContains(csv, "\"'=SUM(A1:A2)\"")
        assertContains(csv, "\"3.00\",\"1.00\",\"2.00\",\"0.00\"")
        assertContains(csv, "\"0.75\"")
        assertContains(csv, "\"Current debt — all dates\"")
        assertContains(csv, "\"1.25\"")
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.csv(statement.copy(creditGrantedMinor = 999)) }
        assertFailsWith<IllegalArgumentException> { CustomerStatementService.csv(statement.copy(events = statement.events + CustomerStatementEvent("foreign", "2026-11-12T11:00:00Z", sale = sale("foreign", customerId = "other")))) }
    }

    @Test fun chronologicalHistoryUsesInstantsAndHasDeterministicTiesWithoutInventedRunningBalance() {
        val statement = CustomerStatementService.build("shop", customer,
            listOf(sale("sale", date = "2026-11-12T10:00:00+01:00")), listOf(payment(date = "2026-11-12T09:00:00Z")))
        assertEquals(listOf("sale", "payment"), statement.events.map { it.id })
        val csv = CustomerStatementService.csv(statement)
        assertFalse(csv.contains("Running balance"))
    }
}
