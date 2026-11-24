package com.androidharness.app.tools

/**
 * Pure helpers behind device commands and app launches. Keeping the wording and
 * the verification decision here makes it testable, and stops a failed shell
 * command from being reported to the model as a successful device action.
 */
object DeviceCommandOutcome {
    fun failure(exitCode: Int, timedOut: Boolean, output: String, timeoutMs: Int): String? = when {
        timedOut ->
            "Device command timed out after ${timeoutMs}ms and was not completed. " +
                "Check the device state before claiming any result."
        exitCode != 0 ->
            "Device command failed (exit $exitCode): ${output.take(500)}. " +
                "A privileged shell is required: start Shizuku, connect Wireless ADB, or grant root/Device Owner in Settings → Terminal."
        else -> null
    }
}

object AppLaunchClaims {
    /** A foreground process is `pkg` or `pkg:subprocess`; `pkg.other` is a different app. */
    fun foregroundMatches(candidates: List<String>, packageName: String): Boolean =
        candidates.any { candidate ->
            candidate == packageName || candidate.startsWith("$packageName:")
        }

    fun verified(packageName: String, via: String): String =
        "Opened $packageName and verified it is in the foreground ($via)."

    fun unverified(packageName: String, observed: List<String>): String =
        "Launch of $packageName was requested, but it could not be verified in the foreground. " +
            "Observed foreground: ${observed.take(4).joinToString().ifBlank { "unknown" }}. " +
            "Check with device_screen before saying the app is open; if no backend is available, " +
            "ask the user to enable O2OAgent's Accessibility service or a privileged shell in Settings → Terminal."

    fun missing(packageName: String): String =
        "$packageName has no launcher activity on this device, so it cannot be opened as an app."

    fun launchFailed(packageName: String, detail: String): String =
        "Could not open $packageName: ${detail.take(400)}. " +
            "No app was launched; do not claim otherwise."

    fun foregroundLabel(candidates: List<String>): String =
        candidates.take(4).joinToString().ifBlank { "unknown" }
}
