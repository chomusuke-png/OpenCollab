package dev.opencollab.core.util

/**
 * Hides what looks like a password, token or key in text the host shares with guests (console
 * output, debugger values): well-known token formats, private key blocks, credentials in URLs
 * and the value after a name such as `password=` or `API_KEY:`. A best effort: it cannot
 * recognise every secret, and one split across two pieces of output may get through.
 */
object SecretMask {
    const val HIDDEN = "•••"

    private val tokens = listOf(
        Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)"""),
        Regex("""\b(?:AKIA|ASIA|AGPA|AIDA|AROA|ANPA)[0-9A-Z]{16}\b"""), // AWS access key ids
        Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}\b"""), // GitHub
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b"""),
        Regex("""\bglpat-[A-Za-z0-9_-]{20,}\b"""), // GitLab
        Regex("""\bsk-[A-Za-z0-9_-]{20,}"""), // OpenAI, Anthropic and similar
        Regex("""\b[rsp]k_(?:live|test)_[A-Za-z0-9]{16,}\b"""), // Stripe
        Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}"""), // Slack
        Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""), // Google API keys
        Regex("""\bnpm_[A-Za-z0-9]{36}\b"""),
        Regex("""\bSG\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}"""), // SendGrid
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""), // JSON Web Tokens
    )

    /** `Authorization: Bearer …` and the like: keep the scheme, hide the credentials. */
    private val authScheme = Regex("""(?i)\b(bearer|basic|token)(\s+)([A-Za-z0-9._~+/=-]{12,})""")

    /** `scheme://user:password@host`: keep the user, hide the password. */
    private val urlPassword = Regex("""([A-Za-z][A-Za-z0-9+.-]*://[^\s:/@]+:)([^\s@/]+)@""")

    /**
     * `password=…`, `"apiKey": "…"`, `DB_PASSWORD: …`: a name that says it holds a secret, then
     * `=` or `:` and a value. Keeps the name and the quotes, hides the value.
     */
    private val namedValue = Regex(
        """(?i)([\w.-]*(?:passw(?:or)?d|pwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?|auth[_-]?key|session[_-]?key)[\w.-]*["']?\s*[:=]\s*["']?)([^\s"',;&]{4,})""",
    )

    /** Names of variables whose whole value should be hidden (in the debugger). */
    private val secretName = Regex("""(?i)passw(?:or)?d|pwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential""")

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (pattern in tokens) out = pattern.replace(out, HIDDEN)
        out = authScheme.replace(out) { it.groupValues[1] + it.groupValues[2] + HIDDEN }
        out = urlPassword.replace(out) { it.groupValues[1] + HIDDEN + "@" }
        out = namedValue.replace(out) { m -> if (m.groupValues[2] == HIDDEN) m.value else m.groupValues[1] + HIDDEN }
        return out
    }

    /** True if a variable called [name] probably holds a secret. */
    fun isSecretName(name: String): Boolean = secretName.containsMatchIn(name)
}
