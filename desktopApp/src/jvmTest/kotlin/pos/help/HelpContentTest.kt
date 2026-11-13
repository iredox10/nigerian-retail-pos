package pos.help

import kotlin.test.*

class HelpContentTest {
    @Test fun searchFindsTaskDetailsAndSupportsSeveralWordsWithoutInternet() {
        assertTrue(HelpContent.search("CARTON bottles").any { it.id == "stock-units" })
        assertTrue(HelpContent.search("  backup password  ").any { it.id == "backup" })
        assertTrue(HelpContent.search("printer saved").any { it.id == "cash-sale" })
        assertTrue(HelpContent.search("unrecognised-topic-123").isEmpty())
        assertEquals(HelpContent.articles, HelpContent.search(" \n "))
    }

    @Test fun guidanceKeepsPaymentVerificationAndBackupBoundariesExplicit() {
        val offline = HelpContent.articles.single { it.id == "offline" }.steps.joinToString(" ")
        assertContains(offline, "verification services are not connected")
        assertContains(offline, "does not mean another computer")
        val credit = HelpContent.articles.single { it.id == "credit" }
        assertContains(credit.summary, "short payment alone does not create debt")
    }
}
