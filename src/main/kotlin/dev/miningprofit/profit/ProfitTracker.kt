package dev.miningprofit.profit

import dev.miningprofit.commissions.CommissionsTracker
import dev.miningprofit.support.Election
import dev.miningprofit.support.Bazaar
import dev.miningprofit.support.Config
import dev.miningprofit.support.IslandTracker
import dev.miningprofit.support.Tab
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Profit tracker: what the blocks you break are worth.
 *
 *  - Every block of a kind it knows ([KINDS]: coal ore, hard stone) that you break - broken by your
 *    client, or gone within [NEAR_YOU] of you (Efficient Miner and the like) - in the mining islands.
 *  - Your fortune, read from the tab list every [READ_MS]: Mining Fortune for everything, Ore Fortune on
 *    top for ores, Block Fortune for blocks. A block's expected drops are its base drop times
 *    (1 + fortune / 100) - coal's base is 5 (a block came to ~150 coal at ~2,900 fortune; a 5-minute
 *    run's collection matched it), hard stone's 1.
 *  - Mining Fortune's also kept for each item you hold (by its SkyBlock uuid), as the tab list last read
 *    with it in hand for [SETTLE_MS]: what a block's broken at with that in hand while Maniac Miner's on, and
 *    for a moment after a swap (before the tab list catches up) - a drill swapped to mid-ability gets its own.
 *    Kept without the buffs that come and go - the Sky Mall's (+50 on a day it's Mining Fortune, as the chat
 *    says when the day's buff changes) and Better Together's (+20 for each player the sidebar says is in your
 *    zone, at most 5; you aren't one of them) - and those added as they are at the moment of the block. What
 *    else the tab list's Mining Fortune moves by with the same item in hand (a pet, a potion) moves every
 *    item's kept fortune by as much: it's yours, whatever you hold.
 *  - While Maniac Miner is on, the tab list can't keep up (each block mined adds to your fortune), so the
 *    fortune read when it started (or the held drill's own, above) is kept, and each block mined since adds to it, up to [MANIAC_MAX]:
 *    [MANIAC_STEP] Mining Fortune, or [MANIAC_BLEGG] while the drill in your hand is the one it was used
 *    with and has a Blue Cheese part (swapped to any other, Blue Cheese or not, it's MANIAC_STEP). During Mining Fiesta (see Election) a
 *    quarter more of either, and of the most it comes to (625) - and a half more with a Fiesta Flask
 *    drunk as well (an hour from when you use one). Measured: 18 blocks gave 225, without the part and
 *    with the Fiesta. Only Mining Fortune's raised by these, none of the others.
 *  - Counted and priced as the enchanted item it's compacted into and sold as (Enchanted Coal, 160 coal
 *    each - not coal, nor Enchanted Coal Blocks), at its bazaar price (instant sell, or sell offer in the
 *    settings).
 *  - Compact: each "COMPACT! You found an Enchanted Coal!" is one enchanted item dropped as well as the
 *    block's usual drops (Compact's chance per block, 0.25% to 0.57% by its tier).
 *  - Pets (see ExpShare) and what comes in the chat - corpses, the Fossil Excavator, charmed shards (see
 *    LootTracker) - are added to it, and shown apart from the drops.
 *  - Per hour over the time you've been at it, on the session's own clock (never the commission timer's): it
 *    runs while you're breaking blocks (on commissions: or gaining Mining XP, or making commission
 *    progress), and after [IDLE_MS] without any it pauses, rolled back to the last of it.
 *  - A session for each place, on its own (see [Session]): the Crystal Hollows' coal, its gems (from 3 gem
 *    blocks in a row, those 3 taken over from the coal's), the Base Camp (the Glacite Tunnels and their
 *    Mineshafts) and the End. What happens anywhere else (the Dwarven Mines too) isn't counted, and nothing's
 *    shown there. Each keeps over world changes, and over restarts unless [Config.overlayResetOnLaunch].
 *    `/miningprofit reset` starts the one where you are again (`all`: every one), with its commission
 *    clock; `/miningprofit tracker reset` only the profit session (the commission timer carries on).
 */
object ProfitTracker {

    /**
     * A block kind: which fortune adds to it, and the enchanted item its drops are counted and sold as
     * ([per] drops make one).
     */
    class Kind(val name: String, val ore: Boolean, val item: String, val itemName: String, val per: Int, val base: Double,
               /** Also sold as this, one each (rough gems): whichever's worth more. */
               val single: String? = null, val gem: Boolean = false,
               /** A Dwarven metal: Dwarven Metal Fortune on top of Mining Fortune (not Ore or Block). */
               val metal: Boolean = false,
               /** What it's sold as when metals are counted as they drop, not enchanted (one each). */
               val raw: String? = null)

    private val KINDS: Map<Block, Kind> = mapOf(
        // Base drops measured (blocks broken against collection gained): coal, diamond, emerald 5, iron and
        // gold 1, redstone 8. All count towards profit as their enchanted form; coal's collection is shown
        Blocks.COAL_ORE to Kind("Coal", true, "ENCHANTED_COAL", "Enchanted Coal", 160, 5.0),
        Blocks.IRON_ORE to Kind("Iron", true, "ENCHANTED_IRON", "Enchanted Iron", 160, 1.0),
        Blocks.GOLD_ORE to Kind("Gold", true, "ENCHANTED_GOLD", "Enchanted Gold", 160, 1.0),
        Blocks.REDSTONE_ORE to Kind("Redstone", true, "ENCHANTED_REDSTONE", "Enchanted Redstone", 160, 8.0),
        Blocks.DIAMOND_ORE to Kind("Diamond", true, "ENCHANTED_DIAMOND", "Enchanted Diamond", 160, 5.0),
        Blocks.EMERALD_ORE to Kind("Emerald", true, "ENCHANTED_EMERALD", "Enchanted Emerald", 160, 5.0),
        // Hard stone: 1. Sold as Concentrated Stone (576 Enchanted Hard Stone, each 576 hard stone). Block
        // Fortune's, and a block towards Maniac Miner's
        Blocks.STONE to Kind("Hard Stone", false, HARD_STONE, "Concentrated Stone", 576 * 576, 1.0),
        // The Glacite Tunnels' (base drops as counted): tungsten 1 (cobblestone) or 3 (clay); umber 1
        // (terracotta), 2 (brown / black terracotta) or 3 (red sandstone); glacite 1
        Blocks.INFESTED_COBBLESTONE to tungsten(1.0),
        Blocks.CLAY to tungsten(3.0),
        Blocks.TERRACOTTA to umber(1.0),
        Blocks.BROWN_TERRACOTTA to umber(2.0),
        Blocks.BLACK_TERRACOTTA to umber(2.0),
        Blocks.SMOOTH_RED_SANDSTONE to umber(3.0),
        Blocks.RED_SANDSTONE to umber(3.0),
        Blocks.PACKED_ICE to Kind("Glacite", false, "ENCHANTED_GLACITE", "Enchanted Glacite", 160, 1.0, metal = true, raw = "GLACITE"),
        // Mithril (measured, 10 blocks of each): gray 1, green 2, blue 5
        Blocks.GRAY_WOOL to mithril(1.0),
        Blocks.CYAN_TERRACOTTA to mithril(1.0),
        Blocks.PRISMARINE to mithril(2.0),
        Blocks.PRISMARINE_BRICKS to mithril(2.0),
        Blocks.DARK_PRISMARINE to mithril(2.0),
        Blocks.LIGHT_BLUE_WOOL to mithril(5.0),
        // Titanium (measured, 20 blocks): 2
        Blocks.POLISHED_DIORITE to Kind("Titanium", false, "ENCHANTED_TITANIUM", "Enchanted Titanium", 160, 2.0, metal = true, raw = "TITANIUM_ORE"),
        // The End's obsidian: 1 (a block at 2,032 Mining + Block Fortune gave 22, i.e. 21.32). Block Fortune's
        Blocks.OBSIDIAN to Kind("Obsidian", false, "ENCHANTED_OBSIDIAN", "Enchanted Obsidian", 160, 1.0),
    ) + gems()

    private fun mithril(base: Double) = Kind("Mithril", false, "ENCHANTED_MITHRIL", "Enchanted Mithril", 160, base, metal = true, raw = "MITHRIL_ORE")

    private fun tungsten(base: Double) = Kind("Tungsten", false, "ENCHANTED_TUNGSTEN", "Enchanted Tungsten", 160, base, metal = true, raw = "TUNGSTEN")
    private fun umber(base: Double) = Kind("Umber", false, "ENCHANTED_UMBER", "Enchanted Umber", 160, base, metal = true, raw = "UMBER")

    // Gemstones (glass and panes, by colour): about 4 rough a block with Gemstone Fortune, sold as Flawed
    // (80 rough) or rough, whichever's worth more
    private fun gems(): Map<Block, Kind> {
        val colours = listOf(
            "RUBY" to "red", "AMBER" to "orange", "SAPPHIRE" to "light_blue", "JADE" to "lime", "AMETHYST" to "purple",
            "TOPAZ" to "yellow", "JASPER" to "magenta", "OPAL" to "white", "AQUAMARINE" to "blue", "CITRINE" to "brown",
            "ONYX" to "black", "PERIDOT" to "green",
        )
        val out = HashMap<Block, Kind>()
        for ((gem, colour) in colours) {
            val kind = Kind(gem.lowercase().replaceFirstChar { it.uppercase() }, false, "FLAWED_${gem}_GEM", "Flawed $gem", 80, GEM_BASE,
                single = "ROUGH_${gem}_GEM", gem = true)
            for (suffix in listOf("_stained_glass", "_stained_glass_pane")) {
                val id = net.minecraft.resources.Identifier.withDefaultNamespace(colour + suffix)
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).ifPresent { out[it] = kind }
            }
        }
        return out
    }
    private const val GEM_BASE = 4.0
    private const val HARD_STONE = "CONCENTRATED_STONE"
    // Commissions: profit, Mining XP and your pet, not collection and fortune
    private val ISLANDS = setOf("Crystal Hollows", "Dwarven Mines", "Glacite Tunnels", "Mineshaft", END)
    private const val END = "The End"
    // Collections shown an hour, and the only blocks that start the clock off the commission islands
    private val COLLECTED = setOf("ENCHANTED_COAL", "ENCHANTED_OBSIDIAN")

    // Broken this near you by the server (Efficient Miner, Mining Spread): yours
    private const val NEAR_YOU = 8.0
    private const val READ_MS = 3_000L
    private const val MANIAC_STEP = 10.0
    private const val MANIAC_BLEGG = 15.0
    private const val FIESTA_MORE = 0.25
    private const val FLASK_MORE = 0.25
    private const val FLASK_MS = 3_600_000L
    private const val MANIAC_MAX = 500.0
    private const val HELD_MS = 500L
    private const val IDLE_MS = 30_000L
    // The same block reported twice (by your client, then the server) within this long is one break
    private const val SAME_BREAK_MS = 1_500L

    // Per kind: blocks, their expected drops, Compact's procs, and the drops counted for the last block
    // (taken back if Compact procs on it)
    private class Count(var blocks: Int = 0, var drops: Double = 0.0, var compact: Int = 0, var last: Double = 0.0,
                        var byYou: Int = 0)

    /**
     * One overlay's session: what's been counted there, its clock, its loot, and what the pets made there (by pet,
     * "name|rarity", with what an XP of it was last worth). For the gems, the gaps between the gem blocks you broke.
     */
    private class Session(val key: String) {
        val counts = LinkedHashMap<Kind, Count>()
        val pristine = LinkedHashMap<String, Double>()
        var miningXp = 0.0
        var startTime = 0L
        var lastBreak = 0L
        var paused = false
        var pauseStart = 0L
        val loot = LootTracker.Bag()
        val petXp = LinkedHashMap<String, Double>()
        val petRate = HashMap<String, Double>()
        val gaps = ArrayDeque<Int>()
        var lastGem = 0L
        var gemsByYou = 0
        val activeMs: Long get() = if (startTime == 0L) 0L else (if (paused) pauseStart else System.currentTimeMillis()) - startTime
        fun any() = counts.isNotEmpty() || miningXp > 0 || loot.any() || pristine.isNotEmpty() || petXp.values.any { it > 0 }
    }

    const val HOLLOWS = "hollows"
    const val GEMS = "gems"
    const val BASE_CAMP = "basecamp"
    const val THE_END = "end"
    /** The overlays, in the order they're previewed when moving them. */
    val KEYS = listOf(HOLLOWS, GEMS, BASE_CAMP, THE_END)
    val NAMES = mapOf(HOLLOWS to "Crystal Hollows coal", GEMS to "Crystal Hollows gems", BASE_CAMP to "Base Camp", THE_END to "The End")
    private val sessions = LinkedHashMap<String, Session>()
    private fun session(key: String) = sessions.getOrPut(key) { Session(key) }
    // What the counting's going into now (a throwaway one where nothing's counted)
    private var cur = Session("none")
    private fun use(s: Session?) { cur = s ?: Session("none") }

    // Gems in the Hollows: on from 3 gem blocks in a row (no coal between), off with the next coal. The ones
    // before it's on, as they were counted (to the coal's session), to move over
    private var gemMode = false
    private class Pending(val kind: Kind, val drops: Double, val at: Long, val byYou: Boolean)
    private val streak = ArrayList<Pending>()
    private const val GEM_STREAK = 3
    // Gaps between gem blocks you break, longer than this, aren't one block after the next
    private const val GEM_GAP_MAX = 1_500
    private const val GAPS_KEPT = 600
    // Breaking a gem at your best takes this low a share of your gaps (or less): the ideal, the efficiency's against
    private const val IDEAL_SHARE = 0.1
    private const val IDEAL_MIN_GAPS = 20

    /** Which overlay's where you are: null where none is (the Dwarven Mines, anywhere that isn't mining). */
    fun placeKey(): String? {
        val island = IslandTracker.currentMiningIsland
        return when {
            island == "Crystal Hollows" -> if (gemMode) GEMS else HOLLOWS
            island == "Mineshaft" || island == "Glacite Tunnels" -> BASE_CAMP
            island == "Dwarven Mines" && baseCamp() -> BASE_CAMP
            island == END -> THE_END
            else -> null
        }
    }
    private fun baseCamp() = try {
        dev.miningprofit.commissions.PickoRules.current() === dev.miningprofit.commissions.PickoRules.GLACITE
    } catch (_: Throwable) { false }
    private fun sessionHere(): Session? = placeKey()?.let { session(it) }

    private val counts get() = cur.counts


    // Fortune as last read from the tab list
    private var mining = 0
    // Mining Fortune by the item in your hand (its SkyBlock uuid, else its id), as the tab list last read with
    // it held SETTLE_MS or more (the tab list's a moment behind a swap); the last ITEMS_KEPT held
    private val byItem = LinkedHashMap<String, Int>()
    private var heldKey: String? = null
    private var heldSince = 0L
    private var keyStack: net.minecraft.world.item.ItemStack? = null
    private const val SETTLE_MS = 3_000L
    // The last tab list reading taken as it is (not in Maniac Miner), and the buffs in it then; when Maniac Miner
    // last ended, and when the buffs last moved (the tab list's a moment behind either)
    private var tabAt = 0L
    private var miningBuffs = 0
    private var maniacEnded = 0L
    private var buffsAt = 0L
    private var lastBuffs = 0
    // The drill Maniac Miner was used with (its key), and what's been counted towards it since, by kind
    private var procKey: String? = null
    private val maniacBlocks = LinkedHashMap<String, Int>()
    private var maniacFrom = 0L
    private var maniacTab = -1
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    // Every item's kept Mining Fortune but [except]'s moved by [d]
    private fun shift(d: Int, except: String?) {
        for (k in byItem.keys.toList()) if (k != except) byItem[k] = byItem[k]!! + d
    }

    // The Sky Mall's buff (Mining Fortune it gives: 50 or 0) and the SkyBlock day it was said for (kept with the fortune)
    private var skyMall = 0
    private var skyMallDay = -1L
    private const val SKYBLOCK_EPOCH = 1_560_275_700_000L
    private const val SKYBLOCK_DAY_MS = 1_200_000L
    private fun skyblockDay(now: Long) = (now - SKYBLOCK_EPOCH) / SKYBLOCK_DAY_MS
    private val SKY_MALL = Regex("^New buff: (.+)$")
    private val SKY_MALL_FORTUNE = Regex("[+]([0-9]+) Mining Fortune")
    // Better Together: on (till when: 20 minutes from its start, or till it's said to be over), and the sidebar's
    // count of the other players in your zone ("Players: 3")
    private var togetherUntil = 0L
    private const val TOGETHER_MS = 20 * 60_000L
    private const val TOGETHER_EACH = 20
    private const val TOGETHER_MAX = 5
    private val PLAYERS = Regex("^[^A-Za-z0-9]*Players:? *([0-9]+)", RegexOption.IGNORE_CASE)
    private var togetherLogged = false

    // The players the sidebar says are in your zone in Better Together, or null if it isn't on
    private fun togetherPlayers(now: Long): Int? {
        val lines = dev.miningprofit.support.ScoreboardUtils.lines
        if (now >= togetherUntil && lines.none { "Better Together" in it }) return null
        val n = lines.firstNotNullOfOrNull { PLAYERS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (n == null && !togetherLogged && lines.isNotEmpty()) {
            togetherLogged = true
            log.info("[MP profit] Better Together on, but no players line on the sidebar: " + lines.joinToString(" | "))
        }
        return n
    }

    // The Mining Fortune of the buffs that come and go, as they are now
    private fun buffs(now: Long) = skyMall + TOGETHER_EACH * minOf(togetherPlayers(now) ?: 0, TOGETHER_MAX)

    // A day's Sky Mall buff said: [fortune] of it. When the last one known wasn't the day before's (or today's),
    // what it was is anyone's guess - the fortune's kept as it was, the buff taken as what it's in now
    private fun skyMallSaid(fortune: Int, now: Long) {
        if (!fortuneLoaded) loadFortune()
        val day = skyblockDay(now)
        if (skyMallDay < day - 1) {
            val d = fortune - skyMall
            shift(-d, null)
            miningBuffs += d
        }
        log.info("[MP profit] Sky Mall: +$fortune Mining Fortune (was +$skyMall, day ${if (skyMallDay < day - 1) "unknown" else "known"})")
        skyMall = fortune
        skyMallDay = day
        saveFortune()
    }
    private const val ITEMS_KEPT = 16

    // What's in your hand, by its key; when it changed to that (not when only its stack did - a drill's fuel
    // going down makes a new one)
    private fun held(now: Long): String? {
        val stack = Minecraft.getInstance().player?.mainHandItem
        if (stack !== keyStack) {
            keyStack = stack
            val key = stack?.takeIf { !it.isEmpty }?.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)?.copyTag()?.let { t ->
                t.getStringOr("uuid", "").ifEmpty { t.getStringOr("id", "") }.ifEmpty { null }
            }
            if (key != heldKey) {
                heldKey = key
                heldSince = now
            }
        }
        return heldKey
    }

    // Mining Fortune for a block broken now: the tab list's, if it was read with what's in your hand (not
    // while Maniac Miner holds it, nor since a swap), else what's in your hand's own as last read with it -
    // either without the buffs then, and with them as they are now
    private fun miningNow(now: Long): Int {
        val key = held(now)
        val own = if (key != null && (maniac || tabAt - heldSince < SETTLE_MS)) byItem[key] else null
        return (own ?: (mining - miningBuffs)) + buffs(now)
    }
    private var oreF = 0
    private var blockF = 0
    private var gemF = 0
    private var metalF = 0
    // Mining XP: gained (the session's), and the action bar's last reading of it
    private var miningXp: Double get() = cur.miningXp; set(v) { cur.miningXp = v }
    private var xpNow = -1.0
    private var xpBar = ""
    private var xpBarAt = 0L
    private var readAt = 0L
    // Maniac Miner: on, and the blocks mined since it started
    private var maniac = false
    private var maniacBonus = 0.0
    // Whether the drill in your hand has a Blue Cheese part, as last looked (every HELD_MS at most)
    private var heldBlegg = false
    private var heldAt = 0L
    // A Fiesta Flask's: till when (kept with the fortune, so it's still known after a restart)
    private var flaskUntil = 0L
    private val flaskOn get() = System.currentTimeMillis() < flaskUntil
    // What Mining Fiesta makes of Maniac Miner's fortune: 1, 1.25, or 1.5 with a flask
    private val fiestaTimes get() = if (!Election.miningFiesta) 1.0 else 1.0 + FIESTA_MORE + (if (flaskOn) FLASK_MORE else 0.0)

    /** The item in [hand] used: a Fiesta Flask is taken as drunk (an hour's worth, unless one's already running). */
    fun onUse(player: net.minecraft.world.entity.player.Player, hand: net.minecraft.world.InteractionHand) {
        val name = player.getItemInHand(hand).takeIf { !it.isEmpty }?.hoverName?.string ?: return
        if (!name.contains("Fiesta Flask") || flaskOn) return
        setFlask(true)
        org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] Fiesta Flask used: on for an hour")
    }

    /** The flask set as running (an hour from now) or not, by hand; what to say of it. */
    fun setFlask(on: Boolean): String {
        if (!fortuneLoaded) loadFortune()
        flaskUntil = if (on) System.currentTimeMillis() + FLASK_MS else 0L
        saveFortune()
        return flaskStatus()
    }

    fun flaskStatus(): String {
        if (!fortuneLoaded) loadFortune()
        val left = (flaskUntil - System.currentTimeMillis()) / 60_000
        return "Mining Fiesta ${if (Election.miningFiesta) "on" else "off"}${Election.jerryState.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""} | Fiesta Flask ${if (flaskOn) "on, ${left + 1}m left" else "off"} | Maniac Miner x$fiestaTimes"
    }
    private var heldStack: net.minecraft.world.item.ItemStack? = null

    // The clock, as CommissionsTracker's: started by the first block, paused (from the last one) after
    // IDLE_MS without any, and picked up again by the next
    private var startTime: Long get() = cur.startTime; set(v) { cur.startTime = v }
    private var lastBreak: Long get() = cur.lastBreak; set(v) { cur.lastBreak = v }
    private var paused: Boolean get() = cur.paused; set(v) { cur.paused = v }
    private var pauseStart: Long get() = cur.pauseStart; set(v) { cur.pauseStart = v }
    private val activeMs: Long get() = cur.activeMs
    private val recent = HashMap<Long, Long>()

    /** Set while the overlay is being dragged. */
    var dragging: Pair<Int, Int>? = null

    /** The session where you are starts again (in the Hollows the coal's and the gems' both); [all]: every one. What was reset, by name. */
    fun reset(all: Boolean = false): List<String> {
        val keys = if (all) KEYS else when (val k = placeKey()) {
            HOLLOWS, GEMS -> listOf(HOLLOWS, GEMS)
            null -> emptyList()
            else -> listOf(k)
        }
        for (k in keys) sessions.remove(k)
        if (HOLLOWS in keys) { gemMode = false; streak.clear() }
        use(null)
        gone.clear()
        LootTracker.reset()
        LootTracker.bag = sessionHere()?.loot ?: LootTracker.Bag()
        saveSessions()
        return keys.map { NAMES[it] ?: it }.distinct()
    }

    val maniacOn: Boolean get() = dev.miningprofit.support.ManiacMiner.on

    // Fortune off the tab list ("Mining Fortune: ☘123"), every READ_MS - not while Maniac Miner's on

    fun onClientTick() {
        val now = System.currentTimeMillis()
        if (!sessionsLoaded) loadSessions()
        val here = sessionHere()
        use(if (here?.key == BASE_CAMP) here else null)
        // What comes in goes to where you are
        LootTracker.bag = here?.loot ?: scratchBag
        attributePets(here)
        if (now - sessionsSavedAt >= SESSIONS_SAVE_MS) saveSessions()
        // Each session's clock pauses on its own
        for (s in sessions.values) {
            use(s)
            if (startTime != 0L && !paused && now - lastBreak >= IDLE_MS) {
                paused = true
                pauseStart = lastBreak
            }
        }
        use(here)
        // The Littlefoot drops that only show up in the inventory
        if (Config.profitTracker) LootTracker.onClientTick()
        // What it's working from, in the log every minute while you're mining (to check it against)
        if (lastBreak > 0 && now - lastBreak < IDLE_MS && now - summaryAt > SUMMARY_MS) {
            summaryAt = now
            org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] " + counts.entries.joinToString(" ; ") { (k, c) ->
                val v = valueOf(k, c)
                "${k.name}: ${c.blocks} blocks (${c.byYou} by you, ${c.blocks - c.byYou} near you), drops ${"%.0f".format(c.drops)}, " +
                    "compact ${c.compact}, worth ${v?.let { "%,.0f".format(it) } ?: "?"}"
            } + " | gone by distance (<=${NEAR_YOU.toInt()}/<=16/<=32/further): " +
                gone.entries.joinToString(" ; ") { (k, a) -> "${k.name} ${a.joinToString("/")}" } +
                " | fortune ${miningNow(now)}/$oreF/$blockF/$gemF/$metalF" + (if (maniac) " +maniac ${"%.1f".format(maniacBonus)} (step ${maniacStep(System.currentTimeMillis())})" else "") +
                " | ${activeMs / 1000}s")
        }
        val on = maniacOn
        if (on && !maniac) {
            maniacBonus = 0.0
            procKey = held(now)
            maniacBlocks.clear()
            maniacFrom = now
            maniacTab = -1
        }
        if (!on && maniac) {
            maniacEnded = now
            log.info("[MP profit] Maniac Miner over after ${(now - maniacFrom) / 1000}s: +${"%.0f".format(maniacBonus)} counted, from " +
                maniacBlocks.entries.joinToString { "${it.value} ${it.key}" })
        }
        // (asked for before it's wanted, so it's in by the first block)
        if (on) Election.miningFiesta
        maniac = on
        // The buffs moving (the tab list's behind them a moment)
        val b = buffs(now)
        if (b != lastBuffs) {
            log.info("[MP profit] buffs ${lastBuffs} > $b (Sky Mall +$skyMall, Better Together players ${togetherPlayers(now)})")
            lastBuffs = b
            buffsAt = now
        }
        // In Maniac Miner: each move of the tab list's Mining Fortune in the log, by what's been counted towards it
        // so far (to see what the game counts, against what's counted here)
        if (on) maniacTab()
        if (now - readAt < READ_MS) return
        readAt = now
        if (!fortuneLoaded) loadFortune()
        // (the tab list's a moment behind: just after Maniac Miner it's still in it, and just after a buff it isn't yet)
        if (on || now - maniacEnded < SETTLE_MS || now - buffsAt < SETTLE_MS) return
        val tab = HashMap<String, Int>()
        for (line in Tab.lines) {
            val m = FORTUNE.find(line) ?: continue
            tab[m.groupValues[1]] = m.groupValues[2].replace(",", "").toDoubleOrNull()?.toInt() ?: continue
        }
        val found = tab.isNotEmpty()
        val key = held(now)
        val settled = key != null && now - heldSince >= SETTLE_MS
        val before = listOf(mining, oreF, blockF, gemF, metalF)
        tab["Mining"]?.let { mining = it }
        tab["Ore"]?.let { oreF = it }
        tab["Block"]?.let { blockF = it }
        tab["Gemstone"]?.let { gemF = it }
        tab["Dwarven Metal"]?.let { metalF = it }
        if (found) {
            tabAt = now
            miningBuffs = b
        }
        // (and as what's in your hand gives without the buffs, once it's been held long enough for the tab list to
        // show it - what it's moved by since it was last, every other item's moved by too)
        var itemChanged = false
        val own = mining - b
        if (tab["Mining"] != null && settled && byItem[key] != own) {
            val was = byItem.remove(key!!)
            if (was != null) {
                log.info("[MP profit] fortune moved ${own - was} (${was} > $own without the buffs, with the same item in hand): the ${byItem.size} other items' moved by it")
                shift(own - was, key)
            }
            byItem[key] = own
            while (byItem.size > ITEMS_KEPT) byItem.remove(byItem.keys.first())
            itemChanged = true
        }
        if (found && (itemChanged || before != listOf(mining, oreF, blockF, gemF, metalF))) saveFortune()
        // No fortune in the tab list in a mining island: the whole tab list in the log (a few times a session),
        // to see where it's shown and how
        if (!found && IslandTracker.currentMiningIsland in ISLANDS && Tab.lines.isNotEmpty() && loggedLines < 3 &&
            now - loggedAt > 60_000L) {
            loggedLines++
            loggedAt = now
            org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] no fortune in the tab list; it reads: " +
                Tab.lines.joinToString(" | "))
        }
    }

    // The action bar's Mining XP: "+12.5 Mining (1,234,567/2,000,000)" - what's needed shortened at times
    // ("/3.5M") - or with a percentage, or nothing after it
    private val XP_FULL = Regex("""\+([\d,.]+) Mining \(([\d,.]+)/([\d,.]+)([kKmMbB]?)\)""")
    private val XP_GAIN = Regex("""\+([\d,.]+) Mining(?: \([^)]*\))?""")
    // "+73.9 Mining (17.65%)": the percentage of the way to your next Mining level - each gain reads the
    // same until that moves on, so the XP is taken from how far it's moved, of what the level takes
    private val XP_PCT = Regex("""\+[\d,.]+ Mining \(([\d.]+)%\)""")
    // Skill XP from each level to the next (0 to 1 first)
    private val SKILL_XP = longArrayOf(50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000, 30000,
        50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000, 1300000,
        1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000, 2600000, 2750000,
        2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000, 6100000, 6400000, 6700000,
        7000000)
    private var pctNow = -1.0
    private var pctLevel = -1

    // The first few different Mining XP action bars of a session, in the log (to check how it's worded)
    private val barsLogged = HashSet<String>()

    /** The action bar (colour codes stripped): Mining XP. */
    fun onActionBar(text: String) {
        if (barsLogged.size < 6 && "Mining" in text) Regex("""\+[\d,.]+ Mining[^+]{0,40}""").find(text)?.value?.let {
            if (barsLogged.add(it)) org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] action bar: $it")
        }
        // Shown for commissions only (in the Hollows hard stone gives it too, and mustn't start the clock)
        if (!Config.profitTracker) return
        val now = System.currentTimeMillis()
        val full = XP_FULL.find(text)
        if (full != null) {
            // From the running total, so a message shown over and over isn't counted each time
            val cur = full.groupValues[2].replace(",", "").toDoubleOrNull() ?: return
            val need = (full.groupValues[3].replace(",", "").toDoubleOrNull() ?: return) *
                when (full.groupValues[4].lowercase()) { "k" -> 1e3; "m" -> 1e6; "b" -> 1e9; else -> 1.0 }
            if (xpNow >= 0) {
                val d = if (cur >= xpNow) cur - xpNow else (xpNeed - xpNow).coerceAtLeast(0.0) + cur
                if (d > 0) gainedXp(d, now)
            }
            xpNow = cur
            xpNeed = need
            return
        }
        val level = ExpShare.miningLevel
        val pct = XP_PCT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (pct != null && level in 0 until SKILL_XP.size) {
            if (pctNow >= 0 && pctLevel in 0 until SKILL_XP.size) {
                val d = when {
                    pct >= pctNow && level == pctLevel -> (pct - pctNow) / 100.0 * SKILL_XP[level]
                    // A level gained: the rest of the old one and this much of the new (the level-up's message
                    // may be yet to come)
                    else -> {
                        val next = if (level == pctLevel) minOf(level + 1, SKILL_XP.size - 1) else level
                        (100.0 - pctNow) / 100.0 * SKILL_XP[pctLevel] + pct / 100.0 * SKILL_XP[next]
                    }
                }
                if (d > 0) gainedXp(d, now)
            }
            pctNow = pct
            pctLevel = level
            return
        }
        val gain = XP_GAIN.find(text) ?: return
        // No total to go by: a new message is a new gain, the same one still up (the bar's sent again and
        // again while it shows, and the rest of it - health, mana - changes meanwhile) isn't
        val shown = gain.value
        if (shown == xpBar && now - xpBarAt < 3_000L) {
            xpBarAt = now
            return
        }
        xpBar = shown
        xpBarAt = now
        gainedXp(gain.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0, now)
    }
    private var xpNeed = 0.0

    // Mining XP gained: anywhere, it levels the pets you're sharing XP with; on commissions it's shown too
    private fun gainedXp(d: Double, now: Long) {
        ExpShare.onMiningXp(d)
        val s = sessionHere() ?: return
        use(s)
        miningXp += d
        // (in the Hollows hard stone gives it too, and mustn't start the clock)
        if (s.key == BASE_CAMP) active(now)
    }

    // You're mining: the clock starts, or picks up again without the time it was paused
    private fun active(now: Long) {
        if (startTime == 0L) startTime = now
        lastBreak = now
        if (paused) {
            startTime += now - pauseStart
            paused = false
        }
    }

    /** Commission progress (or what counts as it): on commissions the clock runs with the commission tracker's. */
    fun onCommissionActivity(now: Long) {
        if (!Config.profitTracker || placeKey() != BASE_CAMP) return
        use(session(BASE_CAMP))
        active(now)
    }

    // Fortune as last read, kept over restarts: the tab list doesn't show it everywhere (not in the Dwarven
    // Mines without its widget), and without it every block would count as its base drop
    private var fortuneLoaded = false
    private val fortuneFile get() = dev.miningprofit.support.DataDir.file("fortune.json")

    private fun loadFortune() {
        fortuneLoaded = true
        try {
            if (!java.nio.file.Files.exists(fortuneFile)) return
            val o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(fortuneFile)).asJsonObject
            if (mining == 0) mining = o.get("mining")?.asInt ?: 0
            if (oreF == 0) oreF = o.get("ore")?.asInt ?: 0
            if (blockF == 0) blockF = o.get("block")?.asInt ?: 0
            if (gemF == 0) gemF = o.get("gem")?.asInt ?: 0
            if (metalF == 0) metalF = o.get("metal")?.asInt ?: 0
            flaskUntil = o.get("flaskUntil")?.asLong ?: 0L
            o.getAsJsonObject("byItem")?.entrySet()?.forEach { (k, v) -> if (k !in byItem) byItem[k] = v.asInt }
            skyMall = o.get("skyMall")?.asInt ?: 0
            skyMallDay = o.get("skyMallDay")?.asLong ?: -1L
        } catch (_: Exception) {
        }
    }

    private fun saveFortune() {
        val o = com.google.gson.JsonObject()
        o.addProperty("mining", mining); o.addProperty("ore", oreF); o.addProperty("block", blockF)
        o.addProperty("gem", gemF); o.addProperty("metal", metalF)
        o.addProperty("flaskUntil", flaskUntil)
        o.add("byItem", com.google.gson.JsonObject().apply { for ((k, v) in byItem) addProperty(k, v) })
        o.addProperty("skyMall", skyMall)
        o.addProperty("skyMallDay", skyMallDay)
        dev.miningprofit.support.SaveFile.write(fortuneFile, o.toString())
    }

    // The stats menu's lines ("☘ Ore Fortune 123", no colon or plus: an item's "Ore Fortune: +5" isn't your total)
    private val MENU_FORTUNE = Regex("""^\s*\S{0,2}\s*(Mining|Ore|Block|Gemstone|Dwarven Metal) Fortune\s+(?:[^\w\s]\s*)?(\d[\d,]*(?:\.\d+)?)""")

    /**
     * The lines of a stats menu ("Your Equipment and Stats", colour codes stripped): the fortunes the tab list
     * doesn't show (Ore, Block, Gemstone, Dwarven Metal). Mining Fortune is the tab list's to say - it moves
     * with buffs - so it's only taken from here when the tab list hasn't given one.
     */
    fun onStatsMenu(lines: List<String>) {
        if (!fortuneLoaded) loadFortune()
        var changed = false
        for (line in lines) {
            val m = MENU_FORTUNE.find(line) ?: continue
            val v = m.groupValues[2].replace(",", "").toDoubleOrNull()?.toInt() ?: continue
            val kind = m.groupValues[1]
            if (kind == "Mining" && mining != 0) continue
            val was = when (kind) { "Mining" -> mining; "Ore" -> oreF; "Block" -> blockF; "Gemstone" -> gemF; else -> metalF }
            if (was == v) continue
            when (kind) { "Mining" -> mining = v; "Ore" -> oreF = v; "Block" -> blockF = v; "Gemstone" -> gemF = v; else -> metalF = v }
            changed = true
        }
        if (changed) {
            saveFortune()
            org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] stats menu: fortune $mining/$oreF/$blockF/$gemF/$metalF (mining/ore/block/gem/metal)")
        }
    }

    /** /miningprofit fortune <mining|ore|block|gem|metal> <n>: set by hand (the tab list's Mining Fortune still moves it). */
    fun setFortune(kind: String, value: Int): Boolean {
        if (!fortuneLoaded) loadFortune()
        when (kind.lowercase()) {
            "mining" -> mining = value
            "ore" -> oreF = value
            "block" -> blockF = value
            "gem", "gemstone" -> gemF = value
            "metal", "dwarven" -> metalF = value
            else -> return false
        }
        saveFortune()
        return true
    }

    /** The fortunes as they stand (for /miningprofit fortune). */
    fun fortuneStatus(): String {
        if (!fortuneLoaded) loadFortune()
        val now = System.currentTimeMillis()
        val inHand = held(now)?.let { k -> byItem[k]?.let { " (held item's: $it + buffs ${buffs(now)})" } } ?: ""
        return "Mining $mining$inHand | Sky Mall +$skyMall | Better Together ${togetherPlayers(now)?.let { "$it players" } ?: "off"} | Ore $oreF | Block $blockF | Gemstone $gemF | Dwarven Metal $metalF"
    }

    private val COMPACT = Regex("""^COMPACT! You found an? (.+?)!""")

    /** A chat message (colour codes stripped): Compact's drops. */
    fun onChat(text: String) {
        if (!Config.profitTracker) return
        use(sessionHere())
        // Corpses, the excavator, charmed shards
        LootTracker.onChat(text)
        // The buffs that come and go: the day's Sky Mall buff, Better Together starting and ending
        SKY_MALL.find(text.trim())?.let { m ->
            skyMallSaid(SKY_MALL_FORTUNE.find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0, System.currentTimeMillis())
        }
        if ("BETTER TOGETHER STARTED!" in text) {
            togetherUntil = System.currentTimeMillis() + TOGETHER_MS
            togetherLogged = false
        }
        if ("BETTER TOGETHER ENDED!" in text) togetherUntil = 0L
        // (what the game says of the flask, in the log: to go by that and not the click, once it's known)
        if ("Fiesta Flask" in text) org.slf4j.LoggerFactory.getLogger("MiningProfit").info("[MP profit] chat names the flask: $text")
        // Pristine: gemstones on top of the block's drops
        PRISTINE.find(text)?.let { m ->
            val n = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@let
            pristine[m.groupValues[1]] = (pristine[m.groupValues[1]] ?: 0.0) + n * GEM_TIERS[0]
        }
        val m = COMPACT.find(text) ?: return
        // (hard stone's is an Enchanted Hard Stone, which isn't what it's sold as: 576 more of its drops)
        if (m.groupValues[1].equals("Enchanted Hard Stone", ignoreCase = true)) {
            KINDS[Blocks.STONE]?.let { counts.getOrPut(it) { Count() }.drops += 576.0 }
            return
        }
        val kind = KINDS.values.firstOrNull { it.itemName.equals(m.groupValues[1], ignoreCase = true) } ?: return
        // (as with the blocks: obsidian's only in the End, and only obsidian there)
        if (!here(kind) || placeKey() == null) return
        val c = counts.entries.firstOrNull { it.key.item == kind.item }?.value ?: counts.getOrPut(kind) { Count() }
        // On top of the block's own drops
        c.compact++
    }

    // Whether [kind]'s counted on the island you're on: obsidian only in the End, and only obsidian there
    private fun here(kind: Kind?) = (kind?.item == "ENCHANTED_OBSIDIAN") == (IslandTracker.currentMiningIsland == END)

    // "Mining Fortune: ☘123", and the like with other symbols or spacing
    private val FORTUNE = Regex("""(Mining|Ore|Block|Gemstone|Dwarven Metal) Fortune\D{0,6}?([\d][\d,.]*)""")
    // Per kind, blocks gone by distance from you: up to NEAR_YOU, 16, 32, further
    private val gone = HashMap<Kind, IntArray>()
    private var summaryAt = 0L
    private const val SUMMARY_MS = 60_000L
    private var loggedLines = 0
    private var loggedAt = 0L

    /** A block's gone: [old] was there, broken by your client ([byYou]) or by the server's update. */
    @JvmStatic
    fun onBlockGone(pos: BlockPos, old: BlockState, byYou: Boolean) {
        if (!Config.profitTracker) return
        val kind = KINDS[old.block]
        // A Fossil (quartz block, slab, stairs) in a Mineshaft gives Fossil Essence
        val fossil = old.block in FOSSILS && IslandTracker.currentMiningIsland == "Mineshaft"
        if (kind == null && !fossil) return
        if (placeKey() == null) return
        // (obsidian only in the End, and only obsidian there)
        if (!here(kind)) return
        val mc = Minecraft.getInstance()
        val eye = mc.player?.eyePosition ?: return
        val dist = eye.distanceTo(Vec3.atCenterOf(pos))
        // Every one gone, by how far from you (for the log: what's counted against what isn't)
        if (!byYou && kind != null) {
            val b = when {
                dist <= NEAR_YOU -> 0
                dist <= 16 -> 1
                dist <= 32 -> 2
                else -> 3
            }
            gone.getOrPut(kind) { IntArray(4) }[b]++
        }
        if (!byYou && dist > NEAR_YOU) return
        val now = System.currentTimeMillis()
        val key = pos.asLong()
        recent[key]?.let { if (now - it < SAME_BREAK_MS) return }
        recent[key] = now
        if (recent.size > 512) recent.entries.removeIf { now - it.value > SAME_BREAK_MS }
        // The Hollows: coal ends the gems' (and their streak); otherwise the counting's for where you are
        val hollows = IslandTracker.currentMiningIsland == "Crystal Hollows"
        if (hollows && kind?.item == "ENCHANTED_COAL") {
            gemMode = false
            streak.clear()
        }
        use(sessionHere())

        if (kind == null) {
            LootTracker.fossilBlock()
            active(now)
            return
        }

        // Fortune for this block: the tab list's, with Maniac Miner's on top while it's on
        val bonus = if (maniac) maniacBonus else 0.0
        if (maniac) {
            maniacBonus = minOf(maniacBonus + maniacStep(now), MANIAC_MAX * fiestaTimes)
            maniacBlocks[kind.name] = (maniacBlocks[kind.name] ?: 0) + 1
        }
        val base = miningNow(now)
        val fortune = when {
            kind.gem -> base + bonus + gemF
            kind.metal -> base + bonus + metalF
            kind.ore -> base + bonus + oreF
            else -> base + bonus + blockF
        }
        val c = counts.entries.firstOrNull { it.key.item == kind.item }?.value ?: counts.getOrPut(kind) { Count() }
        c.blocks++
        if (byYou) c.byYou++
        c.last = kind.base * (1 + fortune / 100.0)
        c.drops += c.last

        // In the Hollows only coal starts or resumes the clock (not the stone or other ores on the way) - the
        // gems' their gems - in the End obsidian (and hard stone doesn't anywhere)
        if (kind.item != HARD_STONE && (cur.key == BASE_CAMP || kind.item in COLLECTED || (cur.key == GEMS && kind.gem))) active(now)
        if (hollows && kind.gem) gemBlock(kind, c.last, now, byYou)
    }

    // A gem block in the Hollows: towards the streak that starts the gems' session, and (in it) its gaps
    private fun gemBlock(kind: Kind, drops: Double, now: Long, byYou: Boolean) {
        if (gemMode) {
            gemGap(session(GEMS), now, byYou)
            return
        }
        streak.add(Pending(kind, drops, now, byYou))
        if (streak.size < GEM_STREAK) return
        // Three in a row: the gems' from the first of them, those three moved over from the coal's
        val h = session(HOLLOWS)
        val g = session(GEMS)
        for (p in streak) {
            h.counts.entries.firstOrNull { it.key.item == p.kind.item }?.value?.let { c ->
                c.blocks--
                c.drops -= p.drops
                if (p.byYou) c.byYou--
                if (c.blocks <= 0) h.counts.entries.removeIf { it.value === c }
            }
            val c = g.counts.entries.firstOrNull { it.key.item == p.kind.item }?.value ?: g.counts.getOrPut(p.kind) { Count() }
            c.blocks++
            c.drops += p.drops
            if (p.byYou) c.byYou++
        }
        gemMode = true
        use(g)
        active(streak.first().at)
        lastBreak = now
        for (p in streak) gemGap(g, p.at, p.byYou)
        streak.clear()
    }

    private fun gemGap(g: Session, at: Long, byYou: Boolean) {
        if (!byYou) return
        if (g.lastGem > 0) {
            val gap = at - g.lastGem
            if (gap in 1..GEM_GAP_MAX.toLong()) {
                g.gaps.addLast(gap.toInt())
                while (g.gaps.size > GAPS_KEPT) g.gaps.removeFirst()
            }
        }
        g.lastGem = at
        g.gemsByYou++
    }

    // How close to never waiting the gems have been: each block at your best gap (see IDEAL_SHARE), against the
    // uptime. Null till there's enough to say what your best is
    private fun gemEfficiency(g: Session): Double? {
        if (g.gaps.size < IDEAL_MIN_GAPS || g.activeMs <= 0) return null
        val sorted = g.gaps.sorted()
        val ideal = sorted[(sorted.size * IDEAL_SHARE).toInt().coerceIn(0, sorted.size - 1)]
        return (g.gemsByYou * ideal.toDouble() / g.activeMs).coerceAtMost(1.0)
    }

    // --- the pets: what each made since last looked goes to where you are

    private val petSeen = java.util.IdentityHashMap<ExpShare.Pet, Double>()
    private var petsPrimed = false
    private val scratchBag = LootTracker.Bag()
    private fun petKey(p: ExpShare.Pet) = "${p.name}|${p.rarity}"

    private fun attributePets(to: Session?) {
        for (p in ExpShare.allPets()) {
            val now = p.session
            // (what pets had made before the first look is no session's)
            val was = petSeen[p] ?: if (petsPrimed) 0.0 else now
            petSeen[p] = now
            val d = now - was
            if (d == 0.0 || to == null) continue
            val k = petKey(p)
            to.petXp[k] = (to.petXp[k] ?: 0.0) + d
            if (now > 0) to.petRate[k] = ExpShare.petValue(p) / now
        }
        petsPrimed = true
    }

    // What the pets made in [s]: each one's XP there at what an XP of it is worth now (or was last)
    private fun petCoins(s: Session): Double = s.petXp.entries.sumOf { (k, xp) -> xp.coerceAtLeast(0.0) * petRateOf(s, k) }
    private fun petRateOf(s: Session, k: String): Double {
        val live = ExpShare.allPets().firstOrNull { petKey(it) == k && it.session > 0 }
        live?.let { s.petRate[k] = ExpShare.petValue(it) / it.session }
        return s.petRate[k] ?: 0.0
    }

    // --- saving the sessions (not with Config.overlayResetOnLaunch: each run starts them afresh)

    private var sessionsLoaded = false
    private var sessionsSavedAt = 0L
    private const val SESSIONS_SAVE_MS = 30_000L
    private val sessionsFile get() = dev.miningprofit.support.DataDir.file("overlay_sessions.json")

    private fun loadSessions() {
        sessionsLoaded = true
        sessionsSavedAt = System.currentTimeMillis()
        if (Config.overlayResetOnLaunch) return
        try {
            if (!java.nio.file.Files.exists(sessionsFile)) return
            val o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(sessionsFile)).asJsonObject
            o.getAsJsonObject("sessions")?.entrySet()?.forEach { (key, v) -> sessions[key] = readSession(key, v.asJsonObject) }
            o.getAsJsonObject("commissions")?.let { dev.miningprofit.commissions.CommissionsTracker.readState(it) }
        } catch (e: Exception) {
            log.warn("[MP profit] couldn't read the overlay sessions: $e")
        }
    }

    private fun saveSessions() {
        sessionsSavedAt = System.currentTimeMillis()
        if (Config.overlayResetOnLaunch) return
        val o = com.google.gson.JsonObject()
        o.add("sessions", com.google.gson.JsonObject().apply { for ((k, s) in sessions) if (s.any()) add(k, writeSession(s)) })
        o.add("commissions", dev.miningprofit.commissions.CommissionsTracker.writeState())
        dev.miningprofit.support.SaveFile.write(sessionsFile, o.toString())
    }

    private fun writeSession(s: Session): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
        add("counts", com.google.gson.JsonObject().apply {
            for ((k, c) in s.counts) add(k.item, com.google.gson.JsonArray().apply { add(c.blocks); add(c.drops); add(c.compact); add(c.byYou) })
        })
        add("pristine", com.google.gson.JsonObject().apply { for ((k, v) in s.pristine) addProperty(k, v) })
        addProperty("xp", s.miningXp)
        // (the clock as time run, picked up again paused)
        addProperty("ms", s.activeMs)
        add("loot", com.google.gson.JsonArray().apply {
            for (b in s.loot.all) add(com.google.gson.JsonObject().apply {
                addProperty("count", b.count)
                add("items", com.google.gson.JsonObject().apply { for ((k, v) in b.items) addProperty(k, v) })
                add("keys", com.google.gson.JsonObject().apply { for ((k, v) in b.keys) addProperty(k, v) })
            })
        })
        add("pets", com.google.gson.JsonObject().apply { for ((k, v) in s.petXp) add(k, com.google.gson.JsonArray().apply { add(v); add(s.petRate[k] ?: 0.0) }) })
        add("gaps", com.google.gson.JsonArray().apply { s.gaps.forEach { add(it) } })
        addProperty("gemsByYou", s.gemsByYou)
    }

    private fun readSession(key: String, o: com.google.gson.JsonObject): Session {
        val s = Session(key)
        o.getAsJsonObject("counts")?.entrySet()?.forEach { (item, v) ->
            val kind = KINDS.values.firstOrNull { it.item == item } ?: return@forEach
            val a = v.asJsonArray
            s.counts[kind] = Count(a[0].asInt, a[1].asDouble, a[2].asInt, 0.0, a[3].asInt)
        }
        o.getAsJsonObject("pristine")?.entrySet()?.forEach { (k, v) -> s.pristine[k] = v.asDouble }
        s.miningXp = o.get("xp")?.asDouble ?: 0.0
        val ms = o.get("ms")?.asLong ?: 0L
        if (ms > 0) {
            val now = System.currentTimeMillis()
            s.startTime = now - ms
            s.paused = true
            s.pauseStart = now
        }
        o.getAsJsonArray("loot")?.forEachIndexed { i, e ->
            val b = s.loot.all.getOrNull(i) ?: return@forEachIndexed
            val bo = e.asJsonObject
            b.count = bo.get("count")?.asInt ?: 0
            bo.getAsJsonObject("items")?.entrySet()?.forEach { (k, v) -> b.items[k] = v.asLong }
            bo.getAsJsonObject("keys")?.entrySet()?.forEach { (k, v) -> b.keys[k] = v.asInt }
        }
        o.getAsJsonObject("pets")?.entrySet()?.forEach { (k, v) -> val a = v.asJsonArray; s.petXp[k] = a[0].asDouble; s.petRate[k] = a[1].asDouble }
        o.getAsJsonArray("gaps")?.forEach { s.gaps.addLast(it.asInt) }
        s.gemsByYou = o.get("gemsByYou")?.asInt ?: 0
        return s
    }

    // The tab list's Mining Fortune in Maniac Miner, each time it moves: in the log, with what's been counted
    private fun maniacTab() {
        var t = -1
        for (line in Tab.lines) {
            val m = FORTUNE.find(line) ?: continue
            if (m.groupValues[1] != "Mining") continue
            t = m.groupValues[2].replace(",", "").toDoubleOrNull()?.toInt() ?: continue
            break
        }
        if (t < 0 || t == maniacTab) return
        maniacTab = t
        log.info("[MP profit] Maniac Miner +${"%.1f".format((System.currentTimeMillis() - maniacFrom) / 1000.0)}s: tab list Mining Fortune $t, " +
            "counted +${"%.0f".format(maniacBonus)} (fortune ${miningNow(System.currentTimeMillis())} without it) from " +
            maniacBlocks.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "nothing yet" })
    }

    // What a block adds to Maniac Miner's fortune now: by the drill in your hand, and the mayor
    private fun maniacStep(now: Long): Double {
        // (looked at again at once when what's in your hand is another item: drills are swapped mid-ability)
        val inHand = Minecraft.getInstance().player?.mainHandItem
        if (now - heldAt >= HELD_MS || inHand !== heldStack) {
            heldAt = now
            heldStack = inHand
            heldBlegg = dev.miningprofit.support.ManiacMiner.blueCheese(inHand)
        }
        // (Blue Cheese's more only with the drill it was used with still in hand)
        return (if (heldBlegg && held(now) == procKey) MANIAC_BLEGG else MANIAC_STEP) * fiestaTimes
    }

    // The blocks a Fossil is made of
    private val FOSSILS = setOf(Blocks.QUARTZ_BLOCK, Blocks.QUARTZ_SLAB, Blocks.QUARTZ_STAIRS)

    /** What one [id] sells for on the bazaar: instantly, or with a sell offer (the setting); null until prices are in. */
    fun sell(id: String) = Bazaar.price(id)?.let { if (Config.profitSellOffer) it.sellOffer else it.instaSell }

    // Rough gems in each tier (Flawed, Fine, Flawless), and the tier below each
    private val GEM_TIERS = doubleArrayOf(80.0, 6_400.0, 512_000.0)
    private val GEM_NAMES = arrayOf("FLAWED", "FINE", "FLAWLESS")
    private val GEM_BELOW = arrayOf("ROUGH", "FLAWED", "FINE")

    // What one gem of the chosen tier ([Config.profitGemTier]) sells for: that tier's, or 80 of the one below if that's
    // worth more
    private fun gemUnit(gem: String): Double? {
        val tier = Config.profitGemTier.coerceIn(0, 2)
        val top = sell("${GEM_NAMES[tier]}_${gem}_GEM")
        val below = sell("${GEM_BELOW[tier]}_${gem}_GEM")?.times(80.0)
        return listOfNotNull(top, below).maxOrNull()
    }

    /**
     * What [c]'s drops of [k] come to: gemstones as Flawed, Fine or Flawless (the setting), metals as enchanted or as
     * they drop (the setting; Compact's procs are an enchanted item each either way), the rest enchanted. Null while a
     * price it needs isn't in.
     */
    private fun valueOf(k: Kind, c: Count): Double? = when {
        k.gem -> gemUnit(k.name.uppercase())?.let { c.drops / GEM_TIERS[Config.profitGemTier.coerceIn(0, 2)] * it }
        k.metal && Config.profitMetalTier == 1 && k.raw != null -> sell(k.raw)?.let { (c.drops + c.compact * k.per) * it }
        else -> sell(k.item)?.let { (c.drops / k.per + c.compact) * it }
    }

    // Pristine: Flawed (or finer) gemstones found on top of the drops, by gem (name -> rough gems they come to)
    private val pristine get() = cur.pristine
    // (Pristine only ever gives Flawed ones)
    private val PRISTINE = Regex("""^PRISTINE! You found\s+(?:\S\s+)?Flawed (\w+) Gemstone x([\d,]+)!""")

    /** What the pristine gemstones come to at the chosen tier; false in the second place while a price isn't in. */
    private fun pristineCoins(): Pair<Double, Boolean> {
        var coins = 0.0
        var ready = true
        for ((gem, rough) in pristine) {
            val unit = gemUnit(gem.uppercase())
            if (unit == null) ready = false else coins += rough / GEM_TIERS[Config.profitGemTier.coerceIn(0, 2)] * unit
        }
        return coins to ready
    }

    // --- the overlay

    private var cached: List<String> = emptyList()
    private var builtAt = 0L
    private const val TITLE = "§b§lMining Profit Tracker"

    private fun short(v: Double): String {
        val a = Math.abs(v)
        val s = when {
            a >= 1e9 -> "%.2fB".format(a / 1e9)
            a >= 1e6 -> "%.2fM".format(a / 1e6)
            a >= 1e3 -> "%.1fk".format(a / 1e3)
            else -> "%.0f".format(a)
        }
        return if (v < 0) "-$s" else s
    }

    // In thousands, whole: 12k (under a thousand as it is)
    private fun thousands(v: Double) = if (v < 1000) "%.0f".format(v) else "${Math.round(v / 1000)}k"

    /** A line of an overlay: [id] names it for hiding (none: the title and notes, always shown), [more] shows hovered. */
    class Row(val id: String?, val text: String, val more: List<String>? = null)

    /** The overlay being shown while moving them, in place of where you are's (null: where you are's). */
    var previewKey: String? = null
    /** Which overlay's showing: the one being previewed, or where you are's. */
    fun shownKey(): String? = previewKey ?: placeKey()

    // Every line it has, hidden ones too (for the screen that moves it)
    var rows: List<Row> = emptyList()
        private set

    private val TITLES = mapOf(HOLLOWS to "§b§lCoal Profit", GEMS to "§d§lGem Profit", BASE_CAMP to "§b§lBase Camp Profit",
        THE_END to "§5§lEnd Profit")
    /** The lines each overlay can show, in order (by id). */
    private val LINES: Map<String, List<String>> = mapOf(
        HOLLOWS to listOf("rate", "mined", "fortune", "profit", "pets", "drops", "xp", "time"),
        GEMS to listOf("profit", "efficiency", "collection", "time"),
        BASE_CAMP to listOf("profit", "pets", "drops", "xp", "time"),
        THE_END to listOf("rate", "fortune", "profit", "pets", "drops", "time", "mined"),
    )
    fun hidden(key: String, id: String) = "$key.$id" in Config.hiddenLines

    // The lines drawn, in order (with a screen open: hidden ones too, struck through), and whether it was built so
    private var shownRows: List<Row> = emptyList()
    private var builtOpen = false

    fun build(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val open = dev.miningprofit.gui.RatesOverlay.screenOpen()
        if (!force && now - builtAt < 250L && open == builtOpen) return
        builtAt = now
        builtOpen = open
        val key = shownKey()
        val preview = previewKey != null || force
        rows = if (key == null || !Config.profitTracker) emptyList() else rowsFor(key, preview)
        val visible = rows.filter { it.id == null || !hidden(key!!, it.id) }
        // (every line hidden: nothing, rather than a title alone - but with a screen open, all of them, to click back on)
        val shown = if (open && !preview) rows else if (!preview && visible.none { it.id != null }) emptyList() else visible
        val more = HashMap<Int, List<String>>()
        shown.forEachIndexed { i, r -> if (r.id == null || !hidden(key!!, r.id)) r.more?.let { more[i] = it } }
        shownRows = shown
        cached = shown.map { if (it.id != null && hidden(key!!, it.id)) dev.miningprofit.gui.RatesOverlay.struck(it.text) else it.text }
        detail = more
    }

    /** A click at [mx], [my] with a screen open: on a line that can be hidden, hides it (or shows it again). Whether it was. */
    fun click(mx: Double, my: Double): Boolean {
        if (!Config.profitTracker || cached.isEmpty()) return false
        val key = shownKey() ?: return false
        val font = Minecraft.getInstance().font
        val x = Config.profitX
        val y = Config.profitY
        if (my < y || mx < x || mx > x + width()) return false
        val id = shownRows.getOrNull(((my - y) / font.lineHeight).toInt())?.id ?: return false
        val k = "$key.$id"
        if (!Config.hiddenLines.remove(k)) Config.hiddenLines.add(k)
        Config.save()
        build(force = true)
        return true
    }

    private fun rowsFor(key: String, always: Boolean): List<Row> {
        val s = sessions[key]
        val title = Row(null, TITLES[key] ?: TITLE)
        if (s == null || !s.any()) return if (always) listOf(title, Row(null, "§7Nothing yet")) else emptyList()
        val was = cur
        cur = s
        try {
            return rowsOf(s, title)
        } finally {
            cur = was
        }
    }

    private fun rowsOf(s: Session, title: Row): List<Row> {
        val key = s.key
        val ms = s.activeMs
        val by = HashMap<String, Row>()
        // What the blocks' drops come to, and the island's own collection (coal, obsidian in the End)
        var drops = 0.0
        var priced = true
        val target = if (key == THE_END) "ENCHANTED_OBSIDIAN" else "ENCHANTED_COAL"
        val name = if (key == THE_END) "Obsidian" else "Coal"
        var collected = 0.0
        var collectedBlocks = 0
        var gemRough = 0.0
        for ((k, c) in s.counts) {
            val v = valueOf(k, c)
            if (v == null) priced = false else drops += v
            if (k.gem) gemRough += c.drops
            if (k.item == target) {
                collected += c.drops + c.compact * k.per
                collectedBlocks += c.blocks
            }
        }
        // Pristine gemstones are drops on top of the blocks'
        val (pristineValue, pristineReady) = pristineCoins()
        if (!pristineReady) priced = false
        drops += pristineValue
        // What comes in the chat (see LootTracker) is drops too: corpses, fossils, shards, and a Littlefoot's other drops
        val l = s.loot
        val corpse = LootTracker.value(l.corpses)
        val fossil = LootTracker.value(l.fossils)
        val shard = LootTracker.value(l.shards)
        val foot = LootTracker.value(l.littlefoot)
        if (corpse.loading || fossil.loading || shard.loading || foot.loading) priced = false
        val allDrops = drops + corpse.coins + fossil.coins + shard.coins + foot.coins
        val pets = petCoins(s)
        val total = allDrops + pets
        // (every per hour over the session's own clock, the commission timer's no part of it)
        val hours = ms / 3_600_000.0
        fun perHr(v: Double) = if (hours > 0.005) " §7(§e${short(v / hours)}/hr§7)" else ""

        // The collection: an hour (in millions to one decimal, or exact: the setting), or (coal) broken a second
        val hr = if (hours <= 0.005) "-"
            else if (Config.profitExact) "%,d".format(java.util.Locale.US, Math.round(collected / hours)) + "/hr"
            else "%.1f".format(collected / hours / 1e6) + "m/hr"
        // (the Hollows': coal blocks broken a second beside it)
        val perSec = if (key != HOLLOWS) "" else " §7(§e" + (if (ms < 1000L) "-" else "%.1f".format(collectedBlocks * 1000.0 / ms)) + "/s§7)"
        by["rate"] = Row("rate", "§b✦ §7$name: §a$hr$perSec")
        by["mined"] = Row("mined", "§b✦ §7" + (if (key == THE_END) "Obsidian Gained" else "Coal Mined") + ": §a" + short(collected))
        val bonus = if (maniac) " §d+${Math.round(maniacBonus)} Maniac" else ""
        by["fortune"] = Row("fortune", "§6✦ §7Fortune: §6☘${miningNow(System.currentTimeMillis())} §8| " +
            (if (key == THE_END) "§7Block §6$blockF" else "§7Ore §6$oreF") + bonus)
        by["profit"] = Row("profit", "§a✦ §7Profit: §a${short(total)}" + perHr(total))

        // Drops: and (hovered) where they came from
        val dropRows = ArrayList<String>()
        dropRows.add("§6§lDrops")
        dropRows.add("§7Blocks: §a${short(drops - pristineValue)}")
        if (s.pristine.isNotEmpty()) dropRows.add("§dPristine: §a${short(pristineValue)}")
        if (!l.corpses.isEmpty) {
            dropRows.add("§9Corpses (${l.corpses.count}): §a${short(corpse.coins)}")
            LootTracker.itemLines(l.corpses).forEach { dropRows.add("  $it") }
        }
        if (!l.fossils.isEmpty) dropRows.add("§eFossils (${l.fossils.count} blocks, ~${l.fossils.items.values.sum()} essence): §a${short(fossil.coins)}")
        if (!l.shards.isEmpty) {
            dropRows.add("§bShards (${l.shards.items.values.sum()}): §a${short(shard.coins)}")
            LootTracker.itemLines(l.shards).forEach { dropRows.add("  $it") }
        }
        if (!l.littlefoot.isEmpty) {
            dropRows.add("§3Littlefoot (${l.littlefoot.count}, besides the shards): §a${short(foot.coins)}")
            LootTracker.itemLines(l.littlefoot).forEach { dropRows.add("  $it") }
        }
        by["drops"] = Row("drops", "§6✦ §7Drops Profit: §a${short(allDrops)}" + perHr(allDrops), dropRows)

        // Pets: what their XP here is worth, and (hovered) pet by pet
        val petRows = ArrayList<String>()
        petRows.add("§5§lPets")
        val made = s.petXp.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        if (made.isEmpty()) petRows.add("§7No pet has made XP here yet")
        for ((k, xp) in made) {
            val parts = k.split('|')
            val coins = xp * petRateOf(s, k)
            val xpHr = if (hours > 0.005) " §7(§d${short(xp / hours)}/hr§7)" else ""
            petRows.add("§f${parts[0]} §8${parts.getOrElse(1) { "" }.lowercase()}§7: §d${short(xp)} XP$xpHr §7= §a${short(coins)}" + perHr(coins))
        }
        petRows.add("§8Taxes taken off")
        by["pets"] = Row("pets", "§5✦ §7Pet Profit: §a${short(pets)}" + perHr(pets), petRows)

        // Mining skill XP an hour (SkyBlock's own total off the action bar, not a pet's)
        by["xp"] = Row("xp", "§d✦ §7Mining Skill XP: §e" + if (hours > 0.005) "${short(s.miningXp / hours)}/hr" else "-")

        // Gems: how close to never waiting, and the gems they come to (as rough), pristine's too
        by["efficiency"] = Row("efficiency", "§d✦ §7Efficiency: §a" + (gemEfficiency(s)?.let { "%.0f%%".format(it * 100) } ?: "-"))
        by["collection"] = Row("collection", "§b✦ §7Collection: §a${short(gemRough + s.pristine.values.sum())} §7rough")

        val clock = when (key) { GEMS -> "Uptime"; BASE_CAMP -> "Session"; else -> "Time" }
        by["time"] = Row("time", "§3✦ §7$clock: §b" + dev.miningprofit.support.StringUtils.formatTime(ms / 1000L) +
            if (s.paused) " §c§l(PAUSED)" else "")

        val out = arrayListOf(title)
        if (!priced) out.add(Row(null, "§8(prices loading...)"))
        for (id in LINES[key] ?: emptyList()) by[id]?.let { out.add(it) }
        return out
    }

    // The lines that show more when hovered, by line
    private var detail: Map<Int, List<String>> = emptyMap()

    /** [top]: drawn over the open screen (see RatesOverlay.onTop); the HUD's own drawing leaves it to that then. */
    fun render(g: GuiGraphicsExtractor, top: Boolean = false) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || !Config.profitTracker || mc.debugOverlay.showDebugScreen()) return
        if (!top && dev.miningprofit.gui.RatesOverlay.screenOpen()) return
        // Being moved: that screen draws it
        if (mc.screen is dev.miningprofit.gui.OverlayMover) return
        build()
        if (cached.isEmpty()) return
        draw(g, dragging?.first ?: Config.profitX, dragging?.second ?: Config.profitY)
    }

    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        var ly = y
        for (line in cached) {
            g.text(font, line, x, ly, 0xFFFFFFFF.toInt(), true)
            ly += font.lineHeight
        }
        hover(g, x, y)
    }

    // A line's detail, beside it, while the mouse is on it (there's a mouse with a screen open, chat say)
    private fun hover(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val mc = Minecraft.getInstance()
        if (mc.screen == null || mc.screen is dev.miningprofit.gui.OverlayMover) return
        val font = mc.font
        val mx = mc.mouseHandler.getScaledXPos(mc.window)
        val my = mc.mouseHandler.getScaledYPos(mc.window)
        if (my < y) return
        val row = ((my - y) / font.lineHeight).toInt()
        val rows = detail[row] ?: return
        val line = cached.getOrNull(row) ?: return
        if (mx < x || mx > x + font.width(line)) return
        val w = rows.maxOf { font.width(it) } + 8
        val h = rows.size * font.lineHeight + 6
        val top = y + row * font.lineHeight
        val bx = x + width() + 8
        g.fill(bx, top - 3, bx + w, top - 3 + h, dev.miningprofit.gui.Theme.PANEL)
        g.fill(bx, top - 3, bx + w, top - 2, dev.miningprofit.gui.Theme.ACCENT)
        var ly = top
        for (l in rows) {
            g.text(font, l, bx + 4, ly, 0xFFFFFFFF.toInt(), true)
            ly += font.lineHeight
        }
    }

    fun width(): Int = cached.maxOfOrNull { Minecraft.getInstance().font.width(it) } ?: 0
    fun height(): Int = cached.size * Minecraft.getInstance().font.lineHeight
}
