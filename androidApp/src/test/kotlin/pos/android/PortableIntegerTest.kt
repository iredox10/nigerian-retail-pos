package pos.android

import java.math.BigInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import pos.data.toLongExact

class PortableIntegerTest {
    @Test fun portableConversionMatchesJdkExactnessIncludingSignedBoundaries() {
        val values = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (value in values) assertEquals(BigInteger.valueOf(value).longValueExact(), BigInteger.valueOf(value).toLongExact())
        for (value in listOf(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE), BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), BigInteger.ONE.shiftLeft(200), BigInteger.ONE.shiftLeft(200).negate())) {
            assertFailsWith<ArithmeticException> { value.longValueExact() }
            assertFailsWith<ArithmeticException> { value.toLongExact() }
        }
    }
}
