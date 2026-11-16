package app.assi.core

import android.content.ComponentName
import android.content.pm.PackageManager
import app.assi.App

/**
 * The icon on the phone's home screen. An icon belongs to the launched component, so the app has
 * two launcher entries (activity aliases in the manifest) and keeps exactly one enabled: the one
 * with the mascot, or the plain one for an owner who does not want the mascot anywhere.
 */
object Launcher {
    private val entries = mapOf("mascot" to "app.assi.IconMascot", "plain" to "app.assi.IconPlain")

    private fun component(name: String) = ComponentName(App.instance, entries.getValue(name))

    /** "mascot" or "plain". */
    fun icon(): String {
        val plain = App.instance.packageManager.getComponentEnabledSetting(component("plain"))
        return if (plain == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "plain" else "mascot"
    }

    /**
     * Shows the chosen entry and hides the other. The new one is enabled first, so the app is
     * never without an entry; the home screen may take a few seconds to redraw the icon.
     */
    fun setIcon(name: String): String {
        require(name in entries) { "Иконка — mascot или plain" }
        val manager = App.instance.packageManager
        manager.setComponentEnabledSetting(component(name), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in entries.keys - name) {
            manager.setComponentEnabledSetting(component(other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        return icon()
    }
}
