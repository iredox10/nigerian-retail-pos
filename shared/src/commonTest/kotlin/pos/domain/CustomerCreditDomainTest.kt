package pos.domain

import kotlin.test.*

class CustomerCreditDomainTest {
    @Test fun phoneFormattingDoesNotClaimNumberVerification() {
        assertEquals("+2348012345678",normalizeNigerianPhone("0801 234-5678"))
        assertEquals("+2348012345678",normalizeNigerianPhone(" +234 (801) 234-5678 "))
        assertNull(normalizeNigerianPhone(" "))
        listOf("+23408012345678","0801234567","080123456789","0801x345678","08012345678\n123","+1 202 555 0123").forEach {assertFails {normalizeNigerianPhone(it)}}
    }
    @Test fun creditDueDatesRespectCalendarLeapYears() {
        listOf("2024-02-29","2000-02-29","2026-11-12").forEach {assertTrue(isValidCreditDueDate(it))}
        listOf("1900-02-29","2026-02-29","2026-04-31","0000-01-01","2026-00-01","2026-01-00","2026-1-1").forEach {assertFalse(isValidCreditDueDate(it))}
    }
    @Test fun pureCheckoutEnforcesCreditLimitButNotCashSalesAboveExistingDebt() {
        val p=Product("p","shop","Soap","piece",10000,10.0)
        val c=Customer("c","shop","Buyer",15000,creditLimitMinor=20000)
        assertFails {checkout("shop","sale",listOf(CartLine(p,1)),0,c,true,"2026-11-12T10:00:00Z")}
        assertEquals(5000L,checkout("shop","sale",listOf(CartLine(p,1)),5000,c,true,"2026-11-12T10:00:00Z",dueDate="2026-11-13").sale.creditMinor)
        val blocked=c.copy(creditLimitMinor=0)
        assertEquals(0L,checkout("shop","cash",listOf(CartLine(p,1)),10000,blocked,true,"2026-11-12T10:00:00Z").sale.creditMinor)
    }
}
