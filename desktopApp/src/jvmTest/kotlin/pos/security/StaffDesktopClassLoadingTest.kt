package pos.security

import kotlin.test.Test
import kotlin.test.assertNotNull

/** Regression: inline composable/local-function lowering once emitted an illegal JVM method name. */
class StaffDesktopClassLoadingTest {
    @Test fun desktopScreensAndApplicationClassesLoadAsValidJvmBytecode() {
        listOf("pos.screens.StaffScreenKt","pos.screens.AuditScreenKt",
            "pos.screens.ProductManagementPanelKt","pos.screens.StockScreenKt","pos.screens.SellScreenKt",
            "pos.screens.SuppliersScreenKt","pos.screens.CustomerScreenKt","pos.screens.CreditReviewScreenKt",
            "pos.screens.BulkCatalogueUpdateScreenKt","pos.screens.PriceListsScreenKt",
            "pos.screens.QuotationsScreenKt","pos.screens.SalesScreenKt",
            "pos.MainKt","pos.PreviewKt").forEach {
            assertNotNull(Class.forName(it,false,javaClass.classLoader))
        }
    }
}
