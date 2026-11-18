package app.axiko.core

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import app.axiko.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The icon on the phone's home screen. An icon belongs to the launched component, so the app has
 * a launcher entry for every look (activity aliases in the manifest) and keeps exactly one
 * enabled: the mascot on one of its backgrounds, or the plain spark for an owner who does not
 * want the mascot anywhere. The art is part of the APK, so a new look needs a new build.
 */
object Launcher {
    /** In the order the owner sees them. The first one is the look of a fresh install. */
    private val entries = linkedMapOf(
        "mascot" to "app.axiko.IconMascot",
        "night" to "app.axiko.IconNight",
        "light" to "app.axiko.IconLight",
        "pink" to "app.axiko.IconPink",
        "plain" to "app.axiko.IconPlain",
        "plain-night" to "app.axiko.IconPlainNight",
    )

    private fun component(name: String) = ComponentName(App.instance, entries.getValue(name))

    /** The name of the look that is on now. */
    fun icon(): String {
        val manager = App.instance.packageManager
        return entries.keys.firstOrNull { manager.getComponentEnabledSetting(component(it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            ?: "mascot"
    }

    /**
     * Shows the chosen entry and hides the others. The new one is enabled first, so the app is
     * never without an entry; the home screen may take a few seconds to redraw the icon.
     */
    fun setIcon(name: String): String {
        require(name in entries) { "Значок — один из: " + entries.keys.joinToString(", ") }
        val manager = App.instance.packageManager
        manager.setComponentEnabledSetting(component(name), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in entries.keys - name) {
            manager.setComponentEnabledSetting(component(other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        return icon()
    }

    /**
     * Every look with its picture, for a screen that lets the owner choose: `[{id, on, image}]`.
     * The picture is the real icon as this phone's launcher shapes it, so the choice shows what
     * will stand on the home screen and no second copy of the art is kept for the screen.
     */
    fun icons(): JSONArray {
        val now = icon()
        return JSONArray(entries.keys.map { name -> JSONObject().put("id", name).put("on", name == now).put("image", picture(name)) })
    }

    private fun picture(name: String): String = runCatching {
        val manager = App.instance.packageManager
        val drawable = manager.getActivityInfo(component(name), PackageManager.MATCH_DISABLED_COMPONENTS).loadIcon(manager)
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, PICTURE_PX, PICTURE_PX)
        drawable.draw(Canvas(bitmap))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrDefault("")

    private const val PICTURE_PX = 144
}
