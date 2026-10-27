package dev.miningprofit.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The lowest BIN of an item that isn't sold on the bazaar (a Littlefoot Fluff, a Bejeweled Handle), from Coflnet's
 * public API (sky.coflnet.com; nothing about you is sent), asked for in the background the first time an item's
 * wanted and again every [REFRESH_MS].
 */
object AuctionPrices {

    private const val URL = "https://sky.coflnet.com/api/item/price/"
    private const val REFRESH_MS = 1_800_000L
    private const val RETRY_MS = 120_000L

    private class Entry(val price: Double, val at: Long)

    // An item's id -> its lowest BIN (0: none for sale)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val triedAt = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    /** The lowest BIN of the item [id] (null until it's in, or if none's for sale). Asks for it if it's old or never asked for. */
    fun lowestBin(id: String): Double? {
        val now = System.currentTimeMillis()
        val e = cache[id]
        val stale = e == null || now - e.at >= (if (e.price > 0) REFRESH_MS else RETRY_MS)
        if (stale && now - (triedAt[id] ?: 0L) >= RETRY_MS && inflight.add(id)) {
            triedAt[id] = now
            fetch(id)
        }
        return e?.price?.takeIf { it > 0 }
    }

    /** Whether [id]'s price hasn't come back yet (once it has, even "none for sale" is an answer). */
    fun loading(id: String): Boolean = !cache.containsKey(id)

    private fun fetch(id: String) {
        val request = HttpRequest.newBuilder(URI.create("$URL$id/bin")).timeout(Duration.ofSeconds(20)).header("User-Agent", "MiningProfitTracker").GET().build()
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            try {
                if (error != null || response.statusCode() != 200) {
                    log.warn("Couldn't fetch the lowest BIN of $id: ${error ?: response.statusCode()}")
                    return@whenComplete
                }
                val lowest = com.google.gson.JsonParser.parseString(response.body()).asJsonObject.get("lowest")?.asDouble ?: 0.0
                cache[id] = Entry(lowest, System.currentTimeMillis())
            } catch (e: Exception) {
                log.warn("Couldn't read the lowest BIN of $id: $e")
            } finally {
                inflight.remove(id)
            }
        }
    }
}
