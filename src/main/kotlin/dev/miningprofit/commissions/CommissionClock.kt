package dev.miningprofit.commissions

import dev.miningprofit.support.CommissionWidget

/**
 * What runs the rates' clock ([CommissionsTracker]): a commission's progress going up in the tab list, and
 * "... Commission Complete!" in chat. The clock pauses after a spell with neither.
 */
object CommissionClock {

    private val DONE = Regex("""^([A-Z][A-Z ]+?) Commission Complete!""")
    // Each commission's progress last seen (percent; 100 = done)
    private val last = HashMap<String, Double>()
    private var ticks = 0

    fun onClientTick() {
        if (++ticks % 5 != 0) return
        val seen = HashSet<String>()
        for (c in CommissionWidget.commissions) {
            val key = c.key
            val pct = if (c.completed) 100.0 else c.progress.removeSuffix("%").toDoubleOrNull() ?: continue
            seen.add(key)
            val was = last.put(key, pct)
            if (was != null && pct > was) CommissionsTracker.registerProgress()
        }
        last.keys.retainAll(seen)
    }

    fun onChat(text: String) {
        if (DONE.containsMatchIn(text)) CommissionsTracker.registerCompletion()
    }
}
