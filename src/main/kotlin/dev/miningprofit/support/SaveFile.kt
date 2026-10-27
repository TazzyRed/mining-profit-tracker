package dev.miningprofit.support

import java.nio.file.Files
import java.nio.file.Path

/** Small state files (sessions, fortune, pets) written whole on a background thread, not mid-frame. */
object SaveFile {

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "miningprofit-save").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun write(file: Path, text: CharSequence) {
        val body = text.toString()
        io.execute {
            try {
                Files.createDirectories(file.parent)
                Files.writeString(file, body)
            } catch (_: Exception) {
            }
        }
    }
}
