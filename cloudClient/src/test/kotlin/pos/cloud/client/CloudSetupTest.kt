package pos.cloud.client

import kotlin.test.*

class CloudSetupTest {
    @Test fun printedCodeAndCameraQrRoundTripWithoutAnyCredentials() {
        val code="ABCDE-23456"
        val matrix=CloudSetup.qr(code)
        val scale=8;val size=matrix.size*scale
        val pixels=IntArray(size*size) {i ->if(matrix[(i/size)/scale][(i%size)/scale])0xff000000.toInt() else 0xffffffff.toInt()}
        assertEquals(code,CloudSetup.readQr(size,size,pixels))
        assertEquals(code,CloudSetup.pairingCode("abcde 23456"))
        assertEquals(code,CloudSetup.pairingCode("retailpos:pair:$code"))
        assertFailsWith<IllegalArgumentException> {CloudSetup.pairingCode("https://attacker.test/?token=secret")}
        assertFailsWith<IllegalArgumentException> {CloudSetup.pairingCode("ABCDE-01234")}
    }
}
