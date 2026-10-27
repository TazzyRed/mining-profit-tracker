package dev.miningprofit.support

object StringUtils {

    /** mm:ss, hh:mm:ss, dd:hh:mm:ss. */
    fun formatTime(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> String.format("%02d:%02d:%02d:%02d", days, hours, minutes, secs)
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, secs)
            else -> String.format("%02d:%02d", minutes, secs)
        }
    }
}
