package dev.opencollab.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TlsIdentityTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `a saved certificate loads back with the same fingerprint`() {
        val id = TlsIdentity.ephemeral("relay", validityDays = 30)
        val file = tmp.resolve("certs/relay.p12")
        id.saveAsPkcs12(file, "pw".toCharArray())
        val loaded = TlsIdentity.fromPkcs12(file, "pw".toCharArray())
        assertEquals(id.fingerprint, loaded.fingerprint)
        loaded.serverContext()
    }

    @Test
    fun `a certificate saved without a password loads without one`() {
        val id = TlsIdentity.ephemeral("relay")
        val file = tmp.resolve("relay.p12")
        id.saveAsPkcs12(file, CharArray(0))
        assertEquals(id.fingerprint, TlsIdentity.fromPkcs12(file, CharArray(0)).fingerprint)
    }
}
