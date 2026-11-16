package app.assi.sense

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import app.assi.App
import app.assi.MainActivity
import app.assi.R
import app.assi.core.Engine
import app.assi.core.Workspace
import org.json.JSONObject
import java.io.File

/** The tile in the quick settings shade: one tap to ask Assi about whatever is on the screen. */
class AskTile : TileService() {
    override fun onClick() {
        val open = Intent(this, MainActivity::class.java).putExtra("assist", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Engine.pool.execute { Assist.captureNow() }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(open)
        }
    }
}

/**
 * A home-screen widget that shows what a module wants shown. A module takes part by exporting
 * `widget()` from its main.js, returning `{ title, text, open, image }` (image: a picture in
 * `data/`, shown left of the text); the owner picks the module when placing the widget.
 */
class BoardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Engine.pool.execute {
            try {
                ids.forEach { Widgets.draw(it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { App.prefs.edit().remove("widget.$it").apply() }
    }
}

object Widgets {
    /** Modules that export `widget()`: `id → title`. */
    fun sources(): List<Pair<String, String>> =
        File(Workspace.root, "modules").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { dir ->
            val main = File(dir, "main.js")
            if (!main.isFile || !Regex("exports\\.widget\\s*=").containsMatchIn(main.readText())) return@mapNotNull null
            val title = runCatching { JSONObject(File(dir, "module.json").readText()).optString("title") }.getOrDefault("")
            dir.name to title.ifBlank { dir.name }
        }

    fun draw(id: Int) {
        AppWidgetManager.getInstance(App.instance).updateAppWidget(id, views(id, App.prefs.getString("widget.$id", null)))
    }

    /** What widget [id] shows for [module] (null: none chosen yet): `{title, text, open, image}` of its widget(). */
    fun views(id: Int, module: String?): RemoteViews {
        val app = App.instance
        val views = RemoteViews(app.packageName, R.layout.widget_board)
        var open = MainActivity.HOME
        var picture: Bitmap? = null
        if (module == null) {
            views.setTextViewText(R.id.widget_title, "Assi")
            views.setTextViewText(R.id.widget_text, "Удерживайте виджет и выберите модуль")
        } else {
            val content = runCatching { JSONObject(Engine.rpc("modules/$module/main.js", "widget", "[]") ?: "{}") }
                .getOrElse { JSONObject().put("title", module).put("text", "Модуль не ответил: " + Engine.describe(it).lineSequence().first()) }
            views.setTextViewText(R.id.widget_title, content.optString("title").ifBlank { module })
            views.setTextViewText(R.id.widget_text, content.optString("text"))
            open = content.optString("open").ifBlank { "modules/$module/ui/index.html" }
            picture = content.optString("image").takeIf { it.isNotBlank() }?.let { picture(it) }
        }
        views.setViewVisibility(R.id.widget_image, if (picture != null) View.VISIBLE else View.GONE)
        picture?.let { views.setImageViewBitmap(R.id.widget_image, it) }
        val tap = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java).setAction("app.assi.OPEN.$id").putExtra("open", open),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, tap)
        return views
    }

    /**
     * A module's picture (`data/...`), made small enough to cross over to the launcher, which
     * cannot read the app's files itself; null when there is nothing to show.
     */
    private fun picture(path: String): Bitmap? {
        val file = Workspace.resolve(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PICTURE_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The longest side of a widget's picture: sharp on a big widget, light for the launcher. */
    private const val PICTURE_PX = 480

    /** Redraws every widget, or only those of one module (a module calls this when its data changes). */
    fun refresh(module: String? = null) {
        val app = App.instance
        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, BoardWidget::class.java))
        Engine.pool.execute {
            ids.filter { module == null || App.prefs.getString("widget.$it", null) == module }.forEach { draw(it) }
        }
    }
}

/** Shown when a widget is placed: which module it should show. */
class WidgetChoice : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val sources = Widgets.sources()
        if (sources.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Пока ни один модуль не умеет показывать себя в виджете. Попросите Assi: «сделай виджет для модуля …».")
                .setPositiveButton("Понятно", null)
                .setOnDismissListener { finish() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Что показывать")
            .setItems(sources.map { it.second }.toTypedArray()) { _, which ->
                App.prefs.edit().putString("widget.$id", sources[which].first).commit()
                Engine.pool.execute { Widgets.draw(id) }
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
