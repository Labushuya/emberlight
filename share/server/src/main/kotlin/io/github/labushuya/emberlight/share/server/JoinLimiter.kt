package io.github.labushuya.emberlight.share.server

/**
 * Drosselung der Beitritts-Anfragen (ADR 0018, Rate-Limit). Gezählt werden Fehlversuche mit falscher Einladung. Je
 * Absender sind [perAddress] in [windowMillis] erlaubt, insgesamt [global]. Ist eine Grenze erreicht, antwortet der
 * Server mit `429` und `Retry-After`, bis genug Fehlversuche aus dem Fenster gefallen sind.
 *
 * [nowMillis] ist injizierbar. Die Produktion nimmt eine monotone Uhr, Tests eine feste Zeit.
 */
class JoinLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val perAddress: Int = PER_ADDRESS,
    private val global: Int = GLOBAL,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private val byAddress = HashMap<String, ArrayDeque<Long>>()
    private val all = ArrayDeque<Long>()

    init {
        require(perAddress > 0 && global > 0 && windowMillis > 0)
    }

    /** Sekunden bis zur nächsten erlaubten Anfrage, oder `null`, wenn der Absender jetzt darf. */
    @Synchronized
    fun retryAfterSeconds(address: String): Long? {
        val now = nowMillis()
        prune(now)
        val waits = mutableListOf<Long>()
        val own = byAddress[address]
        if (own != null && own.size >= perAddress) {
            waits += own[own.size - perAddress] + windowMillis - now
        }
        if (all.size >= global) {
            waits += all[all.size - global] + windowMillis - now
        }
        return waits.maxOrNull()?.let { ceilSeconds(it) }
    }

    /** Zählt einen Fehlversuch. */
    @Synchronized
    fun recordFailure(address: String) {
        val now = nowMillis()
        prune(now)
        byAddress.getOrPut(address) { ArrayDeque() }.addLast(now)
        all.addLast(now)
    }

    private fun prune(now: Long) {
        val cutoff = now - windowMillis
        while (all.isNotEmpty() && all.first() <= cutoff) all.removeFirst()
        val iterator = byAddress.entries.iterator()
        while (iterator.hasNext()) {
            val times = iterator.next().value
            while (times.isNotEmpty() && times.first() <= cutoff) times.removeFirst()
            if (times.isEmpty()) iterator.remove()
        }
    }

    private fun ceilSeconds(millis: Long): Long = maxOf(1L, (millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)

    companion object {
        const val PER_ADDRESS = 10
        const val GLOBAL = 60
        const val WINDOW_MILLIS = 10 * 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
