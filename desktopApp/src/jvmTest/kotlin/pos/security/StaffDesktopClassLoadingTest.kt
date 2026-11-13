package pos.security

import kotlin.test.Test
import kotlin.test.assertNotNull

/** Regression: inline composable/local-function lowering once emitted an illegal JVM method name. */
class StaffDesktopClassLoadingTest {
    @Test fun staffScreenAndApplicationClassesLoadAsValidJvmBytecode() {
        listOf("pos.screens.StaffScreenKt","pos.MainKt","pos.PreviewKt").forEach {
            assertNotNull(Class.forName(it,false,javaClass.classLoader))
        }
    }
}
