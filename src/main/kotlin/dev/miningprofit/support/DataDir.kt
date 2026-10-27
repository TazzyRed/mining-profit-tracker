package dev.miningprofit.support

import java.nio.file.Path

/** Where the trackers keep their sessions and what they've learned: config/miningprofit/. */
object DataDir {
    val path: Path get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("miningprofit")

    fun file(name: String): Path {
        try { java.nio.file.Files.createDirectories(path) } catch (_: Exception) {}
        return path.resolve(name)
    }
}
