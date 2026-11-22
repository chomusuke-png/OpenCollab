package dev.opencollab.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddressMaskTest {
    @Test
    fun `addresses keep only their last part and ports are hidden`() {
        assertEquals("•••.•••.•••.54", AddressMask.mask("192.168.4.54"))
        assertEquals("from •••.•••.•••.54:•••", AddressMask.mask("from 192.168.4.54:51234"))
        assertEquals("Session started. Endpoints: wss://•••.•••.•••.12:•••, wss://•••.•••.•••.7:•••",
            AddressMask.mask("Session started. Endpoints: wss://192.168.1.12:47800, wss://203.0.113.7:47800"))
        assertEquals("wss://[•••:•••:7]:•••", AddressMask.mask("wss://[2001:db8::7]:47800"))
        assertEquals("from •••:•••:7", AddressMask.mask("from fe80::7"))
    }

    @Test
    fun `host names keep their domain, local addresses and other text stay`() {
        assertEquals("wss://my•••.duckdns.org:•••", AddressMask.mask("wss://myhome.duckdns.org:47800"))
        assertEquals("wss://DE•••:•••", AddressMask.mask("wss://DESKTOP-AB12:47800"))
        assertEquals("wss://localhost:47800 and 127.0.0.1", AddressMask.mask("wss://localhost:47800 and 127.0.0.1"))
        val fingerprint = "Certificate fingerprint: 50:95:CE:CB:FA:42:CC:46:14:BC:D5:66:E9:84:20:E1"
        assertEquals(fingerprint, AddressMask.mask(fingerprint))
        assertEquals("OpenCollab 3.2.1 at 12:30:05", AddressMask.mask("OpenCollab 3.2.1 at 12:30:05"))
        assertEquals("transport 2", AddressMask.mask("transport 2"))
    }

    @Test
    fun `ports named in a sentence are hidden`() {
        assertEquals("router must forward TCP ••• to this computer, port •••",
            AddressMask.mask("router must forward TCP 47800 to this computer, port 47800"))
        assertEquals("redirige el puerto TCP •••", AddressMask.mask("redirige el puerto TCP 47800"))
    }
}
