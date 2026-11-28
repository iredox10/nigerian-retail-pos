package pos.data

import java.math.BigInteger

/** BigInteger.longValueExact is absent on Android 11; retain its exact signed range check. */
internal fun BigInteger.toLongExact(): Long {
    if (bitLength() > 63) throw ArithmeticException("BigInteger out of long range")
    return toLong()
}
