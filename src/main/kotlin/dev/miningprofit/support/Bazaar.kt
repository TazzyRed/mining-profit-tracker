package dev.miningprofit.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Bazaar prices, from Hypixel's public bazaar endpoint (no key needed; nothing about you is sent),
 * fetched in the background every [REFRESH_MS]. Per item: what it sells for instantly (the best buy
 * order) and with a sell offer (about the lowest sell offer).
 *
 * What's counted is the median of each over the last day, from Coflnet's bazaar history (sky.coflnet.com, asked
 * for per item the first time it's priced and again every [DAY_REFRESH_MS]): one listing far off the rest (a
 * Flawed Jasper sell offer at 5,000 with the day's at 780) moves it only if it lasts most of the day. The live
 * price until the day's is in.
 *
 * Also an item's name to its id ("Green Goblin Egg" is GOBLIN_EGG_GREEN), from Hypixel's items list
 * (fetched once a session), for pricing what a chat message names.
 */
object Bazaar {

    private const val URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
    private const val REFRESH_MS = 600_000L
    private const val RETRY_MS = 60_000L
    private const val DAY_URL = "https://sky.coflnet.com/api/bazaar/%s/history/day"
    private const val DAY_REFRESH_MS = 3_600_000L
    // Coflnet's asked one item at a time, this far apart
    private const val DAY_GAP_MS = 300L
    // A day with fewer prices than this in its history isn't taken (the live price is)
    private const val DAY_MIN_SAMPLES = 12

    class Price(val instaSell: Double, val sellOffer: Double)

    private val prices = ConcurrentHashMap<String, Price>()
    @Volatile private var fetchedAt = 0L
    @Volatile private var triedAt = 0L
    @Volatile private var fetching = false
    // Lowercase item name -> the ids it goes by (a name's often shared by an item and its bazaar form)
    private val names = ConcurrentHashMap<String, List<String>>()
    // An item's id -> the median of its prices over the last day, and when it was asked for
    private class Day(val price: Price?, val at: Long)
    private val days = ConcurrentHashMap<String, Day>()
    private val dayTriedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var dayAskedAt = 0L
    @Volatile private var dayFetching = false
    @Volatile private var namesTriedAt = 0L
    @Volatile private var fetchingNames = false
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    /** When the prices were last fetched (0: not yet). */
    val updatedAt get() = fetchedAt

    /** Whether the item names are in (asks for them if not). */
    val namesLoaded: Boolean
        get() {
            refreshNamesIfNeeded()
            return names.isNotEmpty()
        }

    /**
     * The price of [id] (e.g. "COAL"): the median over the last day, the live one till that's in; null until it's
     * fetched, or if [id] isn't on the bazaar. Asks for a refresh when they're old.
     */
    fun price(id: String): Price? {
        refreshIfOld()
        val live = prices[id] ?: return null
        val day = days[id]
        val now = System.currentTimeMillis()
        if (day == null || now - day.at >= DAY_REFRESH_MS) askDay(id, now)
        return day?.price ?: live
    }

    /** Whether [id] is sold on the bazaar (false too while the prices aren't in). */
    fun onBazaar(id: String): Boolean = prices.containsKey(id)

    private fun askDay(id: String, now: Long) {
        if (dayFetching || now - dayAskedAt < DAY_GAP_MS || now - (dayTriedAt[id] ?: 0L) < RETRY_MS) return
        dayFetching = true
        dayAskedAt = now
        dayTriedAt[id] = now
        val request = HttpRequest.newBuilder(URI.create(DAY_URL.format(id))).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "MiningProfitTracker").GET().build()
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            try {
                if (error != null || response.statusCode() != 200) {
                    log.warn("Couldn't fetch the day's bazaar prices of $id: ${error ?: response.statusCode()}")
                    return@whenComplete
                }
                val sells = ArrayList<Double>()
                val buys = ArrayList<Double>()
                for (e in com.google.gson.JsonParser.parseString(response.body()).asJsonArray) {
                    val o = e.asJsonObject
                    val sell = o.get("sell")?.takeIf { !it.isJsonNull }?.asDouble ?: continue
                    val buy = o.get("buy")?.takeIf { !it.isJsonNull }?.asDouble ?: continue
                    if (sell <= 0 || buy <= 0) continue
                    sells.add(sell)
                    buys.add(buy)
                }
                val price = if (sells.size < DAY_MIN_SAMPLES) null else Price(median(sells), median(buys))
                days[id] = Day(price, System.currentTimeMillis())
                val live = prices[id]
                if (price != null && live != null) {
                    log.info("[MP price] $id day median: instant sell ${"%,.1f".format(price.instaSell)} (live ${"%,.1f".format(live.instaSell)}), " +
                        "sell offer ${"%,.1f".format(price.sellOffer)} (live ${"%,.1f".format(live.sellOffer)}), ${sells.size} prices")
                }
            } catch (e: Exception) {
                log.warn("Couldn't read the day's bazaar prices of $id: $e")
            } finally {
                dayFetching = false
            }
        }
    }

    private fun median(values: MutableList<Double>): Double {
        values.sort()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2
    }

    /**
     * The bazaar id of the item called [name] as chat has it ("Enchanted Umber", "Fossil Essence", "Littlefoot
     * Shard"); null if the names aren't in yet or it isn't a bazaar item.
     */
    fun idOfName(name: String): String? {
        refreshNamesIfNeeded()
        refreshIfOld()
        val clean = name.trim()
        // Essences and shards are bazaar products, but not in the items list
        Regex("""^(.+) Essence$""").find(clean)?.let { return "ESSENCE_" + it.groupValues[1].uppercase().replace(' ', '_') }
        Regex("""^(.+) Shards?$""").find(clean)?.let { return "SHARD_" + it.groupValues[1].uppercase().replace(' ', '_') }
        val ids = names[clean.lowercase()] ?: return null
        return ids.firstOrNull { prices.containsKey(it) } ?: ids.firstOrNull()
    }

    private fun refreshIfOld() {
        val now = System.currentTimeMillis()
        if (fetching || now - fetchedAt < REFRESH_MS || now - triedAt < RETRY_MS) return
        fetching = true
        triedAt = now
        val request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build()
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            try {
                if (error != null || response.statusCode() != 200) {
                    log.warn("Couldn't fetch bazaar prices: ${error ?: response.statusCode()}")
                    return@whenComplete
                }
                val products = com.google.gson.JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("products")
                for ((id, p) in products.entrySet()) {
                    val o = p.asJsonObject
                    val q = o.getAsJsonObject("quick_status") ?: continue
                    prices[id] = Price(q.get("sellPrice").asDouble, q.get("buyPrice").asDouble)
                }
                fetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                log.warn("Couldn't read bazaar prices: $e")
            } finally {
                fetching = false
            }
        }
    }

    private fun refreshNamesIfNeeded() {
        val now = System.currentTimeMillis()
        if (names.isNotEmpty() || fetchingNames || now - namesTriedAt < RETRY_MS) return
        fetchingNames = true
        namesTriedAt = now
        val request = HttpRequest.newBuilder(URI.create(ITEMS_URL)).timeout(Duration.ofSeconds(60)).GET().build()
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            try {
                if (error != null || response.statusCode() != 200) {
                    log.warn("Couldn't fetch the item names: ${error ?: response.statusCode()}")
                    return@whenComplete
                }
                val byName = HashMap<String, MutableList<String>>()
                for (e in com.google.gson.JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("items")) {
                    val o = e.asJsonObject
                    val id = o.get("id")?.asString ?: continue
                    val name = o.get("name")?.asString ?: continue
                    byName.getOrPut(name.lowercase()) { ArrayList() }.add(id)
                }
                names.putAll(byName)
            } catch (e: Exception) {
                log.warn("Couldn't read the item names: $e")
            } finally {
                fetchingNames = false
            }
        }
    }
}
