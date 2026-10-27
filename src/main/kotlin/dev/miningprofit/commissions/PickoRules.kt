package dev.miningprofit.commissions

import dev.miningprofit.support.IslandTracker
import dev.miningprofit.support.ScoreboardUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What a Pickobulus breaks, per area - for Glacite Pickos/hr.
 *
 * The thrown pickaxe is a point that starts at your eyes and moves [THROW_STEP] blocks per step along your view; the
 * first step inside a solid block is the hit block. The blocks in the 6x6x6 cube from -3 to +2 around it are then gone
 * through in order (x, then y, then z), and each one the area lets Pickobulus break goes if one of its sides touches air
 * at that moment. Glacite and gemstones turn into air when broken, so the blocks behind them can go too; everything else
 * turns into bedrock, which opens nothing up.
 */
object PickoRules {

    class Profile(
        val name: String,
        // category of each block this area's Pickobulus breaks
        val category: Map<Block, String>,
        // categories that turn into air when broken (the rest turn into bedrock)
        val becomesAir: Set<String>,
        // categories worth breaking
        val valuable: Set<String>,
    )

    private val MITHRIL = listOf(
        Blocks.GRAY_WOOL, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_WOOL,
        Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE
    )

    val DWARVEN = Profile(
        "Dwarven Mines",
        buildMap {
            MITHRIL.forEach { put(it, "mithril") }
            put(Blocks.POLISHED_DIORITE, "titanium")
            put(Blocks.STONE, "stone")
        },
        becomesAir = emptySet(),
        valuable = setOf("mithril", "titanium"),
    )

    // White glass is opal: a gemstone in a Mineshaft, but in the Glacite Tunnels the white layers never break
    private val OPAL = setOf(Blocks.WHITE_STAINED_GLASS, Blocks.WHITE_STAINED_GLASS_PANE)

    private fun opalBreaks() = IslandTracker.currentMiningIsland == "Mineshaft"

    // The Glacite categories with opal left out while it doesn't break
    private class OpalAware(private val all: Map<Block, String>) : Map<Block, String> by all {
        override fun get(key: Block): String? = if (key in OPAL && !opalBreaks()) null else all[key]
        override fun containsKey(key: Block): Boolean = get(key) != null
    }

    val GLACITE = Profile(
        "Glacite Tunnels",
        OpalAware(buildMap {
            put(Blocks.PACKED_ICE, "glacite")
            BuiltInRegistries.BLOCK.forEach { block ->
                val path = BuiltInRegistries.BLOCK.getKey(block).path
                if (path.endsWith("stained_glass") || path.endsWith("stained_glass_pane")) put(block, "gemstone")
            }
            put(Blocks.BROWN_TERRACOTTA, "umber")
            put(Blocks.TERRACOTTA, "umber")
            put(Blocks.SMOOTH_RED_SANDSTONE, "umber")
            put(Blocks.CLAY, "tungsten")
            put(Blocks.INFESTED_COBBLESTONE, "tungsten")
            put(Blocks.INFESTED_STONE, "hardstone")
            put(Blocks.LIGHT_GRAY_WOOL, "hardstone")
            MITHRIL.forEach { put(it, "mithril") }
            put(Blocks.POLISHED_DIORITE, "titanium")
        }),
        becomesAir = setOf("glacite", "gemstone"),
        valuable = setOf("glacite", "gemstone", "umber", "tungsten", "mithril", "titanium"),
    )

    // How far the thrown pickaxe moves per step
    private const val THROW_STEP = 0.85
    private const val MAX_THROW = 40.0

    /** The block a throw from [eye] looking along [look] hits, or null if it hits nothing in range. */
    fun throwTarget(level: ClientLevel, eye: net.minecraft.world.phys.Vec3, look: net.minecraft.world.phys.Vec3): BlockPos? {
        val pos = BlockPos.MutableBlockPos()
        var t = THROW_STEP
        while (t <= MAX_THROW) {
            pos.set(
                kotlin.math.floor(eye.x + look.x * t).toInt(),
                kotlin.math.floor(eye.y + look.y * t).toInt(),
                kotlin.math.floor(eye.z + look.z * t).toInt()
            )
            val state = level.getBlockState(pos)
            if (!state.isAir && state.fluidState.isEmpty && (!isThin(state) ||
                    inShape(level, pos, state, eye.x + look.x * t, eye.y + look.y * t, eye.z + look.z * t))
            ) return pos.immutable()
            t += THROW_STEP
        }
        return null
    }

    // Panes (gemstone panes) are only a thin post and arms: the pickaxe flies through the rest of the cell,
    // counting as touching them a little way out
    private const val PANE_MARGIN = 1.0 / 16
    private fun isThin(state: BlockState): Boolean {
        val path = BuiltInRegistries.BLOCK.getKey(state.block).path
        return path.endsWith("_pane") || path == "iron_bars"
    }

    private fun inShape(level: ClientLevel, pos: BlockPos, state: BlockState, x: Double, y: Double, z: Double): Boolean {
        val lx = x - pos.x
        val ly = y - pos.y
        val lz = z - pos.z
        return state.getCollisionShape(level, pos).toAabbs().any { it.inflate(PANE_MARGIN, 0.0, PANE_MARGIN).contains(lx, ly, lz) }
    }

    /** The rules for where you are, or null where there are none. */
    fun current(): Profile? {
        val location = ScoreboardUtils.location
        // The Base Camp (and its campfire) is where Glacite trips start: counts as the Glacite area
        val now = if ("Glacite" in location || "Base Camp" in location || "Campfire" in location ||
            IslandTracker.currentMiningIsland == "Mineshaft") GLACITE
        else if (IslandTracker.currentMiningIsland == "Dwarven Mines") DWARVEN else null
        val time = System.currentTimeMillis()
        if (now != null) {
            lastProfile = now
            lastProfileAt = time
            return now
        }
        // Changing servers (e.g. out of a Mineshaft), before the new one says where you are: the last area
        return if (IslandTracker.currentMiningIsland == null && time - lastProfileAt < SWITCH_GRACE_MS) lastProfile else null
    }

    private var lastProfile: Profile? = null
    private var lastProfileAt = 0L
    private const val SWITCH_GRACE_MS = 20_000L

    class Counts(val byCategory: Map<String, Int>) {
        operator fun get(category: String) = byCategory[category] ?: 0
    }

    // Open to air right now: not a solid block (air, bedrock, fluid)
    private fun open(state: BlockState) = state.isAir || state.`is`(Blocks.BEDROCK) || !state.fluidState.isEmpty

    // A block with barrier against one of its sides is walled in (e.g. a Mineshaft's display gems): never broken
    private fun caged(level: ClientLevel, pos: BlockPos): Boolean {
        val n = BlockPos.MutableBlockPos()
        for (dir in Direction.entries) if (level.getBlockState(n.setWithOffset(pos, dir)).`is`(Blocks.BARRIER)) return true
        return false
    }

    /** What a throw hitting [target] breaks right now under [profile]; each block it breaks also goes in [positions]. */
    fun simulate(level: ClientLevel, target: BlockPos, profile: Profile, positions: MutableMap<BlockPos, String>? = null): Counts {
        val pos = BlockPos.MutableBlockPos()
        val neighbour = BlockPos.MutableBlockPos()
        val nowAir = HashSet<Long>() // broken into air during this throw
        val nowBedrock = HashSet<Long>() // broken into bedrock during this throw (solid for this throw)
        val counts = HashMap<String, Int>()
        for (dx in -3..2) for (dy in -3..2) for (dz in -3..2) {
            pos.set(target.x + dx, target.y + dy, target.z + dz)
            val state = level.getBlockState(pos)
            val category = profile.category[state.block] ?: continue
            if (caged(level, pos)) continue
            var exposed = false
            for (dir in Direction.entries) {
                neighbour.setWithOffset(pos, dir)
                val key = neighbour.asLong()
                if (key in nowAir) { exposed = true; break }
                if (key in nowBedrock) continue
                if (open(level.getBlockState(neighbour))) { exposed = true; break }
            }
            if (!exposed) continue
            if (category in profile.becomesAir) nowAir.add(pos.asLong()) else nowBedrock.add(pos.asLong())
            positions?.put(pos.immutable(), category)
            counts[category] = (counts[category] ?: 0) + 1
        }
        return Counts(counts)
    }
}
