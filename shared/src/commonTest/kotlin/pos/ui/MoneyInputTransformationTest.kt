package pos.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.*
import pos.ui.components.MoneyInputTransformation

class MoneyInputTransformationTest {
    @Test fun groupsNairaWithoutChangingKoboOrTheEnteredValue() {
        listOf("0" to "0", "1000" to "1,000", "1234567.50" to "1,234,567.50",
            "1000." to "1,000.", "1000.0" to "1,000.0", "" to "").forEach { (input, expected) ->
            val result = MoneyInputTransformation.filter(AnnotatedString(input))
            assertEquals(expected, result.text.text)
            for (offset in 0..input.length) {
                assertEquals(offset, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset)))
            }
            val offsets = (0..expected.length).map(result.offsetMapping::transformedToOriginal)
            assertEquals(offsets.sorted(), offsets)
            assertTrue(offsets.all { it in 0..input.length })
        }
    }

    @Test fun invalidInputStaysVisibleForValidation() {
        listOf("1,000", "1e3", "12..5", "₦500", "-5", "1.2x").forEach { input ->
            assertEquals(input, MoneyInputTransformation.filter(AnnotatedString(input)).text.text)
        }
    }
}
