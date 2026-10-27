package dev.miningprofit.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * What a pet's XP is worth: buy it at level 1, level it, sell it at max level - what's made, over the XP in between.
 * Each price is that pet at that level (the rarity too) from Coflnet's public API (sky.coflnet.com, the one /cofl uses;
 * nothing about you is sent), asked for in the background the first time a pet's wanted and again every [REFRESH_MS]:
 *  - its lowest BIN now, or with none listed, what it sold for this week (the median of each hour's lowest sale);
 *  - or, with [Config.petPriceWeekAvg], what it sold for on average this week (by the number sold), or with no sales,
 *    its lowest BIN.
 * The sale is taken after the Auction House's taxes ([afterTax]); the level 1 pet is bought without any.
 */
object PetPrices {

    private const val URL = "https://sky.coflnet.com/api/item/price/"
    private const val REFRESH_MS = 1_800_000L
    private const val RETRY_MS = 120_000L

    // A pet at a level: its lowest BIN, and this week's sales (average by volume, median of each hour's lowest); 0: none
    private class Entry(val bin: Double, val weekAvg: Double, val weekLow: Double, val at: Long) {
        val any get() = bin > 0 || weekAvg > 0
    }

    // "ENDERMITE|MYTHIC|100" -> its prices
    private val cache = ConcurrentHashMap<String, Entry>()
    private val triedAt = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    private fun id(name: String) = name.uppercase().replace(' ', '_')

    /**
     * What selling at a BIN of [price] leaves: the listing fee (1% under 10M, 2% from 10M, 2.5% from
     * 100M) and the claim tax (1% on a sale over 1M, which never takes what's collected under 1M). The
     * listing's duration fee (a few hundred coins) isn't counted.
     */
    fun afterTax(price: Double): Double {
        val fee = price * when {
            price >= 100_000_000 -> 0.025
            price >= 10_000_000 -> 0.02
            else -> 0.01
        }
        val claim = if (price > 1_000_000) minOf(price * 0.01, price - 1_000_000) else 0.0
        return price - fee - claim
    }

    private fun entry(name: String, rarity: String, level: Int): Entry? {
        val key = "${id(name)}|$rarity|$level"
        val now = System.currentTimeMillis()
        val e = cache[key]
        val stale = e == null || now - e.at >= (if (e.any) REFRESH_MS else RETRY_MS)
        if (stale && now - (triedAt[key] ?: 0L) >= RETRY_MS && inflight.add(key)) {
            triedAt[key] = now
            fetch(key, name, rarity, level)
        }
        return e
    }

    // The price used, and where it came from ("BIN", "week avg", "week sales")
    private fun pick(e: Entry): Pair<Double, String>? = when {
        Config.petPriceWeekAvg && e.weekAvg > 0 -> e.weekAvg to "week avg"
        e.bin > 0 -> e.bin to "BIN"
        e.weekLow > 0 -> e.weekLow to "week sales"
        else -> null
    }

    /** The pet [name] of [rarity] at [level], as the setting prices it (null until it's in, or with no price at all). */
    fun lowest(name: String, rarity: String, level: Int): Double? = entry(name, rarity, level)?.let { pick(it) }?.first

    private fun fetch(key: String, name: String, rarity: String, level: Int) {
        val query = "PET_${id(name)}/%s?Rarity=$rarity&PetLevel=$level"
        val bin = get(URL + query.format("bin"))
        val week = get(URL + query.format("history/week"))
        bin.thenCombine(week) { b, w -> b to w }.whenComplete { result, error ->
            try {
                if (error != null || result == null) {
                    log.warn("Couldn't fetch the pet price of $key: $error")
                    return@whenComplete
                }
                val (b, w) = result
                val lowest = b?.let { com.google.gson.JsonParser.parseString(it).asJsonObject.get("lowest")?.asDouble } ?: 0.0
                var sold = 0.0
                var coins = 0.0
                val mins = ArrayList<Double>()
                w?.let { body ->
                    for (h in com.google.gson.JsonParser.parseString(body).asJsonArray) {
                        val o = h.asJsonObject
                        val n = o.get("volume")?.asDouble ?: 0.0
                        val avg = o.get("avg")?.asDouble ?: 0.0
                        if (n > 0 && avg > 0) { sold += n; coins += n * avg }
                        o.get("min")?.asDouble?.takeIf { it > 0 }?.let { mins.add(it) }
                    }
                }
                mins.sort()
                val e = Entry(lowest, if (sold > 0) coins / sold else 0.0, if (mins.isEmpty()) 0.0 else mins[mins.size / 2], System.currentTimeMillis())
                cache[key] = e
                log.info("[MP pet] $key: BIN ${"%,.0f".format(e.bin)}, week avg ${"%,.0f".format(e.weekAvg)} (${sold.toInt()} sold), week low ${"%,.0f".format(e.weekLow)}")
            } catch (e: Exception) {
                log.warn("Couldn't read the pet price of $key: $e")
            } finally {
                inflight.remove(key)
            }
        }
    }

    // A GET's body (null: not a 200)
    private fun get(url: String) = client.sendAsync(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "MiningProfitTracker").GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).thenApply { if (it.statusCode() == 200) it.body() else null }.exceptionally { null }

    /**
     * Coins for each XP of the pet called [name] ("Mithril Golem") of [rarity] ("LEGENDARY"), which takes
     * [xpToMax] from level 1 to [maxLevel]: its max level price after taxes, less its level 1 price. Null
     * until both prices are in; never under 0 (a pet that's worth less levelled isn't losing you coins by it).
     */
    fun perXp(name: String, rarity: String, maxLevel: Int, xpToMax: Double): Double? = perXp(name, rarity, 1, maxLevel, xpToMax)

    /**
     * The same for levelling from [from] to [to] ([xp] between them): bought at [from], sold at [to]. A dragon
     * that's level 100 already is bought there and sold at 200.
     */
    fun perXp(name: String, rarity: String, from: Int, to: Int, xp: Double): Double? {
        if (xp <= 0) return null
        val high = lowest(name, rarity, to) ?: return null
        val low = lowest(name, rarity, from) ?: return null
        return ((afterTax(high) - low) / xp).coerceAtLeast(0.0)
    }

    /** The sum behind [perXp], for showing (/miningprofit pet). */
    fun describe(name: String, rarity: String, from: Int, to: Int, xp: Double): String {
        val hi = entry(name, rarity, to)?.let { pick(it) }
        val lo = entry(name, rarity, from)?.let { pick(it) }
        if (hi == null || lo == null) return "lvl $from -> $to: prices loading (or none for sale)"
        val per = perXp(name, rarity, from, to, xp) ?: 0.0
        return "lvl $from ${"%,.0f".format(lo.first)} (${lo.second}) -> lvl $to ${"%,.0f".format(hi.first)} (${hi.second}, " +
            "${"%,.0f".format(afterTax(hi.first))} after tax) = ${"%.2f".format(per)} coins/XP"
    }
}
