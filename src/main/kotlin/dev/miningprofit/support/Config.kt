package dev.miningprofit.support

/** The settings, kept in config/miningprofit.json (written whenever they change). */
object Config {

    // The profit tracker (see profit.ProfitTracker)
    var profitTracker = true
    /** Sell offer prices instead of instant sell. */
    var profitSellOffer = false
    /** Coal an hour to the item (9,184,222/hr) instead of in millions (9.2m/hr). */
    var profitExact = false
    /** Rose, Golden and Jade Dragons: under level 100 their XP is valued levelling to 100, from 100 on levelling to 200. */
    var profitDragonTiers = false
    /** Pets priced at what they sold for on average this week, instead of their lowest BIN now. */
    var petPriceWeekAvg = false
    /** What metals (mithril, titanium, tungsten, umber, glacite) are priced as: 0 enchanted, 1 as they drop. */
    var profitMetalTier = 0
    /** What gemstones are priced as: 0 Flawed, 1 Fine, 2 Flawless. */
    var profitGemTier = 0
    /** Every overlay session starts afresh each launch (else they're kept, over restarts too). */
    var overlayResetOnLaunch = false
    /** Overlay lines hidden, as "overlay.line". */
    val hiddenLines = LinkedHashSet<String>()
    var profitX = 4
    var profitY = 140

    // Commission Stats: Comms/hr, Glacite Pickos/hr, Shafts/hr (see gui.RatesOverlay)
    var ratesOverlay = true
    var overlayX = 4
    var overlayY = 4

    /** Core of the Mountain level: 2 or more gives Maniac Miner its longer duration (see ManiacMiner). */
    var cotmLevel = 10

    private val file get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("miningprofit.json")

    private val booleans: Map<String, kotlin.reflect.KMutableProperty0<Boolean>> get() = mapOf(
        "profitTracker" to ::profitTracker,
        "profitSellOffer" to ::profitSellOffer,
        "profitExact" to ::profitExact,
        "profitDragonTiers" to ::profitDragonTiers,
        "petPriceWeekAvg" to ::petPriceWeekAvg,
        "overlayResetOnLaunch" to ::overlayResetOnLaunch,
        "ratesOverlay" to ::ratesOverlay,
    )

    private val ints: Map<String, kotlin.reflect.KMutableProperty0<Int>> get() = mapOf(
        "profitMetalTier" to ::profitMetalTier,
        "profitGemTier" to ::profitGemTier,
        "profitX" to ::profitX,
        "profitY" to ::profitY,
        "overlayX" to ::overlayX,
        "overlayY" to ::overlayY,
        "cotmLevel" to ::cotmLevel,
    )

    fun load() {
        try {
            val f = file
            if (!java.nio.file.Files.exists(f)) return
            val json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).asJsonObject
            for ((key, prop) in booleans) if (json.has(key)) prop.set(json.get(key).asBoolean)
            for ((key, prop) in ints) if (json.has(key)) prop.set(json.get(key).asInt)
            hiddenLines.clear()
            json.getAsJsonArray("hiddenLines")?.forEach { hiddenLines.add(it.asString) }
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val json = com.google.gson.JsonObject()
            for ((key, prop) in booleans) json.addProperty(key, prop.get())
            for ((key, prop) in ints) json.addProperty(key, prop.get())
            json.add("hiddenLines", com.google.gson.JsonArray().apply { hiddenLines.forEach { add(it) } })
            java.nio.file.Files.writeString(file, com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json))
        } catch (_: Exception) {
        }
    }
}
