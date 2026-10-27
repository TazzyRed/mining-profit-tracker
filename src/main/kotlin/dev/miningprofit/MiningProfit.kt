package dev.miningprofit

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.miningprofit.commissions.CommissionClock
import dev.miningprofit.commissions.CommissionsTracker
import dev.miningprofit.commissions.GlacitePickos
import dev.miningprofit.gui.LineClicks
import dev.miningprofit.gui.OverlayMover
import dev.miningprofit.gui.RatesOverlay
import dev.miningprofit.gui.SettingsScreen
import dev.miningprofit.profit.ExpShare
import dev.miningprofit.profit.ProfitTracker
import dev.miningprofit.support.Config
import dev.miningprofit.support.Election
import dev.miningprofit.support.ManiacMiner
import dev.miningprofit.support.ScoreboardUtils
import dev.miningprofit.support.Tab
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionResult

/**
 * Mining Profit Tracker: the profit overlay (drops, pets levelling, shaft loot) and Commission Stats (Comms/hr, Glacite
 * Pickos/hr, Shafts/hr). This is the wiring: the tab list and sidebar read, chat and right-clicks passed on, the
 * overlays drawn, and /miningprofit.
 */
object MiningProfit : ClientModInitializer {

    private const val TAG = "§b[Mining Profit] §7"

    private fun say(ctx: CommandContext<FabricClientCommandSource>, text: String) =
        ctx.source.sendFeedback(Component.literal(TAG + text))

    override fun onInitializeClient() {
        Config.load()
        ExpShare.load()
        ExpShare.register()
        LineClicks.register()

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            Tab.onClientTick(client)
            ScoreboardUtils.onClientTick(client)
            CommissionClock.onClientTick()
            CommissionsTracker.onClientTick()
            ProfitTracker.onClientTick()
            ExpShare.onClientTick()
            Election.onClientTick(client)
        })

        ClientReceiveMessageEvents.GAME.register(ClientReceiveMessageEvents.Game { message, overlay ->
            val plain = message.string.replace(Regex("§."), "")
            // The action bar: Mining XP for the profit tracker
            if (overlay) {
                ProfitTracker.onActionBar(plain)
                return@Game
            }
            ManiacMiner.onChat(plain)
            CommissionClock.onChat(plain)
            GlacitePickos.onChat(plain)
            ProfitTracker.onChat(plain)
            ExpShare.onChat(message, plain)
        })

        // Right-clicks: the Fiesta Flask drunk (profit tracker), and where a Pickobulus is thrown from
        UseItemCallback.EVENT.register { player, _, hand ->
            ProfitTracker.onUse(player, hand)
            GlacitePickos.onUse()
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player, _, hand, _ ->
            ProfitTracker.onUse(player, hand)
            GlacitePickos.onUse()
            InteractionResult.PASS
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("miningprofit", "overlays")) { context, _ ->
            RatesOverlay.render(context)
            ProfitTracker.render(context)
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("miningprofit")
                .executes { SettingsScreen.open(); 1 }
                .then(literal("gui").executes { OverlayMover.start(null); 1 })
                // Where you are's sessions: its rates clock and its profit tracker
                .then(literal("reset").executes {
                    CommissionsTracker.reset()
                    val done = ProfitTracker.reset()
                    RatesOverlay.build(force = true)
                    say(it, "Reset here: " + (listOf("rates") + done).joinToString(", ") + ". §8(/miningprofit reset all: everywhere)")
                    1
                }.then(literal("all").executes {
                    CommissionsTracker.reset(all = true)
                    ProfitTracker.reset(all = true)
                    RatesOverlay.build(force = true)
                    say(it, "Every overlay session reset.")
                    1
                }))
                // Only the profit session (the rates carry on)
                .then(literal("tracker").then(literal("reset").executes {
                    val done = ProfitTracker.reset()
                    say(it, if (done.isEmpty()) "No profit tracker session here to reset." else "Profit tracker reset: ${done.joinToString(", ")}.")
                    1
                }))
                // Fortune: what it has, or set by hand
                .then(literal("fortune").executes { say(it, "Fortune: " + ProfitTracker.fortuneStatus()); 1 }
                    .then(argument("type", StringArgumentType.word())
                        .suggests { _, b -> listOf("mining", "ore", "block", "gem", "metal").forEach { s -> b.suggest(s) }; b.buildFuture() }
                        .then(argument("value", IntegerArgumentType.integer(0, 100000)).executes {
                            val type = StringArgumentType.getString(it, "type")
                            val ok = ProfitTracker.setFortune(type, IntegerArgumentType.getInteger(it, "value"))
                            say(it, if (ok) "Fortune: " + ProfitTracker.fortuneStatus() else "§cUnknown fortune '$type' (mining, ore, block, gem or metal).")
                            1
                        })))
                // The Fiesta Flask: how it stands, or set by hand
                .then(literal("flask").executes { say(it, ProfitTracker.flaskStatus()); 1 }
                    .then(literal("on").executes { say(it, ProfitTracker.setFlask(true)); 1 })
                    .then(literal("off").executes { say(it, ProfitTracker.setFlask(false)); 1 }))
                // The pets: which is out, what each has made
                .then(literal("pet").executes {
                    for (line in ExpShare.status()) it.source.sendFeedback(Component.literal(line))
                    1
                }))
        }

        org.slf4j.LoggerFactory.getLogger("MiningProfit").info("Mining Profit Tracker loaded")
    }
}
