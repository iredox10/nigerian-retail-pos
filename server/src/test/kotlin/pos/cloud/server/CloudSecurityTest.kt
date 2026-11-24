package pos.cloud.server

import kotlin.test.*
import org.junit.jupiter.api.Test

class CloudSecurityTest {
    @Test fun cloudPasswordsUseIndependentRandomSaltsAndRejectWrongSecrets() {
        val first=CloudPassword.create("a-long-test-password")
        val second=CloudPassword.create("a-long-test-password")
        assertNotEquals(first.first,second.first);assertNotEquals(first.second,second.second)
        assertTrue(CloudPassword.matches("a-long-test-password",first.first,first.second))
        assertFalse(CloudPassword.matches("another-long-password",first.first,first.second))
        assertFailsWith<CloudApiProblem> {CloudPassword.create("short")}
    }
    @Test fun randomBearerTokensHaveNoEmbeddedIdentityAndAreStoredByHash() {
        val tokens=(0 until 20).map {randomSecret()}
        assertEquals(tokens.size,tokens.toSet().size)
        assertTrue(tokens.all {it.matches(Regex("[A-Za-z0-9_-]{43}"))})
        assertTrue(tokens.all {sha256(it).matches(Regex("[0-9a-f]{64}")) && sha256(it)!=it})
    }
    @Test fun namesAndIdentifiersRejectControlCharacters() {
        assertEquals("Counter",safeName(" Counter ",100))
        assertFailsWith<CloudApiProblem> {safeName("Counter\n2")}
        assertFailsWith<CloudApiProblem> {safeId(" ")}
        assertFailsWith<CloudApiProblem> {uuid("not-an-id")}
        assertFailsWith<CloudApiProblem> {safeId("x".repeat(201))}
    }
}
