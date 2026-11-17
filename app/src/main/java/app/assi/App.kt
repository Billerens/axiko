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
        // Keys of model providers that lay in the settings go to the vault before any page or chat is opened,
        // and what was written to disk while they were readable (chats, tasks) is cleaned of every stored secret.
        runCatching {
            val moved = app.assi.agent.Models.settle()
            if (moved || prefs.getString(SECRETS_CLEANED, null) != SECRETS_CLEANING) {
                app.assi.core.Secrets.scrubFiles(Workspace.state, app.assi.core.Vault.known()) { it.name == "store" && it.parentFile == Workspace.state }
                prefs.edit().putString(SECRETS_CLEANED, SECRETS_CLEANING).apply()
            }
        }
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

        /** Says the files written before secrets were sealed have been cleaned of them, and by which rules. */
        private const val SECRETS_CLEANED = "secrets.cleaned"
        private const val SECRETS_CLEANING = "2"

        /** Settings the pages and the agent share: provider, model, voice. */
        val prefs: SharedPreferences
            get() = instance.getSharedPreferences("assi", Context.MODE_PRIVATE)
    }
}
