package app.assi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.assi.core.Engine
import app.assi.core.Jobs
import app.assi.core.Workspace
import app.assi.sense.Triggers
import app.assi.store.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // A restore from a backup the owner confirmed swaps the folders here, before anyone opens them.
        app.assi.backup.Backup.startup(this)
        Workspace.init(this)
        runCatching { Store.recover() }.onFailure {
            Workspace.safeMode = true
            android.util.Log.e("Assi", "Не удалось восстановить операцию магазина; включён безопасный режим", it)
        }
        Engine.init(this)
        Engine.pool.execute {
            runCatching { Workspace.migrateHistoryIfNeeded() }
                .onFailure { Engine.host.log("error", "Миграция истории: ${it.message}") }
        }
        Jobs.rearm()
        Triggers.start(this)
    }

    companion object {
        lateinit var instance: App
            private set

        /** Settings the pages and the agent share: provider, model, voice. */
        val prefs: SharedPreferences
            get() = instance.getSharedPreferences("assi", Context.MODE_PRIVATE)
    }
}
