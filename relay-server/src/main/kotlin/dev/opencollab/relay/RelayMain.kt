package dev.opencollab.relay

import dev.opencollab.transport.TlsIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """
OpenCollab relay server

Usage: java -jar opencollab-relay-all.jar [options]

TLS (choose one):
  --keystore <file.p12>         PKCS#12 keystore with the relay certificate and key
                                (password from env OPENCOLLAB_RELAY_KEYSTORE_PASSWORD)
  --self-signed                 Generate a self-signed certificate at startup and print its
                                fingerprint; clients must pin it ("Relay certificate fingerprint")
    --keep-certificate <file>   With --self-signed: keep the certificate in this PKCS#12 file and
                                reuse it, so the fingerprint stays the same across restarts. A new
                                one is made when the file is missing or the certificate expired.
                                (password from env OPENCOLLAB_RELAY_KEYSTORE_PASSWORD, or none)
  --plain-behind-proxy          Listen WITHOUT TLS. Only allowed with --bind 127.0.0.1/::1, for use
                                behind a local TLS-terminating reverse proxy (nginx, Caddy, ...)

Options:
  --bind <address>              Default 0.0.0.0
  --port <port>                 Default 8443
  --host-secret-file <file>     File containing the secret hosts must present
                                (alternatively env OPENCOLLAB_RELAY_SECRET). Required unless
                                --allow-anonymous-hosts is given.
  --allow-anonymous-hosts       Let anyone open rooms (NOT recommended on the internet)
  --max-rooms <n>               Default 200
  --max-peers <n>               Guests per room, default 16
"""

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    val flags = HashSet<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when (a) {
            "--self-signed", "--plain-behind-proxy", "--allow-anonymous-hosts" -> flags += a
            "--help", "-h" -> { println(USAGE); return }
            else -> {
                if (!a.startsWith("--") || i + 1 >= args.size) fail("Unknown or incomplete option: $a")
                opts[a] = args[++i]
            }
        }
        i++
    }
    val bind = opts["--bind"] ?: "0.0.0.0"
    val port = opts["--port"]?.toIntOrNull() ?: 8443

    val tlsChoices = listOf(opts.containsKey("--keystore"), "--self-signed" in flags, "--plain-behind-proxy" in flags).count { it }
    if (tlsChoices != 1) fail("Choose exactly one of --keystore, --self-signed, --plain-behind-proxy")

    if (opts.containsKey("--keep-certificate") && "--self-signed" !in flags) fail("--keep-certificate only works with --self-signed")

    val tls = when {
        opts.containsKey("--keystore") -> {
            val pw = System.getenv("OPENCOLLAB_RELAY_KEYSTORE_PASSWORD") ?: fail("Set OPENCOLLAB_RELAY_KEYSTORE_PASSWORD")
            val id = TlsIdentity.fromPkcs12(Path.of(opts.getValue("--keystore")), pw.toCharArray())
            println("Relay certificate fingerprint (SHA-256): ${id.fingerprint}")
            id.serverContext()
        }
        "--self-signed" in flags -> {
            val kept = opts["--keep-certificate"]?.let { Path.of(it) }
            val id = if (kept != null) keptCertificate(kept) else TlsIdentity.ephemeral("OpenCollab Relay", validityDays = 365)
            println("Self-signed relay certificate fingerprint (SHA-256): ${id.fingerprint}")
            println("Configure this fingerprint in each IDE (Settings > Tools > OpenCollab > Relay certificate fingerprint).")
            id.serverContext()
        }
        else -> {
            if (bind != "127.0.0.1" && bind != "::1" && bind != "localhost") {
                fail("--plain-behind-proxy requires --bind 127.0.0.1 (TLS must be terminated by a local proxy)")
            }
            null
        }
    }

    val secret = opts["--host-secret-file"]?.let { Files.readString(Path.of(it)).trim() }
        ?: System.getenv("OPENCOLLAB_RELAY_SECRET")?.trim()
        ?: ""
    if (secret.isEmpty() && "--allow-anonymous-hosts" !in flags) {
        fail("A host secret is required (--host-secret-file or OPENCOLLAB_RELAY_SECRET), or pass --allow-anonymous-hosts")
    }
    if (secret.isNotEmpty() && secret.length < 16) fail("The host secret must be at least 16 characters")

    val server = RelayServer(
        RelayConfig(
            bind = bind, port = port, tls = tls, hostSecret = secret,
            maxRooms = opts["--max-rooms"]?.toIntOrNull() ?: 200,
            maxPeersPerRoom = opts["--max-peers"]?.toIntOrNull() ?: 16,
        ),
    )
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

/** The certificate kept in [file], or a new one saved there when it is missing or expired. */
private fun keptCertificate(file: Path): TlsIdentity {
    val password = (System.getenv("OPENCOLLAB_RELAY_KEYSTORE_PASSWORD") ?: "").toCharArray()
    if (Files.exists(file)) {
        val id = try {
            TlsIdentity.fromPkcs12(file, password)
        } catch (e: Exception) {
            fail("Cannot read the kept certificate $file (${e.message}). Check OPENCOLLAB_RELAY_KEYSTORE_PASSWORD, or delete the file to make a new certificate.")
        }
        // Renew a few days early, so clients do not meet an expired certificate.
        if (id.certificate.notAfter.time - System.currentTimeMillis() > RENEW_BEFORE_MILLIS) {
            println("Using the kept certificate in $file (valid until ${id.certificate.notAfter.toInstant()}).")
            return id
        }
        println("The kept certificate in $file expires soon: making a new one. Hosts must enter the new fingerprint.")
    }
    val id = TlsIdentity.ephemeral("OpenCollab Relay", validityDays = 825)
    try {
        id.saveAsPkcs12(file, password)
    } catch (e: Exception) {
        fail("Cannot save the certificate to $file: ${e.message}")
    }
    println("Saved a new certificate in $file (valid until ${id.certificate.notAfter.toInstant()}).")
    return id
}

private const val RENEW_BEFORE_MILLIS = 7L * 24 * 3_600_000

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    System.err.println(USAGE)
    exitProcess(2)
}
