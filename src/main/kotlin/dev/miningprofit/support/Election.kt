package dev.miningprofit.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Who's in office, from Hypixel's public election endpoint (no key needed; nothing about you is sent),
 * fetched in the background every [REFRESH_MS]. Only one thing's wanted of it: whether Mining Fiesta
 * (Cole's, as mayor or minister: +25% Mining Fortune) is among the perks in force.
 *
 * With Jerry in, his Perkpocalypse gives another mayor's perks for a few hours at a time, and the endpoint
 * doesn't say whose: that's read in game, from the Calendar's Mayor Jerry item (/calendar), and kept (over a
 * restart too) till the time it says they change.
 */
object Election {

    private const val URL = "https://api.hypixel.net/v2/resources/skyblock/election"
    private const val REFRESH_MS = 3_600_000L
    private const val RETRY_MS = 60_000L
    private const val FIESTA = "Mining Fiesta"
    // Perkpocalypse's perks change this often (taken when the menu doesn't say when)
    private const val JERRY_TURN_MS = 6 * 3_600_000L
    // Asked to open /calendar at most this often, while Jerry's perks aren't known and the fiesta matters
    private const val ASK_MS = 30 * 60_000L
    // Each mayor's perks by name: the Mayor Jerry item lists the perks, not whose they are
    private val PERKS = mapOf(
        "Aatrox" to listOf("Slayer XP Buff", "Pathfinder", "SLASHED Pricing"),
        "Cole" to listOf("Prospection", "Mining XP Buff", "Mining Fiesta", "Molten Forge"),
        "Diana" to listOf("Lucky!", "Pet XP Buff", "Mythological Ritual", "Sharing is Caring"),
        "Diaz" to listOf("Volume Trading", "Shopping Spree", "Stock Exchange", "Long Term Investment"),
        "Finnegan" to listOf("Pelt-pocalypse", "GOATed", "Blooming Business", "Pest Eradicator"),
        "Foxy" to listOf("Sweet Benevolence", "A Time for Giving", "Chivalrous Carnival", "Extra Event"),
        "Marina" to listOf("Fishing XP Buff", "Luck of the Sea 2.0", "Fishing Festival", "Double Trouble"),
        "Paul" to listOf("Marauder", "EZPZ", "Benediction"),
    )
    private val TIME = Regex("""(\d+)\s*([dhms])\b""")

    @Volatile private var fiesta = false
    @Volatile private var jerry = false
    @Volatile private var fetchedAt = 0L
    @Volatile private var triedAt = 0L
    @Volatile private var fetching = false
    // Jerry's Perkpocalypse: whose perks, whether Mining Fiesta's among them, and till when (0: not known)
    private var jerryMayor: String? = null
    private var jerryFiesta = false
    private var jerryUntil = 0L
    private var jerryLoaded = false
    private var readIn = -1
    @Volatile private var wanted = false
    private var askedAt = 0L
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")
    private val file get() = DataDir.file("jerry.txt")

    /** Whether Mining Fiesta is in force (false until it's fetched). Asks for a refresh when it's old. */
    val miningFiesta: Boolean
        get() {
            refreshIfOld()
            if (!jerry) return fiesta
            load()
            if (!jerryKnown()) wanted = true
            return fiesta || (jerryKnown() && jerryFiesta)
        }

    private fun jerryKnown() = jerryUntil > System.currentTimeMillis()

    /** For the status line: Jerry's perks, as known. */
    val jerryState: String
        get() = when {
            !jerry -> ""
            !jerryKnown() -> "Jerry's perks not known (open /calendar)"
            else -> "Jerry has ${jerryMayor ?: "?"}'s perks for ${(jerryUntil - System.currentTimeMillis()) / 60_000}m"
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
                    log.warn("Couldn't fetch the election: ${error ?: response.statusCode()}")
                    return@whenComplete
                }
                val mayor = com.google.gson.JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("mayor")
                val perks = ArrayList<String>()
                mayor.getAsJsonArray("perks")?.forEach { perks.add(it.asJsonObject.get("name").asString) }
                mayor.get("minister")?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("perk")?.let { perks.add(it.get("name").asString) }
                val was = fiesta
                fiesta = FIESTA in perks
                jerry = "Perkpocalypse" in perks
                if (fetchedAt == 0L || was != fiesta) log.info("[MP profit] election: ${mayor.get("name")?.asString}, perks in force $perks; Mining Fiesta ${if (fiesta) "on" else "off"}")
                fetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                log.warn("Couldn't read the election: $e")
            } finally {
                fetching = false
            }
        }
    }

    /** Each tick: the Calendar's Mayor Jerry item read when it's open; asked for, now and then, when it's wanted. */
    fun onClientTick(mc: net.minecraft.client.Minecraft) {
        (mc.screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.let { screen ->
            if (screen.menu.containerId == readIn) return@let
            val inventory = mc.player?.inventory
            val slot = screen.menu.slots.firstOrNull { s ->
                s.container !== inventory && !s.item.isEmpty && lore(s).any { it.contains("Perkpocalypse") }
            } ?: return@let
            readIn = screen.menu.containerId
            read(s(slot.item.hoverName.string), lore(slot))
        }
        if (!wanted || !jerry || jerryKnown()) return
        val now = System.currentTimeMillis()
        if (now - askedAt < ASK_MS) return
        askedAt = now
        wanted = false
        say("§b[Mining Profit] §7Jerry's in: which mayor's perks he has now isn't known (Mining Fiesta for Maniac Miner) - open §e/calendar§7 once and it's read.")
    }

    // The Mayor Jerry item: the perks listed under "Perkpocalypse Perks:" (whose they are worked out from their
    // names), Mining Fiesta among them or not, and when they change ("Next set of perks in 5h 50m!")
    private fun read(name: String, lore: List<String>) {
        log.info("[MP profit] Perkpocalypse item '$name': ${lore.joinToString(" | ")}")
        val from = lore.indexOfFirst { it.startsWith("Perkpocalypse Perks") }
        if (from < 0) return
        val after = lore.drop(from + 1).map { it.trim() }
        fun matches(perks: List<String>) = perks.count { it in after }
        val mayor = PERKS.entries.maxByOrNull { matches(it.value) }?.takeIf { matches(it.value) > 0 }?.key
        val hasFiesta = FIESTA in after || mayor == "Cole"
        val left = after.firstOrNull { it.startsWith("Next set of perks") }?.let { line ->
            TIME.findAll(line).sumOf { m ->
                val v = m.groupValues[1].toLong()
                when (m.groupValues[2]) { "d" -> v * 86_400_000L; "h" -> v * 3_600_000L; "m" -> v * 60_000L; else -> v * 1000L }
            }
        }?.takeIf { it in 1..JERRY_TURN_MS }
        val now = System.currentTimeMillis()
        val was = jerryMayor
        jerryMayor = mayor
        jerryFiesta = hasFiesta
        jerryUntil = now + (left ?: JERRY_TURN_MS)
        jerryLoaded = true
        save()
        log.info("[MP profit] Perkpocalypse: ${mayor ?: "?"}'s perks, Mining Fiesta ${if (hasFiesta) "on" else "off"}, for ${(jerryUntil - now) / 60_000}m${if (left == null) " (guessed: the item didn't say)" else ""}")
        if (was != mayor) say("§b[Mining Profit] §7Jerry has §f${mayor ?: "?"}§7's perks now" +
            (if (hasFiesta) " - §aMining Fiesta on" else "") + "§7 (for ${(jerryUntil - now) / 60_000}m).")
    }

    private fun s(text: String) = text.replace(Regex("§."), "").trim()
    private fun lore(slot: net.minecraft.world.inventory.Slot): List<String> =
        slot.item.get(net.minecraft.core.component.DataComponents.LORE)?.lines()?.map { s(it.string) } ?: emptyList()
    private fun say(text: String) =
        net.minecraft.client.Minecraft.getInstance().gui.chat.addClientSystemMessage(net.minecraft.network.chat.Component.literal(text))

    private fun load() {
        if (jerryLoaded) return
        jerryLoaded = true
        try {
            val f = file.toFile()
            if (!f.exists()) return
            val parts = f.readText().trim().split(' ')
            jerryUntil = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            jerryFiesta = parts.getOrNull(1) == "fiesta"
            jerryMayor = parts.getOrNull(2)?.takeIf { it != "?" }
        } catch (e: Exception) {
            log.warn("Couldn't read jerry.txt: $e")
        }
    }

    private fun save() {
        try {
            file.toFile().writeText("$jerryUntil ${if (jerryFiesta) "fiesta" else "none"} ${jerryMayor ?: "?"}")
        } catch (e: Exception) {
            log.warn("Couldn't save jerry.txt: $e")
        }
    }
}
