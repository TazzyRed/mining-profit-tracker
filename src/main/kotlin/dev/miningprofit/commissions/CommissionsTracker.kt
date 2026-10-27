package dev.miningprofit.commissions

import dev.miningprofit.support.IslandTracker
import dev.miningprofit.support.StringUtils

/**
 * Commissions completed, per hour and uptime - one clock for each region (the Dwarven Mines, the Crystal
 * Hollows, the Glacite Tunnels with their Mineshafts), each running on its own; what's shown is the one
 * for where you are. Uptime only counts time spent making commission progress: after
 * [INACTIVITY_TIMEOUT_MS] without progress a clock pauses and rolls back to the last progress, so e.g.
 * 25:30 becomes 25:20 instead of counting the idle 10 seconds.
 */
object CommissionsTracker {

    private const val INACTIVITY_TIMEOUT_MS = 30_000L

    private class Clock {
        var completed = 0
        var glacitePickos = 0
        var shafts = 0
        var startTime = 0L
        var lastActivityTime = 0L
        var isPaused = false
        var pauseStartTime = 0L
        var pauseCount = 0
        var pausedMs = 0L
        val running get() = startTime != 0L
        fun activeMs(now: Long) = if (!running) 0L else (if (isPaused) pauseStartTime else now) - startTime
        fun pause(from: Long) {
            isPaused = true
            pauseStartTime = from
            pauseCount++
        }
    }

    private val clocks = HashMap<String, Clock>()
    /** Where you are, as of the last tick (somewhere without commissions: where you last were). */
    @Volatile
    var region = "Dwarven Mines"
        private set
    const val GLACITE = "Glacite Tunnels"
    // The clock of where you are
    private val clock: Clock get() = synchronized(clocks) { clocks.getOrPut(region) { Clock() } }

    // The tab list calls the Tunnels the Dwarven Mines: they're told apart by the sidebar's location, and
    // go with their Mineshafts
    private fun findRegion() {
        val island = IslandTracker.currentMiningIsland
        val rules = try { PickoRules.current() } catch (_: Throwable) { null }
        when {
            island == "Mineshaft" || rules === PickoRules.GLACITE -> region = GLACITE
            island == "Crystal Hollows" -> region = island
            island == "Dwarven Mines" && rules === PickoRules.DWARVEN -> region = island
        }
    }

    // For the Comms/hr hover: how often it paused and for how long, and Mineshafts gone into
    val pauseCount: Int get() = clock.pauseCount
    // (on the Tunnels' clock: they're the Base Camp's)
    val shaftsEntered: Int get() = clock.shafts
    private var wasInShaft = false

    /** Mineshafts gone into an hour, over the uptime (as Comms/hr is). */
    fun shaftsPerHour(): Double {
        val c = clock
        val ms = c.activeMs(System.currentTimeMillis())
        return if (ms <= 0L) 0.0 else c.shafts / (ms / 3_600_000.0)
    }

    /**
     * Whether the clock is running: it starts with the session's first commission progress (or
     * completion), so the commission being worked on at the start counts towards uptime as well as
     * towards Completed.
     */
    val running: Boolean get() = clock.running

    /**
     * A commission finished: its "... Commission Complete!" message came. Counted from the message, which
     * comes once for each, rather than from the tab list, which can go from a commission straight to its
     * replacement (claimed a second or two after finishing) without ever showing it done.
     */
    fun registerCompletion() {
        clock.completed++
        registerProgress()
    }

    /** Commission progress was made: resume if paused, skipping the time spent paused. */
    fun registerProgress() {
        val c = clock
        val now = System.currentTimeMillis()
        if (!c.running) c.startTime = now
        c.lastActivityTime = now
        // The profit tracker's clock runs with this one on commissions
        dev.miningprofit.profit.ProfitTracker.onCommissionActivity(now)
        if (c.isPaused) {
            c.startTime += now - c.pauseStartTime
            c.pausedMs += now - c.pauseStartTime
            c.isPaused = false
        }
    }

    fun onClientTick() {
        val inShaft = IslandTracker.currentMiningIsland == "Mineshaft"
        if (inShaft && !wasInShaft) synchronized(clocks) { clocks.getOrPut(GLACITE) { Clock() } }.shafts++
        wasInShaft = inShaft
        findRegion()
        val now = System.currentTimeMillis()
        for (c in synchronized(clocks) { clocks.values.toList() }) {
            if (!c.running || c.isPaused) continue
            // Paused from the last progress, which takes the idle seconds back off the uptime
            if (now - c.lastActivityTime >= INACTIVITY_TIMEOUT_MS) c.pause(c.lastActivityTime)
        }
    }

    fun isPausedForInactivity(): Boolean = clock.isPaused

    /** Time spent paused this session, the current pause included. */
    fun getPausedMs(): Long = clock.let { it.pausedMs + if (it.isPaused) System.currentTimeMillis() - it.pauseStartTime else 0L }

    fun getActiveDurationMs(): Long = clock.activeMs(System.currentTimeMillis())

    fun getCompletedCount(): Int = clock.completed

    fun getCommissionsPerHour(): Double {
        val c = clock
        val ms = c.activeMs(System.currentTimeMillis())
        return if (ms <= 0L) 0.0 else c.completed / (ms / 3_600_000.0)
    }

    /**
     * A Pickobulus thrown at glacite in the Glacite Tunnels (or a Mineshaft): counted on the Tunnels' clock whether or
     * not it's running, so a throw before the first commission progress is in the rate (over the uptime, as Comms/hr is).
     */
    fun registerGlacitePicko() {
        val c = synchronized(clocks) { clocks.getOrPut(GLACITE) { Clock() } }
        c.glacitePickos++
    }

    val glacitePickos: Int get() = clock.glacitePickos

    fun getGlacitePickosPerHour(): Double {
        val c = clock
        val ms = c.activeMs(System.currentTimeMillis())
        return if (ms <= 0L) 0.0 else c.glacitePickos / (ms / 3_600_000.0)
    }

    fun getUptime(): String = StringUtils.formatTime(getActiveDurationMs() / 1000L)

    /** Where you are's clock starts again ([all]: every one). */
    fun reset(all: Boolean = false) {
        synchronized(clocks) { if (all) clocks.clear() else clocks.remove(region) }
    }

    /** The clocks, to keep over a restart (see ProfitTracker's sessions). */
    fun writeState(): com.google.gson.JsonObject {
        val now = System.currentTimeMillis()
        val o = com.google.gson.JsonObject()
        for ((k, c) in synchronized(clocks) { clocks.toMap() }) {
            o.add(k, com.google.gson.JsonObject().apply {
                addProperty("completed", c.completed)
                addProperty("pickos", c.glacitePickos)
                addProperty("shafts", c.shafts)
                addProperty("ms", c.activeMs(now))
                addProperty("pauses", c.pauseCount)
                addProperty("pausedMs", c.pausedMs)
            })
        }
        return o
    }

    /** Picked up again paused, as they were left. */
    fun readState(o: com.google.gson.JsonObject) {
        val now = System.currentTimeMillis()
        synchronized(clocks) {
            for ((k, v) in o.entrySet()) {
                val j = v.asJsonObject
                val c = Clock()
                c.completed = j.get("completed")?.asInt ?: 0
                c.glacitePickos = j.get("pickos")?.asInt ?: 0
                c.shafts = j.get("shafts")?.asInt ?: 0
                c.pauseCount = j.get("pauses")?.asInt ?: 0
                c.pausedMs = j.get("pausedMs")?.asLong ?: 0L
                val ms = j.get("ms")?.asLong ?: 0L
                if (ms > 0) {
                    c.startTime = now - ms
                    c.lastActivityTime = now
                    c.isPaused = true
                    c.pauseStartTime = now
                }
                clocks[k] = c
            }
        }
    }
}
