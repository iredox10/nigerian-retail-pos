package pos.domain

import kotlin.test.*

class CreditSaleReturnTest {
    @Test fun returnValueSeparatesCashFromDebtWithoutInventingPayment() {
        val line=ReturnLine("soap","Soap","piece",2,500)
        val value=SaleReturn("return","shop","sale",listOf(line),600,true,"Customer returned goods","2026-11-14T10:00:00Z",400)
        assertEquals(1000L,value.returnedValueMinor)
        assertEquals(600L,value.refundMinor)
        assertFails {value.copy(creditReductionMinor=500)}
        assertFails {value.copy(creditReductionMinor=-1)}
    }
    @Test fun settledCreditReturnRetainsZeroDebtReductionAndRealCashRefund() {
        val source=CreditReturnReduction("return","shop","sale","customer",0,"2026-11-14T10:00:00Z",1000)
        assertEquals(0L,source.amountMinor);assertEquals(1000L,source.cashRefundMinor)
        assertFails {source.copy(amountMinor=-1)}
        assertFails {source.copy(cashRefundMinor=-1)}
        assertFails {CreditReturnAllowance("sale","customer",100,99)}
    }
}
