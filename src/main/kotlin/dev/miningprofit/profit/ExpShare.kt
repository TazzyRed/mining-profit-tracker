package dev.miningprofit.profit

import dev.miningprofit.support.SaveFile
import dev.miningprofit.support.Config
import dev.miningprofit.support.DataDir
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * What the pets are making from your Mining XP: the pet you have out, and the ones in your Exp Sharing
 * slots (which level whatever pet you have out - a max one too).
 *
 *  - The pet you have out is read off the tab list's XP bar (see [observeTab]): what it made is the
 *    difference between its readings. Between them it's worked out from your Mining XP times 1 (a mining pet)
 *    or a third (any other), times 1 + Taming level / 100, times its held item's boost - and put right
 *    by the next reading.
 *  - Each pet you're sharing with gets what the pet out made, times its share rate (what its "Exp Share Slot"
 *    in the Exp Sharing menu says it's "gaining 37% of your equipped pet's Exp": that takes in the Exp Share
 *    item, attributes and the rest; [DEFAULT_SHARE] till it's been seen), times 1 or a third by its own type.
 *    A reading of the pet out puts what was worked out for them from it right: it's the XP the pet really
 *    made that they share in. (A max level pet has no bar to read, so for it the sum from your Mining XP
 *    stands.)
 *
 * Which pet is out comes from the chat (an autopet rule, a pet summoned or despawned: it's read the
 * moment it happens, so a pet that's only out for the seconds a commission takes to claim or a Pickobulus
 * to be thrown is the one that gets those XP), the Pets menu, and the tab list's Pet widget (a few seconds
 * behind, so it only decides when the chat hasn't). A pet's type ("Mining Pet", "Farming Pet") is read off
 * its tooltip in those menus and kept; only a pet never seen in one goes by its name.
 *
 * What the sum needs is picked up as you go, never asked for: the pets and the share rate off the Exp
 * Sharing menu; your Taming level off the Skills menu, then its level-up messages; the item a pet holds off
 * the Pets menu. The sum is only ever the part since the last time a pet's XP was known exactly - the tab
 * list's bar, a level-up message ("Your Endermite leveled up to level 82!"), the menus - and each of those
 * turns the stretch before it into what it really was. So what a pet has made this session is those
 * stretches plus the sum since the last, wherever the XP came from (a claim's reward, say, which the action
 * bar doesn't show).
 *
 * Several of the same pet can be levelling at once, each at its own level: a message goes to the one that
 * was a level under it (two at the same level take one message each), else to the pet you have out, else to
 * the nearest one under it that could have jumped that far (low levels go several at a time), else it's a
 * pet not seen before. Kept in config/miningprofit/expshare.json.
 */
object ExpShare {

    class Pet(val name: String, val offset: Int, val maxLevel: Int) {
        /** Its level (0: not known yet - it makes no XP until it is). */
        var level = 0
        // XP from level 1 when last known, and what the sum says it's made since (of which [direct] is what it made
        // as the pet out, the rest what it was given as one sharing)
        var base = 0.0
        var since = 0.0
        var direct = 0.0
        /** Whether it's been out since its XP was last known (what it made then is what the pets sharing with it get a share of). */
        var wasOut = false
        /**
         * XP it made below a level-up the chat's just told of (already counted when the level-up was): the sum for the
         * same burst of Mining XP that comes after the message mustn't count it again.
         */
        var carry = 0.0
        var carryAt = 0L
        /** What it gets of the pet out's XP, as its slot in the Exp Sharing menu says (under 0: not read - [DEFAULT_SHARE]). */
        var rate = -1.0
        /** Whether [base] is where it really was (a level-up, a menu, the tab list) and not a level's start taken for it. */
        var exact = false
        var rarity = "LEGENDARY"
        /** "Mining", "Farming"...: from its tooltip (null: not seen in one). */
        var type: String? = null
        /**
         * Whether it's getting XP: the first slot's is, the other two's only with their slots unlocked - so
         * once they've been seen to gain any (a level-up, or more XP in the menu than last time).
         */
        var active = true
        /** What it's made in the stretches between times its XP was known (this session, less what a reset took off). */
        var done = 0.0
        /** When the sum last gave it any XP. */
        var lastXp = 0L
        /** How many times its XP's been read off the tab list this session, and when it last was. */
        var reads = 0
        var readAt = 0L
        var changes = 0

        val maxed: Boolean get() = level >= maxLevel

        /** XP it has made this session: the stretches before each time it was known, and the sum since. */
        val session: Double get() = done + since

        /** XP left to max level (it hasn't reached its next level yet, whatever the sum says). */
        val left: Double get() {
            val now = (base + since).coerceAtMost(PetTracker.upTo(level + 1, offset) - 1.0).coerceAtLeast(base)
            return (PetTracker.upTo(maxLevel, offset) - now).coerceAtLeast(0.0)
        }
    }

    // As many as there are Exp Sharing slots: one more, and the one that's gone longest without levelling goes
    private const val MAX_PETS = 3
    // Pets seen out that aren't sharing: this many kept
    private const val MAX_OTHERS = 12
    // Under this level a pet can go up several levels at once
    private const val JUMPY_BELOW = 50
    private const val SAVE_MS = 60_000L
    // What a pet sharing gets of the XP the pet out made, until the Exp Sharing menu says otherwise
    private const val DEFAULT_SHARE = 0.37
    // A level-up message comes in the middle of a burst of XP (a Pickobulus): the sum for the rest of it is for this long
    private const val CARRY_MS = 2_500L
    // The tab list's XP bar is this far behind what a pet's made: XP worked out for it within this isn't on the bar yet
    private const val BAR_LAG_MS = 8_000L
    // Until it's been read: most who share XP are about here
    private const val TAMING_GUESS = 50
    // What a pet that isn't a mining one makes of Mining XP
    private const val OTHER_TYPE = 1.0 / 3.0
    // The tab list runs this far behind the chat: within it of the chat saying which pet's out, the tab list isn't believed
    private const val TAB_LAG_MS = 8_000L
    // The pets that are Mining pets (the rest are another skill's: a Mammoth is a Combat pet, a Penguin a Fishing one)
    private val MINING_PETS = setOf("ROCK", "BAT", "MITHRIL_GOLEM", "WITHER_SKELETON", "SILVERFISH", "ENDERMITE", "ARMADILLO", "BAL",
        "SCATHA", "SNAIL", "GLACITE_GOLEM", "GOBLIN", "MOLE")

    private val LEVEL_UP = Regex("""^Your (.+) leveled up to level (\d+)!$""")
    private val SUMMON = Regex("""^You summoned your (.+?)!$""")
    private val DESPAWN = Regex("""^You despawned your (.+?)!$""")
    private val AUTOPET = Regex("""^Autopet equipped your \[Lvl (\d+)] (.+?)! ?(?:VIEW RULE.*)?$""")
    private val SKILL_UP = Regex("""SKILL LEVEL UP (Taming|Mining) \S+➜(\S+)""")
    private val LVL = Regex("""\[Lvl (\d+)]\s*(.+)""")
    private val PROGRESS = Regex("""Progress to Level \d+: ([\d.]+)%""")
    private val RARITY = Regex("""\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)\b""")
    private val PERCENT = Regex("""(\d+(?:\.\d+)?)%""")
    private val TAMING = Regex("""^(Taming|Mining) (\S+)$""")
    private val HELD = Regex("""Held Item: (.+)""")
    // The line under a pet's name in its tooltip: "Mining Pet", "Farming Pet"
    private val PET_TYPE = Regex("""^([A-Z][a-z]+) Pet$""")
    private val OFFSETS = mapOf("COMMON" to 0, "UNCOMMON" to 6, "RARE" to 11, "EPIC" to 16, "LEGENDARY" to 20, "MYTHIC" to 20)
    // A rarity is the colour a name's written in: the offset into the level table for each
    private val BY_COLOR = mapOf(0xFFFFFF to 0, 0x55FF55 to 6, 0x5555FF to 11, 0xAA00AA to 16, 0xFFAA00 to 20, 0xFF55FF to 20)
    private val COLOR = Regex("§.")
    private val log = org.slf4j.LoggerFactory.getLogger("MiningProfit")

    private val RARITY_BY_COLOR = mapOf(0xFFFFFF to "COMMON", 0x55FF55 to "UNCOMMON", 0x5555FF to "RARE", 0xAA00AA to "EPIC",
        0xFFAA00 to "LEGENDARY", 0xFF55FF to "MYTHIC")
    private const val FIRST_SLOT = 30

    // The pets in the Exp Sharing slots, the others seen out, and the ones that reached max level this session
    // (their XP still counts)
    private val list = ArrayList<Pet>()
    private val others = ArrayList<Pet>()
    private val retired = ArrayList<Pet>()
    /** The pets getting XP (see [Pet.active]). */
    val pets: List<Pet> get() = list.filter { it.active }

    /** The pet you have out, as far as it's known (none: not known, or none out). */
    var out: Pet? = null
        private set
    private var outEventAt = 0L
    private var levelUpAt = 0L

    // The pet out at a Pickobulus, what was worked out for it from the Mining XP that came with it, and what its XP bar showed
    // of it (for the log, to check the one against the other)
    private const val PICKO_MS = 12_000L
    private val PICKO_USED = Regex("""^You used your Pickobulus Pickaxe Ability!""")
    private val PICKO_DESTROYED = Regex("""^Your Pickobulus destroyed (\d+) blocks!""")
    private var pickoAt = 0L
    private var pickoPet: Pet? = null
    private var pickoEst = 0.0
    private var pickoBar = 0.0
    private var pickoBlocks = 0

    // What each kind of pet is ("MITHRIL_GOLEM" -> "Mining"), the boost of the item each holds, and the level each was
    // last seen at in a menu ("NAME|offset")
    private val types = HashMap<String, String>()
    private val boosts = HashMap<String, Double>()
    private val seen = HashMap<String, Int>()

    private fun key(name: String) = name.uppercase().replace(' ', '_')
    private fun seenKey(name: String, offset: Int) = "${key(name)}|$offset"

    // Rose, Golden and Jade Dragons (200 levels) with the setting on: levelled to 100 and sold, then bought
    // at 100 and levelled to 200. The XP a pet made is the stretch of XP up to where it is now, split at
    // level 100 (what it can't be placed in goes with the tier it's in now)
    private fun tiered(p: Pet) = Config.profitDragonTiers && p.maxLevel == 200

    /** What the XP [p] made this session is worth. */
    fun petValue(p: Pet): Double {
        val xp = p.session
        if (xp <= 0) return 0.0
        val prices = dev.miningprofit.support.PetPrices
        if (!tiered(p)) return xp * (prices.perXp(p.name, p.rarity, p.maxLevel, PetTracker.upTo(p.maxLevel, p.offset)) ?: 0.0)
        val t100 = PetTracker.upTo(100, p.offset)
        val t200 = PetTracker.upTo(200, p.offset)
        val rateLow = prices.perXp(p.name, p.rarity, 1, 100, t100) ?: 0.0
        val rateHigh = prices.perXp(p.name, p.rarity, 100, 200, t200 - t100) ?: 0.0
        val end = p.base + p.since
        val start = end - xp
        val low = (minOf(end, t100) - maxOf(start, 0.0)).coerceAtLeast(0.0)
        val high = (minOf(end, t200) - maxOf(start, t100)).coerceAtLeast(0.0)
        val rest = (xp - low - high).coerceAtLeast(0.0)
        return low * rateLow + high * rateHigh + rest * (if (p.level >= 100) rateHigh else rateLow)
    }

    /** Every pet known this run (their [Pet.session] XP is the run's, not an overlay's: see ProfitTracker). */
    fun allPets(): List<Pet> = (list + others + retired).distinct()

    /** A pet's XP this session and what it's worth. */
    class Made(val pet: Pet, val xp: Double, val coins: Double)

    /** Every pet that's made XP this session, the most valuable first. */
    fun made(): List<Made> = (list + others + retired).distinct().filter { it.session > 0 }
        .map { Made(it, it.session, petValue(it)) }.sortedByDescending { it.coins }

    /**
     * The pet whose levelling is followed: of those under max level, the one that's made the most this
     * session (the one you have out, before any have made any).
     */
    fun leveling(): Pet? =
        (listOfNotNull(out) + list.filter { it.active } + others).distinct().filter { !it.maxed && it.level > 0 }.maxByOrNull { it.session }

    /** Your Taming level (under 0: not read yet). */
    var taming = -1
        private set
    /** Your Mining level, the same way (what the action bar's "+73.9 Mining (17.65%)" is a percentage of). */
    var miningLevel = -1
        private set
    /** What [p] gets of the pet out's XP while it's in an Exp Share slot. */
    fun rateOf(p: Pet) = if (p.rate >= 0) p.rate else DEFAULT_SHARE
    private var dirty = false
    private var savedAt = 0L
    private var ticks = 0
    private val logged = HashSet<String>()

    @Volatile private var menu: AbstractContainerMenu? = null
    private var menuKind = 0 // 1 Exp Sharing, 2 Pets, 3 Skills, 4 Stats

    private val file get() = DataDir.file("expshare.json")

    // A mining pet makes all of it, any other a third: by what its tooltip said, else by its name
    private fun typeBoost(name: String): Double {
        val k = key(name)
        val type = types[k]
        return if (type != null) (if (type == "Mining") 1.0 else OTHER_TYPE) else if (k in MINING_PETS) 1.0 else OTHER_TYPE
    }

    private fun typeBoost(p: Pet): Double = p.type?.let { if (it == "Mining") 1.0 else OTHER_TYPE } ?: typeBoost(p.name)

    // Pet XP the pet you have out makes for each Mining XP (none out: none)
    private fun mainPerXp(): Double {
        val main = out ?: return 0.0
        val t = if (taming >= 0) taming else TAMING_GUESS
        return typeBoost(main) * (1 + t / 100.0) * (boosts[main.name] ?: 1.0)
    }

    fun onMiningXp(gained: Double) {
        if (gained <= 0) return
        val main = mainPerXp()
        if (main <= 0) return
        val now = System.currentTimeMillis()
        val outPet = out
        // The pet you have out: all of it (a pet that isn't known yet, or is max level, makes none)
        if (outPet != null && outPet.level > 0 && !outPet.maxed) {
            var xp = gained * main
            // (what a level-up message just counted of this burst isn't counted again)
            if (now - outPet.carryAt < CARRY_MS && outPet.carry > 0) {
                val skip = minOf(xp, outPet.carry)
                xp -= skip
                outPet.carry -= skip
            }
            outPet.since += xp
            outPet.direct += xp
            outPet.lastXp = now
            if (outPet === pickoPet && now - pickoAt < PICKO_MS) pickoEst += xp
        }
        // The ones sharing with it: their share of what it made (the one out gets no share on top of its own)
        for (p in list) {
            if (!p.active || p === outPet || p.maxed) continue
            p.since += gained * main * rateOf(p) * typeBoost(p)
            p.lastXp = now
        }
        dirty = true
    }

    // A pet known to be at [total] XP (from level 1) now: what the sum had it make since it was last known
    // becomes what it really made, if that was known exactly
    private fun fix(p: Pet, total: Double, level: Int) {
        val made = total - p.base
        if (p.exact && made >= 0) {
            if (p.wasOut) shareAdjust(p, made)
            p.done += made
        } else {
            p.done += p.since
        }
        p.base = total
        p.since = 0.0
        p.direct = 0.0
        p.exact = true
        p.level = level
        if (out !== p) p.wasOut = false
        if (made != 0.0) dirty = true
    }

    // [p], a pet that's been out, is known to have made [made] XP: it's of that the pets sharing with it get their
    // share. What was worked out for them while it was out (from [Pet.direct], its own sum) is put right
    private fun shareAdjust(p: Pet, made: Double) {
        // What it made as the pet out: what it made, less what it was given as one sharing
        val real = (made - (p.since - p.direct)).coerceAtLeast(0.0)
        shareExtra(p, real - p.direct, real)
    }

    // The pets sharing with [p] get [extra] more (or less, if it's under 0) XP of it to share in
    private fun shareExtra(p: Pet, extra: Double, real: Double) {
        if (Math.abs(extra) < 1.0) return
        for (q in list) {
            if (!q.active || q === p || q.maxed) continue
            q.since = (q.since + extra * rateOf(q) * typeBoost(q)).coerceAtLeast(0.0)
        }
        log.info("[MP pet] ${p.name} made ${"%,.0f".format(real)} as the pet out (worked out ${"%,.0f".format(p.direct)}): the pets sharing get ${if (extra >= 0) "+" else ""}${"%,.0f".format(extra)} to share in")
    }

    /**
     * [p]'s XP bar says it's at [total]. The bar is a few seconds behind, so a reading that hasn't changed says
     * nothing (the XP just worked out isn't on it yet), and one that has only gets to take off the part of what
     * was worked out that it shows - what was worked out in the last few seconds stays till the bar has it.
     */
    private fun fixBar(p: Pet, total: Double, level: Int, now: Long) {
        if (!p.exact) {
            // Nowhere known yet: this is where it is (what's been worked out is kept in the session)
            p.done += p.since
            p.base = total
            p.since = 0.0
            p.direct = 0.0
            p.exact = true
            p.level = level
            return
        }
        val made = total - p.base
        if (made < 1.0) return
        if (p === pickoPet && now - pickoAt < PICKO_MS) pickoBar += made
        val caughtUp = now - p.lastXp > BAR_LAG_MS
        val real = (made - (p.since - p.direct)).coerceAtLeast(0.0)
        val shown = real >= p.direct || caughtUp
        if (p.wasOut) shareExtra(p, if (shown) real - p.direct else 0.0, real)
        p.done += made
        p.base = total
        p.direct = if (shown) 0.0 else p.direct - real
        p.since = p.direct
        p.level = level
        if (out !== p && p.direct == 0.0) p.wasOut = false
        dirty = true
    }

    // --- which pet is out

    private fun find(name: String, offset: Int): Pet? =
        list.firstOrNull { it.name == name && it.offset == offset } ?: others.firstOrNull { it.name == name && it.offset == offset }

    /**
     * [name] is out now (of rarity [rarity], at [level] if that's known: 0 if not). Its level, if it's not the
     * one it was last seen at, is where it starts from again.
     */
    private fun petOut(name: String, offset: Int, rarity: String?, level: Int, fromChat: Boolean) {
        val now = System.currentTimeMillis()
        if (fromChat) outEventAt = now
        val max = maxLevelOf(name)
        val known = if (level > 0) level else seen[seenKey(name, offset)] ?: 0
        // (a max level pet isn't kept anywhere, but it's still the one that's out)
        var p = find(name, offset) ?: out?.takeIf { it.name == name && it.offset == offset }
        if (p == null) {
            p = Pet(name, offset, max)
            // A max level pet makes nothing: not kept
            if (known < max) {
                others.add(p)
                while (others.size > MAX_OTHERS) others.removeAt(0)
            }
        }
        if (rarity != null) p.rarity = rarity
        types[key(name)]?.let { if (p.type == null) p.type = it }
        // Not the level it was last at (it's been levelled somewhere unseen, or it's another of the same): its level's start
        setLevel(p, known)
        p.wasOut = true
        if (pickoAt > 0 && now - pickoAt < 4_000L) pickoPet = p
        if (out !== p) {
            out = p
            log.info("[MP pet] out: ${p.name} ${if (p.level > 0) "level ${p.level}" else "level not known"} ${p.rarity} (${p.type ?: "type not known"})")
        }
    }

    // [p] is at [level], not the level it was at: what's been worked out for it is kept, and it starts from there
    private fun setLevel(p: Pet, level: Int) {
        if (level <= 0 || level == p.level) return
        p.done += p.since
        p.level = level
        p.base = PetTracker.upTo(level, p.offset)
        p.since = 0.0
        p.direct = 0.0
        p.exact = false
    }

    private fun petAway() {
        outEventAt = System.currentTimeMillis()
        if (out != null) log.info("[MP pet] ${out?.name} despawned")
        out = null
    }

    /**
     * The tab list's Pet widget - the XP bar - read. [total] is XP from level 1. Whatever pet it names is at
     * that XP: that's taken as it is, so what the pet made since the last time it was seen is the difference
     * (a pet that's only out for a moment shows up whenever the bar next has it, and nothing's lost in
     * between: a pet only makes XP while it's out). The bar can be a few seconds behind, so the latest
     * reading can be short of what the pet's really at; the next makes it up.
     */
    fun observeTab(name: String, offset: Int, rarity: String?, level: Int, total: Double) {
        val now = System.currentTimeMillis()
        val o = out
        val same = o != null && o.name == name && o.offset == offset
        // Which pet's out: the chat's the more recent word on it, so the tab list only gets to say once it's had time to catch up
        if (same || now - outEventAt >= TAB_LAG_MS) {
            // (still showing the level before a level-up: not that)
            if (!(same && o != null && o.level > level && now - levelUpAt < TAB_LAG_MS)) petOut(name, offset, rarity, level, fromChat = false)
        }
        val p = find(name, offset) ?: o?.takeIf { same } ?: return
        if (p.maxed) return
        // Behind a level-up that's been heard of: the old level's last reading, not this pet's XP now
        if (level < p.level && now - levelUpAt < TAB_LAG_MS) return
        setLevel(p, level)
        // A reading that's behind where the pet's known to be isn't it (another of the same, or an old one)
        if (p.exact && total + 1 < p.base) return
        val before = p.session
        val was = p.base
        fixBar(p, total, level, now)
        p.reads++
        p.readAt = now
        // (the changes of it, a few of them: its readings come every fifth of a second)
        if (p.base != was && p.changes++ < 30) {
            log.info("[MP pet] tab: ${p.name} $level at ${"%,.0f".format(total)} XP (+${"%,.0f".format(total - was)}), session ${"%,.0f".format(before)} -> ${"%,.0f".format(p.session)}")
        }
    }

    /** The tab list's Pet widget says there's no pet. */
    fun observeNoPet() {
        if (System.currentTimeMillis() - outEventAt >= TAB_LAG_MS && out != null) petAway()
    }

    // --- menus

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            val title = screen.title.string
            val kind = when {
                title.contains("Exp Shar", ignoreCase = true) -> 1
                title.contains("Pets") -> 2
                title.contains("Your Skills") -> 3
                title.contains("Stats") -> 4
                else -> return@register
            }
            menu = screen.menu
            menuKind = kind
            ScreenEvents.remove(screen).register { if (menu === screen.menu) menu = null }
        }
    }

    fun onClientTick() {
        if (pickoAt > 0 && System.currentTimeMillis() - pickoAt >= PICKO_MS) {
            val pet = pickoPet
            log.info("[MP pet] Pickobulus ($pickoBlocks blocks): pet out ${pet?.name ?: "none known"} - worked out ${"%,.0f".format(pickoEst)} XP for it, its bar showed +${"%,.0f".format(pickoBar)}")
            pickoAt = 0L
        }
        if (dirty && System.currentTimeMillis() - savedAt >= SAVE_MS) save()
        val m = menu ?: return
        if (++ticks % 10 == 0) scan(m, menuKind)
    }

    // A menu's own slots (not your inventory's), each item's tooltip
    private fun scan(m: AbstractContainerMenu, kind: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val own = m.slots.size - 36
        if (own <= 0) return
        val items = ArrayList<Pair<Int, List<Component>>>()
        for (i in 0 until own) {
            val stack = m.slots[i].item
            if (stack.isEmpty) continue
            try {
                items.add(i to stack.getTooltipLines(Item.TooltipContext.of(level.registryAccess()), player, net.minecraft.world.item.TooltipFlag.NORMAL))
            } catch (_: Throwable) {
            }
        }
        // Still empty: its contents haven't come yet
        if (items.isEmpty()) return
        when (kind) {
            1 -> readSharing(items)
            2 -> readPets(items)
            3 -> readSkills(items)
            4 -> ProfitTracker.onStatsMenu(items.flatMap { plain(it.second) })
        }
    }

    private fun plain(lines: List<Component>) = lines.map { it.string.replace(COLOR, "") }

    // "Mining Pet" under a pet's name
    private fun typeOf(lines: List<String>): String? =
        lines.take(6).firstNotNullOfOrNull { PET_TYPE.find(it.trim())?.groupValues?.get(1) }

    private fun learnType(name: String, type: String?) {
        type ?: return
        if (types.put(key(name), type) == type) return
        log.info("[MP pet] $name is a $type pet")
        for (p in list + others) if (key(p.name) == key(name)) p.type = type
        dirty = true
    }

    // The Exp Sharing menu: the pets in it are the ones (and the only ones) you're sharing with
    private fun readSharing(items: List<Pair<Int, List<Component>>>) {
        val found = ArrayList<Pet>()
        val slots = ArrayList<Pair<Int, Pet>>()
        val markers = ArrayList<Marker>()
        val before = ArrayList(list)
        for ((slot, tip) in items) {
            val lines = plain(tip)
            val head = lines.firstNotNullOfOrNull { LVL.find(it) }
            if (head == null) {
                // Not a pet: the menu's own items - a slot's says what it's gaining
                if (lines.isNotEmpty() && logged.add("menu $slot ${lines.first()}")) log.info("[MP expshare] menu item $slot: " + lines.joinToString(" | "))
                markerOf(slot, lines)?.let { markers.add(it) }
                continue
            }
            val lvl = head.groupValues[1].toInt()
            val name = head.groupValues[2].trim()
            val rarity = lines.asReversed().firstNotNullOfOrNull { RARITY.find(it) }?.groupValues?.get(1) ?: "LEGENDARY"
            val offset = OFFSETS[rarity] ?: 20
            val max = maxLevelOf(name)
            val pct = lines.firstNotNullOfOrNull { PROGRESS.find(it) }?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val type = typeOf(lines)
            learnType(name, type)
            seen[seenKey(name, offset)] = lvl
            // What it read, once a pet a session (to check the menu's wording against)
            if (logged.add("$slot $name $lvl")) log.info("[MP expshare] slot $slot: $name $lvl ${"%.1f".format(pct)}% (offset $offset): " + lines.joinToString(" | "))
            if (lvl >= max || lines.any { "MAX LEVEL" in it }) continue
            val total = PetTracker.upTo(lvl, offset) + pct / 100.0 * PetTracker.need(lvl, offset)
            // The same pet as before (one of them, if there are several alike): checked against where it was -
            // in the list, or one seen out that's joined it
            val old = before.filter { it.name == name && it.offset == offset && it.level <= lvl && it.base <= total + 1 }.maxByOrNull { it.base }
                ?: others.filter { it.name == name && it.offset == offset && it.level <= lvl && it.base <= total + 1 }.maxByOrNull { it.base }
            if (old != null) {
                before.remove(old)
                others.remove(old)
            }
            val pet = old ?: Pet(name, offset, max).also { it.active = slot == FIRST_SLOT }
            if (old != null) fix(pet, total, lvl) else {
                pet.level = lvl
                pet.base = total
                pet.since = 0.0
                pet.exact = true
            }
            // The first slot's always gets XP; another does once it's been seen to
            if (slot == FIRST_SLOT || (old != null && total > old.base + 1)) pet.active = true
            pet.rarity = rarity
            pet.type = type ?: pet.type
            found.add(pet)
            slots.add(slot to pet)
        }
        // What each slot says its pet is gaining (the slot's item is over its pet's column, or the slots go in order)
        for (mk in markers) {
            val pet = slots.firstOrNull { it.first % 9 == mk.index % 9 }?.second ?: slots.sortedBy { it.first }.getOrNull(mk.no - 1)?.second ?: continue
            mk.rate?.let {
                if (Math.abs(pet.rate - it) > 1e-9) {
                    pet.rate = it
                    log.info("[MP expshare] ${pet.name}: slot #${mk.no} gets ${"%.1f".format(it * 100)}% of the pet out's XP")
                    dirty = true
                }
            }
            mk.active?.let { pet.active = it }
        }
        if (found.map { Triple(it.name, it.level, it.active) } != list.map { Triple(it.name, it.level, it.active) }) dirty = true
        // A pet taken out of the slots keeps what it made, and is still followed (it's still a pet that may be out)
        for (p in list) {
            if (p in found) continue
            if (p.done != 0.0 || p.since > 0) retired.add(p)
            if (!p.maxed && p.level > 0 && p !in others) others.add(p)
        }
        list.clear()
        list.addAll(found)
        if (dirty) save()
    }

    // An item of the Exp Sharing menu that says what a slot is doing: "Exp Share Slot #1", "This pet is currently gaining
    // 37% of your equipped pet's Exp, boosted by its Exp Share item!", "ACTIVE"
    private class Marker(val index: Int, val no: Int, val rate: Double?, val active: Boolean?)

    private val SLOT_NAME = Regex("""Exp Share Slot #(\d+)""")
    private val GAINING = Regex("""gaining (\d+(?:\.\d+)?)%""")

    private fun markerOf(slot: Int, lines: List<String>): Marker? {
        val no = lines.firstNotNullOfOrNull { SLOT_NAME.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val rate = lines.firstNotNullOfOrNull { GAINING.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.div(100.0)?.takeIf { it in 0.0..1.0 }
        val active = when {
            lines.any { it.contains("INACTIVE", ignoreCase = true) || it.contains("LOCKED", ignoreCase = true) } -> false
            lines.any { it.trim().equals("ACTIVE", ignoreCase = true) } -> true
            else -> null
        }
        return Marker(slot, no, rate, active)
    }

    // The Pets menu: what each pet is (its type), where it is, what it's holding, and which is out
    private fun readPets(items: List<Pair<Int, List<Component>>>) {
        for ((_, tip) in items) {
            val lines = plain(tip)
            val head = lines.firstNotNullOfOrNull { LVL.find(it) } ?: continue
            val name = head.groupValues[2].trim().removeSuffix("✦").trim()
            val lvl = head.groupValues[1].toIntOrNull() ?: continue
            val rarity = lines.asReversed().firstNotNullOfOrNull { RARITY.find(it) }?.groupValues?.get(1) ?: "LEGENDARY"
            val offset = OFFSETS[rarity] ?: 20
            val isOut = lines.any { it.contains("Click to despawn", ignoreCase = true) }
            learnType(name, typeOf(lines))
            seen[seenKey(name, offset)] = lvl

            val at = lines.indexOfFirst { HELD.containsMatchIn(it) }
            val held = if (at >= 0) HELD.find(lines[at])!!.groupValues[1].trim() else ""
            val boost = when {
                held.contains("All Skills Exp Super-Boost", ignoreCase = true) -> 1.2
                held.contains("All Skills Exp Boost", ignoreCase = true) -> 1.1
                // By its rarity: the colour it's written in
                held.contains("Mining Exp Boost", ignoreCase = true) -> when (colorOf(tip[at], "Mining Exp Boost")?.let { BY_COLOR[it] }) {
                    0 -> 1.2
                    6 -> 1.3
                    11 -> 1.4
                    16 -> 1.5
                    else -> 1.3
                }
                else -> 1.0
            }
            // The one out, or a pet that's the same one as is known (several alike: the last one read would be anyone's)
            val known = find(name, offset)
            if (isOut || boosts[name] == null || known?.level == lvl) {
                if (boosts.put(name, boost) != boost) {
                    log.info("[MP expshare] $name holds '${held.ifEmpty { "nothing" }}': x$boost")
                    dirty = true
                }
            }
            if (lvl >= maxLevelOf(name) || lines.any { "MAX LEVEL" in it }) {
                if (isOut) petOut(name, offset, rarity, lvl, fromChat = true)
                continue
            }
            val pct = lines.firstNotNullOfOrNull { PROGRESS.find(it) }?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val total = PetTracker.upTo(lvl, offset) + pct / 100.0 * PetTracker.need(lvl, offset)
            if (isOut) petOut(name, offset, rarity, lvl, fromChat = true)
            // Where it is, for one that's known at this level (the pet out has been getting XP: its tab-list-like lag doesn't apply here)
            val p = find(name, offset)
            if (p != null && p.level == lvl && total + 1 >= p.base) fix(p, total, lvl)
        }
        save()
    }

    // The Skills menu: your Taming level
    private fun readSkills(items: List<Pair<Int, List<Component>>>) {
        for ((_, tip) in items) {
            val first = tip.firstOrNull()?.string?.replace(COLOR, "")?.trim() ?: continue
            val m = TAMING.find(first) ?: continue
            val level = number(m.groupValues[2]) ?: continue
            if (m.groupValues[1] == "Taming") setTaming(level) else setMining(level)
        }
    }

    private fun setMining(level: Int) {
        if (level == miningLevel || level !in 0..60) return
        miningLevel = level
        log.info("[MP expshare] Mining $level")
        save()
    }

    private fun setTaming(level: Int) {
        if (level == taming || level !in 0..60) return
        taming = level
        log.info("[MP expshare] Taming $level")
        save()
    }

    // "50" or "L"
    private fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)
        var total = 0
        for (i in s.indices) {
            val v = values[s[i]] ?: return null
            total += if (i + 1 < s.length && (values[s[i + 1]] ?: 0) > v) -v else v
        }
        return total.takeIf { it > 0 }
    }

    private fun maxLevelOf(name: String) =
        if (name.contains("Dragon") && (name.startsWith("Golden") || name.startsWith("Jade") || name.startsWith("Rose"))) 200 else 100

    // A pet's name as the chat has it: without a skin's mark
    private fun cleanName(raw: String) = raw.replace(Regex("""^\[\d+✦] """), "").removeSuffix("✦").trim()

    /** A chat message ([plain]: colour codes stripped): which pet's out, a pet's level-up, or Taming's. */
    fun onChat(message: Component, plain: String) {
        SKILL_UP.find(plain)?.let { m ->
            number(m.groupValues[2])?.let { if (m.groupValues[1] == "Taming") setTaming(it) else setMining(it) }
            return
        }
        val text = plain.trim()
        // A Pickobulus: the pet that's out gets the XP of its blocks
        if (PICKO_USED.containsMatchIn(text)) {
            pickoAt = System.currentTimeMillis()
            pickoPet = out
            pickoEst = 0.0
            pickoBar = 0.0
            pickoBlocks = 0
        }
        PICKO_DESTROYED.find(text)?.let { pickoBlocks = it.groupValues[1].toIntOrNull() ?: 0 }
        // Which pet's out
        AUTOPET.find(text)?.let { m ->
            val name = cleanName(m.groupValues[2])
            petOutFromChat(message, name, m.groupValues[1].toIntOrNull() ?: 0)
            return
        }
        SUMMON.find(text)?.let { m ->
            petOutFromChat(message, cleanName(m.groupValues[1]), 0)
            return
        }
        if (DESPAWN.containsMatchIn(text)) {
            petAway()
            return
        }

        val m = LEVEL_UP.find(text) ?: return
        levelUpAt = System.currentTimeMillis()
        val name = m.groupValues[1].trim()
        val level = m.groupValues[2].toIntOrNull() ?: return
        val offset = colorOf(message, name)?.let { BY_COLOR[it] } ?: 20
        val max = maxLevelOf(name)
        val reached = PetTracker.upTo(level, offset)

        val same = (list + others).filter { it.name == name && it.offset == offset && it.level < level }
        // The one a level under it (of two, the one nearer to levelling)
        var pet = same.filter { it.level == level - 1 }.maxByOrNull { it.base + it.since }
        // The pet you have out, if it's this one
        val o = out
        if (pet == null && o != null && o.name == name && o.offset == offset && o.level < level) pet = o
        // Or the nearest under it that could have come this far since it was last known
        if (pet == null) pet = same.maxByOrNull { it.level }?.takeIf { p ->
            p.level < JUMPY_BELOW || reached - p.base <= 3 * p.since + PetTracker.need(level - 1, offset)
        }

        val target: Pet
        if (pet == null) {
            target = Pet(name, offset, max)
            // Out and not sharing, or one that's sharing (a new one in the slots)
            if (o != null && o.name == name) others.add(target) else list.add(target)
            log.info("[MP expshare] new: $name at $level (offset $offset)")
            target.level = level
            target.base = reached
            target.exact = true
        } else {
            target = pet
            // What it made short of the level is counted now: the rest of the burst of Mining XP it came in is counted only past that
            val below = if (target.exact) reached - target.base else 0.0
            fix(target, reached, level)
            if (below > 0) {
                target.carry = below
                target.carryAt = System.currentTimeMillis()
            }
        }
        target.active = true
        colorOf(message, name)?.let { RARITY_BY_COLOR[it] }?.let { target.rarity = it }
        types[key(name)]?.let { target.type = it }
        seen[seenKey(name, offset)] = level
        // Max level: done with (what it made still counts)
        if (level >= max) {
            list.remove(target)
            others.remove(target)
            if (target !in retired) retired.add(target)
        }
        while (list.size > MAX_PETS) {
            val gone = list.minBy { it.base + it.since }
            list.remove(gone)
            if (gone.done != 0.0 || gone.since > 0) retired.add(gone)
        }
        save()
    }

    private fun petOutFromChat(message: Component, name: String, level: Int) {
        val color = colorOf(message, name)
        val offset = color?.let { BY_COLOR[it] } ?: 20
        petOut(name, offset, color?.let { RARITY_BY_COLOR[it] }, level, fromChat = true)
    }

    // The colour [needle] is written in within [text] (RGB), if it can be told
    private fun colorOf(text: Component, needle: String): Int? {
        var found: Int? = null
        text.visit({ style: Style, part: String ->
            if (found == null && part.contains(needle)) found = style.color?.value?.and(0xFFFFFF)
            java.util.Optional.empty<Unit>()
        }, Style.EMPTY)
        return found
    }

    /** What's known of the pets (for /miningprofit pet). */
    fun status(): List<String> {
        val lines = ArrayList<String>()
        val o = out
        lines.add("§d[MP] §7Pet out: " + if (o == null) "§cnone known" else "§f${o.name} ${if (o.level > 0) o.level else "?"} §7(${o.type ?: "type not seen"})" +
            if (o.maxed) " §7max level" else "")
        lines.add("§d[MP] §7Taming ${if (taming >= 0) taming else "§c? (guessing $TAMING_GUESS; open the Skills menu)§7"}")
        if (o != null) {
            val boost = boosts[o.name]
            lines.add("§d[MP] §7Held item boost of ${o.name}: " + if (boost == null) "§c? (counted as x1; open /pets to read it)" else "§fx$boost")
        }
        val leveling = leveling()
        for (p in (list + others + retired).distinct()) {
            val where = if (p in list) "sharing at ${"%.0f".format(rateOf(p) * 100)}%${if (p.rate < 0) " (default: open the Exp Share menu)" else ""}" else if (p in retired) "max level" else "seen out"
            lines.add("§d[MP] §f${p.name} ${if (p.level > 0) p.level else "?"} §7(${p.type ?: "?"} ${p.rarity.lowercase()}, $where)" + (if (p === leveling) " §a<- levelling" else "") +
                "§7: made §e${"%,.0f".format(p.session)} §7XP" + if (p.maxed || p.level <= 0) "" else ", §e${"%,.0f".format(p.left)}§7 to max")
            lines.add("§d[MP]    §8XP bar read " + if (p.reads == 0) "never (worked out from your Mining XP)" else "${p.reads}x, last ${(System.currentTimeMillis() - p.readAt) / 1000}s ago")
            // What its XP is worth: its prices (Coflnet's lowest BINs), taxed
            val prices = dev.miningprofit.support.PetPrices
            if (tiered(p)) {
                lines.add("§d[MP]    §8" + prices.describe(p.name, p.rarity, 1, 100, PetTracker.upTo(100, p.offset)))
                lines.add("§d[MP]    §8" + prices.describe(p.name, p.rarity, 100, 200, PetTracker.upTo(200, p.offset) - PetTracker.upTo(100, p.offset)))
            } else {
                lines.add("§d[MP]    §8" + prices.describe(p.name, p.rarity, 1, p.maxLevel, PetTracker.upTo(p.maxLevel, p.offset)))
            }
        }
        return lines
    }

    fun load() {
        try {
            if (!java.nio.file.Files.exists(file)) return
            val json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonObject
            taming = json.get("taming")?.asInt ?: -1
            miningLevel = json.get("mining")?.asInt ?: -1
            val version = json.get("v")?.asInt ?: 1
            // The one item boost kept before each pet had its own
            val itemPet = json.get("itemPet")?.asString ?: ""
            if (itemPet.isNotEmpty()) boosts[itemPet] = json.get("itemBoost")?.asDouble ?: 1.0
            json.getAsJsonObject("boosts")?.entrySet()?.forEach { boosts[it.key] = it.value.asDouble }
            json.getAsJsonObject("types")?.entrySet()?.forEach { types[it.key] = it.value.asString }
            json.getAsJsonObject("seen")?.entrySet()?.forEach { seen[it.key] = it.value.asInt }
            json.getAsJsonArray("pets")?.forEach { e -> readPet(e.asJsonObject, list.isEmpty())?.let { list.add(it) } }
            json.getAsJsonArray("others")?.forEach { e -> readPet(e.asJsonObject, false)?.let { others.add(it) } }
        } catch (_: Exception) {
            list.clear()
            others.clear()
        }
    }

    private fun readPet(o: com.google.gson.JsonObject, firstSlot: Boolean): Pet? {
        val p = Pet(o.get("name").asString, o.get("offset").asInt, o.get("max").asInt)
        p.level = o.get("level").asInt
        p.base = o.get("base").asDouble
        p.since = o.get("since")?.asDouble ?: 0.0
        p.rarity = o.get("rarity")?.asString ?: "LEGENDARY"
        p.rate = o.get("rate")?.asDouble ?: -1.0
        p.type = o.get("type")?.asString
        // Saved before this was kept: in slot order, and the first slot's is the one sure to be getting XP
        p.active = o.get("active")?.asBoolean ?: firstSlot
        // Where it was is where it was left: what it made since isn't known
        p.exact = false
        // And what the sum had it make then was last session's: this one starts from nothing
        p.done = -p.since
        return p
    }

    fun save() {
        dirty = false
        savedAt = System.currentTimeMillis()
        val root = com.google.gson.JsonObject()
        root.addProperty("taming", taming)
        root.addProperty("mining", miningLevel)
        root.addProperty("v", 5)
        root.add("boosts", com.google.gson.JsonObject().also { o -> boosts.forEach { (k, v) -> o.addProperty(k, v) } })
        root.add("types", com.google.gson.JsonObject().also { o -> types.forEach { (k, v) -> o.addProperty(k, v) } })
        root.add("seen", com.google.gson.JsonObject().also { o -> seen.forEach { (k, v) -> o.addProperty(k, v) } })
        root.add("pets", petsJson(list))
        root.add("others", petsJson(others))
        SaveFile.write(file, root.toString())
    }

    private fun petsJson(pets: List<Pet>): com.google.gson.JsonArray {
        val arr = com.google.gson.JsonArray()
        for (p in pets) arr.add(com.google.gson.JsonObject().apply {
            addProperty("name", p.name); addProperty("offset", p.offset); addProperty("max", p.maxLevel)
            addProperty("level", p.level); addProperty("base", p.base); addProperty("since", p.since); addProperty("rate", p.rate)
            addProperty("rarity", p.rarity); addProperty("active", p.active)
            p.type?.let { addProperty("type", it) }
        })
        return arr
    }
}
