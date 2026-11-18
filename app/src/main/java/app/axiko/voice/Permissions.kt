package app.axiko.voice

import android.content.pm.PackageManager
import app.axiko.App
import app.axiko.MainActivity

object Permissions {
    private fun granted(name: String): Boolean =
        App.instance.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    /** Asks for the permission if needed and waits for the answer (off the main thread). */
    fun ensure(name: String): Boolean {
        if (granted(name)) return true
        val activity = MainActivity.current ?: return false
        activity.runOnUiThread { activity.requestPermissions(arrayOf(name), 78) }
        // The system dialog takes the window focus; the answer is in once the focus is back.
        for (tick in 0 until 480) {
            Thread.sleep(250)
            if (granted(name)) return true
            if (tick > 6 && activity.hasWindowFocus()) break
        }
        return granted(name)
    }
}
