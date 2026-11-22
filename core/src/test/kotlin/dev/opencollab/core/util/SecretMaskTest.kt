package dev.opencollab.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretMaskTest {
    @Test
    fun `known token formats are hidden`() {
        val text = """
            AWS key AKIAIOSFODNN7EXAMPLE in use
            GH ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789
            OPENAI sk-proj-abcdefghijklmnopqrstuvwxyz012345
            stripe sk_live_abcdefghijklmnop1234
            jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U
        """.trimIndent()
        val out = SecretMask.mask(text)
        for (secret in listOf("AKIAIOSFODNN7EXAMPLE", "ghp_aBcDeF", "sk-proj-abc", "sk_live_abc", "eyJhbGci")) {
            assertFalse(out.contains(secret), "$secret in $out")
        }
        assertTrue(out.startsWith("AWS key ••• in use"), out)
    }

    @Test
    fun `named values, auth headers, URL passwords and private keys are hidden`() {
        assertEquals("DB_PASSWORD=•••", SecretMask.mask("DB_PASSWORD=hunter22"))
        assertEquals("\"apiKey\": \"•••\"", SecretMask.mask("\"apiKey\": \"abcd1234efgh\""))
        assertEquals("Authorization: Bearer •••", SecretMask.mask("Authorization: Bearer abcdefghijklmnop.qrs"))
        assertEquals("postgres://app:•••@db:5432/x", SecretMask.mask("postgres://app:s3cr3t@db:5432/x"))
        assertEquals("key: •••\nafter", SecretMask.mask("key: -----BEGIN PRIVATE KEY-----\nMIIEv\n-----END PRIVATE KEY-----\nafter"))
    }

    @Test
    fun `ordinary output is left alone`() {
        val text = "BUILD SUCCESSFUL in 3s\n> Task :compileKotlin\nTests: 12 passed, 0 failed\nhttps://example.com/docs"
        assertEquals(text, SecretMask.mask(text))
        assertTrue(SecretMask.isSecretName("dbPassword"))
        assertTrue(SecretMask.isSecretName("GITHUB_TOKEN"))
        assertFalse(SecretMask.isSecretName("count"))
    }
}
