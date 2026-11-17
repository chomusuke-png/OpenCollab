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

    val tls = when {
        opts.containsKey("--keystore") -> {
            val pw = System.getenv("OPENCOLLAB_RELAY_KEYSTORE_PASSWORD") ?: fail("Set OPENCOLLAB_RELAY_KEYSTORE_PASSWORD")
            val id = TlsIdentity.fromPkcs12(Path.of(opts.getValue("--keystore")), pw.toCharArray())
            println("Relay certificate fingerprint (SHA-256): ${id.fingerprint}")
            id.serverContext()
        }
        "--self-signed" in flags -> {
            val id = TlsIdentity.ephemeral("OpenCollab Relay", validityDays = 365)
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

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    System.err.println(USAGE)
    exitProcess(2)
}
