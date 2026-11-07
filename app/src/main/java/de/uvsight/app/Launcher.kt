package de.uvsight.app

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Nocked as the phone's home screen (for a phone that only serves the sight): a second entry of the main
 * activity that answers the Home button, switched off by default. On: the phone asks whether Nocked becomes
 * the home app. Off: the entry goes away and the phone's own choice of home app opens, to pick the old one.
 */
object Launcher {
    private fun entry(c: Context) = ComponentName(c, "de.uvsight.app.HomeActivity")
    fun enabled(c: Context): Boolean = c.packageManager.getComponentEnabledSetting(entry(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun set(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(entry(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        if (on) {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = c.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    runCatching { c.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return
                }
            }
            // older Android: the system's chooser for the home screen appears on the next Home press, or now
            runCatching { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            // the phone's own choice of home app, so the old launcher can be picked again
            runCatching { c.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}
