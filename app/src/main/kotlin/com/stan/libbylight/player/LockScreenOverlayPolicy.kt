package com.stan.libbylight.player

/**
 * Pure decision logic for [LockScreenControlsOverlay], kept free of Android types so it can be
 * unit tested. The row is drawn only when every input agrees; any doubt hides it, because a row
 * drawn over some other app is the one failure this feature must not have.
 */
internal object LockScreenOverlayPolicy {
    const val LIGHTOS_PACKAGE = "com.lightos"
    const val LIGHTOS_LOCK_ACTIVITY = "com.lightos.MainActivity"

    data class Inputs(
        val enabled: Boolean,
        val canDrawOverlays: Boolean,
        val hasUsageAccess: Boolean,
        val hasBook: Boolean,
        val screenOn: Boolean,
        val bardInForeground: Boolean,
        val topIsLightOsLockScreen: Boolean,
        val dismissedThisWake: Boolean,
    )

    enum class Action { Show, Hide, Nothing }

    fun wanted(i: Inputs): Boolean =
        i.enabled &&
            i.canDrawOverlays &&
            i.hasUsageAccess &&
            i.hasBook &&
            i.screenOn &&
            !i.bardInForeground &&
            i.topIsLightOsLockScreen &&
            !i.dismissedThisWake

    fun decide(i: Inputs, shown: Boolean): Action {
        val want = wanted(i)
        return when {
            want && !shown -> Action.Show
            !want && shown -> Action.Hide
            else -> Action.Nothing
        }
    }

    /**
     * LightOS's lock screen and its launcher are the same activity (`com.lightos/.MainActivity`).
     * Other LightOS activities (calls, permission prompts) never get the row. A null class name
     * (not reported) is accepted for the LightOS package only.
     */
    fun isLightOsLockScreen(packageName: String?, className: String?, homePackages: Set<String>): Boolean {
        if (packageName == null) return false
        if (packageName == LIGHTOS_PACKAGE) {
            return className == null || className == LIGHTOS_LOCK_ACTIVITY
        }
        return packageName in homePackages
    }
}
