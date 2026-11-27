package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigRedactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `protocol secrets and header values are redacted at every level`() {
        val redacted = ConfigRedaction.redactConfig(
            """
            {
              "outbounds": [{
                "type": "vless",
                "server": "example.com",
                "uuid": "live-uuid",
                "encryption": "mlkem768x25519plus",
                "auth_key": "auth-secret",
                "private_key_passphrase": "passphrase-secret",
                "tls": {
                  "private_key": "private-key",
                  "headers": {
                    "Authorization": "Bearer secret",
                    "X-Custom-Credential": "custom-secret",
                    "Host": "example.com"
                  }
                },
                "transport": {"headers": {"X-API-Key": "api-secret"}}
              }],
              "route": {"rules": [{"action": "reject", "password": "rule-secret"}]}
            }
            """.trimIndent(),
        )
        val root = JSONObject(redacted)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val tls = outbound.getJSONObject("tls")

        assertEquals("example.com", outbound.getString("server"))
        assertEquals("reject", root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        listOf("live-uuid", "auth-secret", "passphrase-secret", "private-key", "Bearer secret", "custom-secret", "api-secret", "rule-secret")
            .forEach { secret -> assertFalse("Leaked secret: $secret", redacted.contains(secret)) }
        listOf("uuid", "encryption", "auth_key", "private_key_passphrase")
            .forEach { key -> assertEquals("redacted", outbound.optString(key)) }
        assertEquals("redacted", tls.getString("private_key"))
        assertEquals(
            "redacted",
            root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("password"),
        )
        assertEquals("redacted", tls.getJSONObject("headers").getString("Authorization"))
        assertEquals("redacted", tls.getJSONObject("headers").getString("X-Custom-Credential"))
        assertEquals("redacted", outbound.getJSONObject("transport").getJSONObject("headers").getString("X-API-Key"))
    }

    @Test
    fun `JVM crash snapshot is redacted before the report file is written`() {
        val reportDir = temporaryFolder.newFolder("report")
        val raw = """{"outbounds":[{"uuid":"live-secret","server":"example.com"}]}"""

        ConfigRedaction.writeRedactedConfig(reportDir, raw)

        val written = File(reportDir, "configuration.json").readText()
        assertFalse(written.contains("live-secret"))
        assertTrue(written.contains("example.com"))
        assertTrue(File(reportDir, ConfigRedaction.MARKER_FILE_NAME).isFile)
    }

    @Test
    fun `invalid JSON never falls back to writing a raw configuration`() {
        val reportDir = temporaryFolder.newFolder("report")
        val raw = """{"uuid":"live-secret""""

        assertThrows(Exception::class.java) {
            ConfigRedaction.writeRedactedConfig(reportDir, raw)
        }
        assertFalse(File(reportDir, "configuration.json").exists())

        File(reportDir, "configuration.json").writeText(raw)
        ConfigRedaction.redactReport(reportDir)
        assertFalse(File(reportDir, "configuration.json").exists())
    }
}
