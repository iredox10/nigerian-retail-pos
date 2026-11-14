package pos.domain
import kotlin.test.*
class ExternalPaymentTest {
    @Test fun exactPortionsAndUnverifiedLabels() {
        val p=ExternalPayment("transfer",12345,"TX-001","Shop bank")
        assertEquals("Transfer not confirmed",p.label)
        assertEquals(12345L,externalPaymentTotal(listOf(p)))
        assertFailsWith<IllegalArgumentException> {externalPaymentTotal(listOf(p,p))}
        assertFails {externalPaymentTotal(listOf(p.copy(amountMinor=Long.MAX_VALUE),p.copy(reference="other")))}
    }
    @Test fun invalidPortionsNeverBecomePayment() {
        assertFails {ExternalPayment("verified",1,"ref","bank")}
        assertFails {ExternalPayment("transfer",0,"ref","bank")}
        assertFails {ExternalPayment("card",1," ","bank")}
        assertFails {ExternalPayment("card",1,"ref","bank\n")}
        assertFails {externalPaymentTotal((1..11).map {ExternalPayment("card",1,"ref-$it","Terminal")})}
        assertEquals(0L,externalPaymentTotal(emptyList()))
    }
}
