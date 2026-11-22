package dev.opencollab.transport

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** TLS protocols OpenCollab accepts. */
internal val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

/**
 * A certificate + private key used by a TLS server (the host, or a relay server).
 *
 * Hosts use [ephemeral] identities: a fresh self-signed EC P-256 certificate per session.
 * Guests authenticate the host by its SHA-256 [fingerprint], which travels inside the
 * invitation (certificate pinning) — no certificate authority is involved.
 */
class TlsIdentity private constructor(
    val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) {
    val fingerprint: String = fingerprintOf(certificate)

    fun serverContext(): SSLContext {
        val password = CharArray(0)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("opencollab", privateKey, password, chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    /** Saves the certificate and key as a PKCS#12 keystore readable only by its owner where possible. */
    fun saveAsPkcs12(path: Path, password: CharArray) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("opencollab", privateKey, password, chain)
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(path.toAbsolutePath().parent, ".opencollab", ".p12")
        try {
            runCatching { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
            Files.newOutputStream(tmp).use { ks.store(it, password) }
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        fun ephemeral(commonName: String = "OpenCollab Host", validityDays: Long = 7): TlsIdentity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger(96, SecureRandom()), Date(now - 3_600_000), Date(now + validityDays * 86_400_000), name, kp.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return TlsIdentity(cert, kp.private, arrayOf(cert))
        }

        /** Loads a PKCS#12 keystore (e.g. a relay server certificate issued by a CA). */
        fun fromPkcs12(path: Path, password: CharArray, alias: String? = null): TlsIdentity {
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(path).use { ks.load(it, password) }
            val a = alias ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
                ?: throw IOException("No private key entry in $path")
            val key = ks.getKey(a, password) as? PrivateKey ?: throw IOException("Entry $a is not a private key")
            val chain = ks.getCertificateChain(a).map { it as X509Certificate }.toTypedArray()
            return TlsIdentity(chain.first(), key, chain)
        }

        fun fingerprintOf(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        fun normalizeFingerprint(fp: String): String = fp.uppercase().filter { it.isLetterOrDigit() }
    }
}

/** How a client decides whether to trust the server certificate. */
sealed interface TrustMode {
    /** Trust exactly one certificate, identified by its SHA-256 fingerprint (host certificates). */
    data class Pinned(val fingerprint: String) : TrustMode

    /** Trust the JVM's default certificate authorities and verify the hostname (public relays). */
    data object SystemCertificateAuthorities : TrustMode

    /** Pin the relay's certificate (self-signed relay deployments). */
    data class PinnedRelay(val fingerprint: String) : TrustMode

    fun verifiesHostname(): Boolean = this is SystemCertificateAuthorities

    fun clientContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        val tms: Array<TrustManager>? = when (this) {
            is Pinned -> arrayOf(PinnedTrustManager(fingerprint))
            is PinnedRelay -> arrayOf(PinnedTrustManager(fingerprint))
            SystemCertificateAuthorities -> {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                tmf.trustManagers
            }
        }
        ctx.init(null, tms, SecureRandom())
        return ctx
    }
}

/** Accepts only a server whose leaf certificate matches the pinned SHA-256 fingerprint. */
class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = TlsIdentity.normalizeFingerprint(fingerprint)

    init {
        require(expected.length == 64) { "A SHA-256 fingerprint has 32 bytes" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
        leaf.checkValidity()
        val actual = TlsIdentity.normalizeFingerprint(TlsIdentity.fingerprintOf(leaf))
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
            throw CertificateException("Server certificate does not match the invitation (possible interception)")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
