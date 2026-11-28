package pos.cloud.server

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtpConfigurationTest {
    private val complete = mapOf("CLOUD_MAIL_HOST" to "smtp.resend.com", "CLOUD_MAIL_PORT" to "587",
        "CLOUD_MAIL_USERNAME" to "resend", "CLOUD_MAIL_PASSWORD" to "test-only-not-a-real-key",
        "CLOUD_MAIL_FROM" to "Retail POS <accounts@example.test>")
    private fun configured(values: Map<String, String>) = SmtpCloudEmailDelivery(MockEnvironment().apply {
        values.forEach { (name, value) -> setProperty(name, value) }
    })
    @Test fun completeAuthenticatedConfigurationIsReadyWithoutSendingAnyEmail() {
        assertTrue(configured(complete).available())
        assertTrue(configured(complete - "CLOUD_MAIL_PORT").available())
    }
    @Test fun missingOrBlankSenderCredentialsAndHostLeaveRecoveryUnavailable() {
        for (key in complete.keys - "CLOUD_MAIL_PORT") {
            assertFalse(configured(complete - key).available(), key)
            assertFalse(configured(complete + (key to "  ")).available(), key)
        }
        assertFalse(configured(emptyMap()).available())
    }
    @Test fun malformedPortHostAndSenderStayUnavailableRatherThanFailingCapabilityRequest() {
        for (port in listOf("", "abc", "0", "65536", "-1")) assertFalse(configured(complete + ("CLOUD_MAIL_PORT" to port)).available())
        for (host in listOf("https://smtp.resend.com", "smtp..example.test", "smtp.example.test\r\nother", "-bad.example.test"))
            assertFalse(configured(complete + ("CLOUD_MAIL_HOST" to host)).available())
        for (sender in listOf("not an address", "a@example.test,b@example.test", "a@example.test\r\nBcc: b@example.test"))
            assertFalse(configured(complete + ("CLOUD_MAIL_FROM" to sender)).available())
    }
}
