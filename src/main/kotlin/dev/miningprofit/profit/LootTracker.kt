package dev.miningprofit.profit

import dev.miningprofit.support.AuctionPrices
import dev.miningprofit.support.Bazaar
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * The profit that comes in the chat rather than from blocks:
 *
 *  - Corpses: each "<TYPE> CORPSE LOOT!" block's
 *    items (all but Glacite Powder) at their bazaar price, less the key it used - the Umber, Tungsten or
 *    Skeleton Key (a Lapis corpse takes none) - unless the "Resourceful perk saved your key" message came
 *    first.
 *  - Fossil Essence from mining Fossils in a Mineshaft (quartz blocks, slabs and stairs): the game says
 *    nothing of it, so each block broken (see ProfitTracker.onBlockGone) counts as [ESSENCE_PER_FOSSIL],
 *    the middle of the 4-8 a Fossil gives (no stat changes it).
 *  - Shards charmed ("CHARM! You charmed the Littlefoot and received 2 Littlefoot Shards!").
 *  - The rest of a Littlefoot's drops, which the game doesn't put in the chat: Enchanted Glacite (16-32, always:
 *    each charm counts as [GLACITE_PER_LITTLEFOOT], the middle; it's confounded with Compact in the sacks, so it
 *    can't be told apart there), and the chance ones - Glacite Amalgamation and Littlefoot Fluff - that go
 *    straight to the inventory, seen as it goes up in the [WATCH_MS] round a charm ([onClientTick]).
 *
 * An item's priced when the overlay's built, so a price that wasn't in yet when it was looted is used
 * once it is; an item that isn't sold on the bazaar is priced at its lowest BIN ([AuctionPrices]: a Littlefoot
 * Fluff), and one that's on neither (a fossil, an enchanted book) counts as nothing.
 */
object LootTracker {

    class Bucket {
        /** Item name -> how many. */
        val items = LinkedHashMap<String, Long>()
        /** Corpses looted, Fossils broken, charms. */
        var count = 0
        /** Keys used, by bazaar id (corpses). */
        val keys = HashMap<String, Int>()

        fun add(name: String, amount: Long) {
            items[name] = (items[name] ?: 0L) + amount
        }

        fun clear() {
            items.clear()
            count = 0
            keys.clear()
        }

        val isEmpty: Boolean get() = count == 0 && items.isEmpty()
    }

    /** What a bucket comes to, and whether something in it is still to be priced. */
    class Value(val coins: Double, val loading: Boolean)

    /** A session's loot (each overlay's session has its own - see ProfitTracker). */
    class Bag {
        val corpses = Bucket()
        val fossils = Bucket()
        val shards = Bucket()
        val littlefoot = Bucket()
        val all get() = listOf(corpses, fossils, shards, littlefoot)
        fun any(): Boolean = all.any { !it.isEmpty }
        fun clear() = all.forEach { it.clear() }
    }

    /** Where what comes in goes: the session of where you are (ProfitTracker sets it). */
    var bag = Bag()
    val corpses get() = bag.corpses
    val fossils get() = bag.fossils
    val shards get() = bag.shards
    val littlefoot get() = bag.littlefoot

    private enum class Mode { NONE, CORPSE }

    // A Littlefoot always drops 16-32 Enchanted Glacite
    const val GLACITE_PER_LITTLEFOOT = 24L
    // The Littlefoot drops that aren't announced: they show up in the inventory
    private val INVENTORY_DROPS = setOf("Glacite Amalgamation", "Littlefoot Fluff")
    // Items that aren't on the bazaar but have a lowest BIN, by name (the id Coflnet knows them by)
    private val AUCTION_IDS = mapOf("Littlefoot Fluff" to "LITTLEFOOT_FLUFF")
    // How long either side of a charm an item turning up in the inventory is taken as its drop
    private const val WATCH_MS = 10_000L
    // The inventory is looked at every this many ticks
    private const val LOOK_EVERY = 5
    private val COLOR = Regex("§.")

    // A Fossil block gives 4-8 Fossil Essence
    const val ESSENCE_PER_FOSSIL = 6L

    // The key each corpse takes (Lapis: none)
    private val KEYS = mapOf("UMBER" to "UMBER_KEY", "TUNGSTEN" to "TUNGSTEN_KEY", "VANGUARD" to "SKELETON_KEY")
    private val CORPSE_START = Regex("""^\s*(LAPIS|UMBER|TUNGSTEN|VANGUARD) CORPSE LOOT!""")
    // "    Green Goblin Egg x2" (a gemstone has its symbol in front)
    private val ITEM = Regex("""^ {4}(?:[^\w\s]\s)?(.+?)(?: x([\d,]+))?\s*$""")
    private val END = Regex("""^\s*▬{10,}""")
    private const val RESOURCEFUL = "Your Resourceful perk saved your key from being consumed"
    private val CHARM = Regex("""CHARM! You charmed the .+? and received (\d+) (.+?) Shards?!""")
    // A block that never ends (a line missed) is let go of after this
    private const val BLOCK_MS = 15_000L
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    private var mode = Mode.NONE
    private var startedAt = 0L
    private var corpseType = ""
    private var keySaved = false
    private val block = ArrayList<Pair<String, Long>>()
    private val unpriced = HashSet<String>()
    // What each item was last priced at (to log when it changes)
    private val priced = HashMap<String, Double>()

    // The inventory's count of each of INVENTORY_DROPS as last looked at (null: not looked at yet), what turned up
    // in the last WATCH_MS (not claimed by a charm yet), and till when the next to turn up is a charm's
    private var held: Map<String, Long>? = null
    private val arrived = ArrayList<Triple<Long, String, Long>>()
    private var claimUntil = 0L
    private var ticks = 0

    fun any(): Boolean = bag.any()

    /** What's being read now (a corpse's loot coming in) dropped: the bags are the sessions'. */
    fun reset() {
        mode = Mode.NONE
        block.clear()
        arrived.clear()
        claimUntil = 0L
        held = null
    }

    /** A chat message (colour codes stripped, not trimmed). */
    fun onChat(text: String) {
        CHARM.find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return
            shards.add(m.groupValues[2] + " Shard", n)
            shards.count++
            if (m.groupValues[2] == "Littlefoot") littlefootKilled()
            return
        }
        if (text.contains(RESOURCEFUL)) {
            keySaved = true
            return
        }
        val now = System.currentTimeMillis()
        if (mode != Mode.NONE && now - startedAt > BLOCK_MS) mode = Mode.NONE
        if (mode == Mode.NONE) {
            CORPSE_START.find(text)?.let {
                start(Mode.CORPSE, now)
                corpseType = it.groupValues[1]
                return
            }
            return
        }
        if (END.containsMatchIn(text)) {
            finish()
            return
        }
        ITEM.find(text)?.let { m ->
            block.add(m.groupValues[1].trim() to (m.groupValues[2].replace(",", "").toLongOrNull() ?: 1L))
        }
    }

    private fun start(m: Mode, now: Long) {
        mode = m
        startedAt = now
        block.clear()
    }

    private fun finish() {
        for ((name, n) in block) {
            // Powder isn't an item
            if (name == "Glacite Powder") continue
            corpses.add(name, n)
        }
        corpses.count++
        val key = KEYS[corpseType]
        if (key != null && !keySaved) corpses.keys[key] = (corpses.keys[key] ?: 0) + 1
        log.info("[MP loot] $corpseType corpse: ${block.joinToString { "${it.first} x${it.second}" }}" + if (keySaved) " (key saved)" else "")
        keySaved = false
        mode = Mode.NONE
        block.clear()
    }

    // A Littlefoot was charmed: its Enchanted Glacite, and the chance drops that turned up just before or will just after
    private fun littlefootKilled() {
        val now = System.currentTimeMillis()
        littlefoot.add("Enchanted Glacite", GLACITE_PER_LITTLEFOOT)
        littlefoot.count++
        arrived.removeAll { now - it.first > WATCH_MS }
        for ((_, name, n) in arrived) {
            littlefoot.add(name, n)
            log.info("[MP loot] Littlefoot: $name x$n (in the inventory just before the charm)")
        }
        arrived.clear()
        claimUntil = now + WATCH_MS
        log.info("[MP loot] Littlefoot charmed (${shardPrice()})")
    }

    // What a Littlefoot Shard comes to, for the log (to check the tracker's number against the bazaar's)
    private fun shardPrice(): String {
        val p = Bazaar.price("SHARD_LITTLEFOOT") ?: return "Littlefoot Shard: no bazaar price yet"
        return "Littlefoot Shard: instant sell ${"%,.0f".format(p.instaSell)}, sell offer ${"%,.0f".format(p.sellOffer)}, counted at ${ProfitTracker.sell("SHARD_LITTLEFOOT")?.let { "%,.0f".format(it) }}"
    }

    /**
     * Every tick: watches the inventory for the chance drops of a Littlefoot ([INVENTORY_DROPS]). A rise in the count
     * in the [WATCH_MS] after a charm is the charm's; one just before it is kept to be claimed by it. With a
     * screen open the count's only noted (things moved about aren't drops).
     */
    fun onClientTick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { held = null; return }
        if (++ticks % LOOK_EVERY != 0) return
        val now = System.currentTimeMillis()
        arrived.removeAll { now - it.first > WATCH_MS }
        val counts = HashMap<String, Long>()
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.replace(COLOR, "").trim()
            if (name in INVENTORY_DROPS) counts[name] = (counts[name] ?: 0L) + stack.count
        }
        val before = held
        held = counts
        if (before == null || mc.screen is AbstractContainerScreen<*>) return
        for (name in INVENTORY_DROPS) {
            val d = (counts[name] ?: 0L) - (before[name] ?: 0L)
            if (d <= 0) continue
            if (now < claimUntil) {
                littlefoot.add(name, d)
                log.info("[MP loot] Littlefoot: $name x$d (in the inventory just after the charm)")
            } else {
                arrived.add(Triple(now, name, d))
            }
        }
    }

    /** A Fossil block in a Mineshaft was broken: its Fossil Essence. */
    fun fossilBlock() {
        fossils.add("Fossil Essence", ESSENCE_PER_FOSSIL)
        fossils.count++
    }

    /** What one [name] is worth: null for the coins when it has no price (yet); [loading]: a price that may still come. */
    private class Worth(val coins: Double?, val loading: Boolean)

    // An item on the bazaar is priced as the profit tracker sells; one that isn't (a Fluff), at its lowest BIN.
    // (Shards and essences have bazaar ids by their name alone, so they wait only for the prices, not for the names)
    private fun unit(name: String): Worth {
        val auction = AUCTION_IDS[name]
        if (auction != null) {
            AuctionPrices.lowestBin(auction)?.let { return Worth(it, false) }
            return Worth(null, AuctionPrices.loading(auction))
        }
        val id = Bazaar.idOfName(name)
        if (id != null) {
            if (Bazaar.updatedAt <= 0) return Worth(null, true)
            // (an id made from the name that has no bazaar product is not on the bazaar: no price, nothing to wait for)
            return Worth(ProfitTracker.sell(id), false)
        }
        // No id: not on the bazaar - or the names aren't in yet
        return Worth(null, !Bazaar.namesLoaded)
    }

    /** [b]'s items and keys, the most valuable first, one line each (for the overlay's hover). */
    fun itemLines(b: Bucket, max: Int = 6): List<String> {
        val rows = ArrayList<Pair<String, Double>>()
        for ((name, n) in b.items) {
            val p = unit(name).coins ?: continue
            rows.add("$name x$n" to p * n)
        }
        for ((key, n) in b.keys) {
            val p = ProfitTracker.sell(key) ?: continue
            rows.add("${key.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }} used x$n" to -p * n)
        }
        return rows.sortedByDescending { Math.abs(it.second) }.take(max).map { (label, v) -> "§8$label: §7${"%,.0f".format(v)}" }
    }

    /** What [b]'s items come to at the bazaar (the setting's price), less the keys it used. */
    fun value(b: Bucket): Value {
        var coins = 0.0
        var loading = false
        for ((name, n) in b.items) {
            val u = unit(name)
            val p = u.coins
            if (p == null) {
                if (u.loading) loading = true
                else if (unpriced.add(name)) log.info("[MP loot] not priced (not on the bazaar, no lowest BIN): $name")
                continue
            }
            if (priced.put(name, p) != p) log.info("[MP loot] $name priced at ${"%,.0f".format(p)} each")
            coins += p * n
        }
        for ((key, n) in b.keys) {
            val p = ProfitTracker.sell(key)
            if (p == null) loading = true else coins -= p * n
        }
        return Value(coins, loading)
    }
}
