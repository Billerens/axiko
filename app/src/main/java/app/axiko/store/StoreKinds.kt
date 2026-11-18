package app.axiko.store

import org.json.JSONObject
import java.io.File

/**
 * A kind of package: where its packages live, which file describes one and which version of its
 * host's rules they are written for. `module` is the shell's own kind, a folder in `modules/`;
 * every other kind belongs to the module that hosts it.
 */
class StoreKind(
    val name: String,
    /** The module whose folder holds packages of this kind; null for the shell's own `module`. */
    val host: String?,
    /** The file inside a package that describes it to its host. */
    val descriptor: String,
    /** The key under which a package names the version of the host's rules it needs, if the host has versions. */
    val engine: String?,
    val version: Int,
    /** Whether a package of this kind must name an HTML screen of its own in its descriptor. */
    val screen: Boolean,
    /** IDs a package of this kind cannot have: folders of the host itself. */
    val reserved: Set<String>,
    /** The host's words for the kind: many of them and one. Shown as they are; the host's own text. */
    val title: String,
    val item: String,
) {
    fun target(id: String): String = if (host == null) "modules/$id" else "modules/$host/$id"
}

/**
 * The kinds of packages this installation knows. Nothing but `module` is known to the shell
 * itself: a module that hosts packages says so in `hosts` of its `module.json`,
 *
 *     "hosts": [{ "kind": "game", "descriptor": "game.json", "engine": "gamesEngine", "version": 1,
 *                 "screen": true, "reserved": ["engine", "ui"], "title": "Игры", "item": "Игра" }]
 *
 * and packages of that kind are installed into its folder, `modules/<host>/<id>`, and nowhere
 * else. A declaration cannot name another place, claim `module`, or take a kind another host
 * already declared: the first host by ID keeps it.
 */
class StoreKinds(private val declarations: () -> List<Pair<String, JSONObject>>) {
    fun all(): Map<String, StoreKind> {
        val found = linkedMapOf(MODULE to StoreKind(MODULE, null, "module.json", null, 0, false, emptySet(), "Модули", "Модуль"))
        val hosts = declarations().sortedBy { it.first }
        for ((host, described) in hosts) {
            if (!ID.matches(host)) continue
            val list = described.optJSONArray("hosts") ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val name = item.optString("kind")
                val descriptor = item.optString("descriptor")
                val engine = item.optString("engine").ifEmpty { null }
                // `module` and `mod` are the shell's own: no module can take them.
                if (!NAME.matches(name) || name in found || name == StoreFormat.MOD || !DESCRIPTOR.matches(descriptor)) continue
                if (engine != null && (!ENGINE.matches(engine) || engine in setOf("axikoApi", "minAxikoApi") || item.optInt("version") < 1)) continue
                val reserved = item.optJSONArray("reserved")?.let { names -> (0 until names.length()).mapNotNull { names.optString(it).ifEmpty { null } } } ?: emptyList()
                found[name] = StoreKind(name, host, descriptor, engine, if (engine == null) 0 else item.optInt("version"), item.optBoolean("screen"),
                    reserved.toSet(), item.optString("title").ifEmpty { name }.take(60), item.optString("item").ifEmpty { name }.take(60))
            }
        }
        // A package may not take the place of a module that hosts others, nor of the store itself.
        val taken = found.values.mapNotNull { it.host }.toSet() + PROTECTED
        found[MODULE] = StoreKind(MODULE, null, "module.json", null, 0, false, taken, "Модули", "Модуль")
        return found
    }

    fun find(name: String): StoreKind? = all()[name]

    fun require(name: String): StoreKind = find(name)
        ?: throw IllegalArgumentException("Для пакетов вида «$name» нет модуля-хозяина. Установите модуль, который их принимает.")

    /** What a package says about the host's rules must be what the installed host offers. Null when it fits. */
    fun mismatch(name: String, runtime: JSONObject, known: Map<String, StoreKind> = all()): String? {
        val kind = known[name] ?: return "Для пакетов вида «$name» нет модуля-хозяина. Установите модуль, который их принимает."
        val key = kind.engine ?: return null
        if (!runtime.has(key)) return "Пакет не называет версию правил «${kind.title}» ($key)."
        val wanted = runtime.optInt(key)
        return if (wanted == kind.version) null else "Пакету нужны правила «${kind.title}» версии $wanted; установлена ${kind.version}."
    }

    companion object {
        const val MODULE = "module"
        private val PROTECTED = setOf("store", "template")
        private val ID = Regex("[a-z0-9][a-z0-9.-]{0,95}")
        private val NAME = Regex("[a-z][a-z0-9-]{0,31}")
        private val DESCRIPTOR = Regex("[a-z][a-z0-9-]{0,31}\\.json")
        private val ENGINE = Regex("[a-z][A-Za-z0-9]{0,31}")

        /**
         * Declarations of the modules in a workspace. [factory] gives those the application ships
         * with: a host whose own `module.json` was edited and lost its `hosts` still hosts what the
         * factory copy says. A host that is not in the workspace hosts nothing.
         */
        fun workspace(workspace: File, factory: () -> List<Pair<String, JSONObject>> = { emptyList() }): StoreKinds = StoreKinds {
            val shipped = runCatching { factory() }.getOrDefault(emptyList()).toMap()
            File(workspace, "modules").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
                val own = runCatching { JSONObject(File(folder, "module.json").readText()) }.getOrNull()
                val described = own?.takeIf { it.has("hosts") } ?: shipped[folder.name] ?: own
                described?.let { folder.name to it }
            }
        }
    }
}
