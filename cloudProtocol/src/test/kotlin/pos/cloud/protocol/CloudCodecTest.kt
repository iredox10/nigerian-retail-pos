package pos.cloud.protocol

import kotlin.test.*

class CloudCodecTest {
    @Test fun jsonRoundTripPreservesExactMoneyUnicodeAndNull() {
        val snapshot=CloudSnapshot("business","Ẹ káàbọ̀ 🙌",mapOf("records" to listOf(mapOf("amount_minor" to "9007199254740993","optional" to null,"reference" to "=quoted\n\"\\:"))))
        val text=CloudCodec.encode(snapshot)
        assertContains(text,"\"9007199254740993\"")
        assertEquals(snapshot,CloudCodec.decode<CloudSnapshot>(text))
    }
    @Test fun fingerprintsIgnoreObjectMapInsertionOrderButNotNullOrContent() {
        val first=CloudSnapshot("b","Shop",mapOf("t" to listOf(linkedMapOf("a" to "1","b" to null))))
        val second=first.copy(tables=mapOf("t" to listOf(linkedMapOf("b" to null,"a" to "1"))))
        assertEquals(CloudCodec.fingerprint(first),CloudCodec.fingerprint(second))
        assertNotEquals(CloudCodec.fingerprint(first),CloudCodec.fingerprint(second.copy(tenantName="Changed")))
        assertEquals(64,CloudCodec.fingerprint(first).length)
    }
    @Test fun unknownFieldsMalformedJsonAndUnsupportedSchemasFailClosed() {
        assertFails {CloudCodec.decode<CloudSnapshot>("{\"tenantId\":\"b\",\"tenantName\":\"Shop\",\"tables\":{},\"secret\":true}")}
        assertFails {CloudCodec.decode<CloudSnapshot>("{tenantId:'b'}")}
        assertFails {CloudSchema(2,emptyList())}
        assertFails {CloudColumn("unsafe;drop","TEXT",1)}
        val id=CloudColumn("id","TEXT",1)
        assertFails {CloudTable("t",listOf(id),listOf("id"),setOf("id"))}
        assertFails {CloudTable("t",listOf(id),listOf("id"),additiveColumns=setOf("id"))}
    }
    @Test fun hugeCursorRangesAndGapsFailWithoutAllocatingTheRange() {
        assertFails {CloudPullReply(0,Long.MAX_VALUE,Long.MAX_VALUE,emptyList())}
        assertFails {CloudPullReply(0,2,2,listOf(CloudChangeEnvelope(2,"op","device",CloudDelta(emptyList()))))}
        assertEquals(Long.MAX_VALUE,CloudPullReply(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,emptyList()).nextRevision)
        val changes=(1L..2).map {CloudChangeEnvelope(it,"op$it","device",CloudDelta(emptyList()))}
        assertEquals(2,CloudPullReply(0,2,3,changes).changes.size)
    }
    @Test fun replyCannotClaimSuccessWithConflicts() {
        val issue=CloudConflict("sales","id",emptyList(),"Needs review")
        assertFails {CloudPushReply("op","accepted",1,listOf(issue))}
        assertFails {CloudPushReply("op","conflict",1)}
        assertEquals("conflict",CloudPushReply("op","conflict",1,listOf(issue)).status)
    }
}
