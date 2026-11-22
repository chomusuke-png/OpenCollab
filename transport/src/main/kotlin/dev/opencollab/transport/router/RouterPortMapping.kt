package dev.opencollab.transport.router

import dev.opencollab.transport.router.PortMappingException.Reason
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Asks the router to forward one TCP port to this computer while a session is hosted, keeps
 * the mapping alive, and removes it on [close]. It only ever maps [internalPort], the port
 * the host's own TLS server listens on, and only removes the mapping it created.
 *
 * If the IDE is killed, the router drops the mapping when its lease ends ([leaseSeconds]),
 * except on routers that only keep permanent mappings.
 */
class RouterPortMapping(
    private val internalPort: Int,
    /** The external port to ask for; 0 = the same as [internalPort], or a free one if that is taken. */
    private val requestedExternalPort: Int = 0,
    private val onStatus: (Status) -> Unit = {},
    private val leaseSeconds: Int = DEFAULT_LEASE_SECONDS,
    private val retryMillis: Long = 60_000,
    /** Finds the routers to try, the preferred first; blocking. */
    private val discover: () -> List<PortMapper> = ::discoverRouters,
    /** Whether this computer still has [InetAddress] (it changes when the network does). */
    private val stillAssigned: (InetAddress) -> Boolean = ::isAssigned,
) : AutoCloseable {
    init {
        require(NetAddresses.validPort(internalPort)) { "Invalid listen port $internalPort" }
        require(requestedExternalPort == 0 || NetAddresses.validPort(requestedExternalPort)) { "Invalid external port $requestedExternalPort" }
    }

    sealed interface Status {
        data object Searching : Status
        data class Found(val protocol: RouterProtocol) : Status
        /** The router confirmed the mapping. It does not prove the port is reachable from the internet. */
        data class Mapped(val mapping: PortMapping, val externalAddress: InetAddress) : Status
        data class RenewalFailed(val detail: String) : Status
        data class Failed(val reason: Reason, val detail: String) : Status
        data object Removed : Status
    }

    data class Result(val mapping: PortMapping, val externalAddress: InetAddress)

    private val lock = Any()
    @Volatile private var closed = false
    private var mapper: PortMapper? = null // guarded by lock
    private var mapping: PortMapping? = null // guarded by lock
    private var externalAddress: InetAddress? = null // guarded by lock
    private var timer: ScheduledExecutorService? = null // guarded by lock
    private var renewal: ScheduledFuture<*>? = null // guarded by lock

    /** The current mapping, while there is one. */
    val current: Result? get() = synchronized(lock) { mapping?.let { Result(it, externalAddress!!) } }

    /**
     * Finds a router and maps the port; blocks for a few seconds. Throws [PortMappingException]
     * when no router could map it, leaving nothing behind.
     */
    @Throws(PortMappingException::class)
    fun start(): Result {
        onStatus(Status.Searching)
        val result = try {
            establish()
        } catch (e: PortMappingException) {
            if (!closed) onStatus(Status.Failed(e.reason, e.message ?: ""))
            throw e
        }
        synchronized(lock) {
            if (closed) {
                runCatching { mapper?.unmap(result.mapping) }
                mapping = null
                throw PortMappingException(Reason.CANCELLED, "Hosting stopped")
            }
            scheduleRenewal(result.mapping.lifetimeSeconds)
        }
        onStatus(Status.Mapped(result.mapping, result.externalAddress))
        return result
    }

    private fun establish(): Result {
        val routers = discover()
        if (routers.isEmpty()) throw PortMappingException(Reason.NO_ROUTER, "No router answered for UPnP or NAT-PMP")
        var failure: PortMappingException? = null
        for (router in routers) {
            if (closed) throw PortMappingException(Reason.CANCELLED, "Hosting stopped")
            try {
                val external = router.externalAddress()
                onStatus(Status.Found(router.protocol))
                // Forwarding on a router without a public address cannot help (CGNAT, two routers).
                if (!NetAddresses.isPublicIpv4(external)) {
                    throw PortMappingException(Reason.NOT_PUBLIC, "The router's internet address ${external.hostAddress} is not public")
                }
                val made = mapOnFreePort(router)
                synchronized(lock) {
                    mapper = router
                    mapping = made
                    externalAddress = external
                }
                return Result(made, external)
            } catch (e: PortMappingException) {
                if (e.reason == Reason.CANCELLED) throw e
                failure = pick(failure, e)
            } catch (e: IOException) {
                failure = pick(failure, PortMappingException(Reason.NO_ROUTER, e.message ?: e.javaClass.simpleName, e))
            }
        }
        throw failure!!
    }

    /** Keeps the most telling failure: what a router said beats a router that did not answer. */
    private fun pick(old: PortMappingException?, new: PortMappingException) =
        if (old == null || old.reason == Reason.NO_ROUTER) new else old

    private fun mapOnFreePort(router: PortMapper): PortMapping {
        val candidates = if (requestedExternalPort != 0) listOf(requestedExternalPort) else
            (listOf(internalPort) + List(ALTERNATIVE_PORTS) { Random.nextInt(49152, 65536) }).distinct()
        var taken: PortMappingException? = null
        for (port in candidates) {
            if (closed) throw PortMappingException(Reason.CANCELLED, "Hosting stopped")
            try {
                val made = router.map(internalPort, port, leaseSeconds)
                if (made.internalPort != internalPort || !NetAddresses.validPort(made.externalPort)) {
                    throw PortMappingException(Reason.INVALID_RESPONSE, "The router confirmed a different mapping")
                }
                return made
            } catch (e: PortMappingException) {
                if (e.reason != Reason.PORT_TAKEN) throw e
                taken = e
            }
        }
        throw taken!!
    }

    private fun scheduleRenewal(lifetimeSeconds: Int, delayMillis: Long? = null) {
        if (lifetimeSeconds <= 0 && delayMillis == null) return // a permanent mapping
        val executor = timer ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "opencollab-router-renewal").apply { isDaemon = true }
        }.also { timer = it }
        val delay = delayMillis ?: (lifetimeSeconds * 1000L / 2).coerceAtLeast(MIN_RENEWAL_MILLIS)
        renewal = executor.schedule(::renew, delay, TimeUnit.MILLISECONDS)
    }

    /** Asks for the mapping again before the lease ends; maps afresh if this computer's address changed. */
    internal fun renew() {
        val outcome: Status = synchronized(lock) {
            if (closed) return
            val router = mapper ?: return
            val old = mapping ?: return
            try {
                val renewed = if (stillAssigned(old.internalAddress)) {
                    router.map(internalPort, old.externalPort, leaseSeconds)
                } else {
                    // A new network address: the old mapping points nowhere, find the router again.
                    runCatching { router.unmap(old) }
                    mapping = null
                    val again = establish()
                    again.mapping
                }
                mapping = renewed
                scheduleRenewal(renewed.lifetimeSeconds)
                Status.Mapped(renewed, externalAddress!!)
            } catch (e: IOException) {
                if (mapping == null) mapping = old // retried later, with the address check again
                scheduleRenewal(old.lifetimeSeconds, retryMillis)
                Status.RenewalFailed(e.message ?: e.javaClass.simpleName)
            }
        }
        onStatus(outcome)
    }

    /** Stops renewing and removes the mapping this object created. Safe to call more than once. */
    override fun close() {
        val toRemove: Pair<PortMapper, PortMapping>?
        synchronized(lock) {
            if (closed) return
            closed = true
            renewal?.cancel(false)
            timer?.shutdownNow()
            timer = null
            toRemove = mapper?.let { router -> mapping?.let { router to it } }
            mapping = null
        }
        if (toRemove != null) {
            try {
                toRemove.first.unmap(toRemove.second)
                onStatus(Status.Removed)
            } catch (e: IOException) {
                onStatus(Status.Failed(Reason.REFUSED, "Could not remove the port mapping: ${e.message}"))
            }
        }
    }

    companion object {
        const val DEFAULT_LEASE_SECONDS = 3600
        private const val ALTERNATIVE_PORTS = 3
        private const val MIN_RENEWAL_MILLIS = 30_000L
        private const val MAX_NAT_PMP_GATEWAYS = 3

        /** UPnP routers first (they say more about the mapping), then NAT-PMP at the likely gateways. */
        fun discoverRouters(): List<PortMapper> {
            val (upnp, answered) = UpnpDiscovery.discover()
            // Each silent address costs almost two seconds, so only the likeliest few are tried.
            val gateways = (NatPmpGateway.likelyGateways() + answered).distinct().take(MAX_NAT_PMP_GATEWAYS)
            return upnp + gateways.map { NatPmpGateway(it, retryDelaysMillis = listOf(250, 500, 1000)) }
        }

        private fun isAssigned(address: InetAddress): Boolean =
            address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(true)
    }
}
