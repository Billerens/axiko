package app.axiko.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A rule the installer itself saw broken in an authentic archive: the release is banned, no model is asked. */
class StoreViolation(val rule: String, val path: String, message: String) : IllegalArgumentException(message)

/** The index, the manifest and the archive do not agree: the source's fault, not a reason to ban the package. */
class StoreSourceFault(message: String) : IllegalArgumentException(message)

/** A banned release was asked for again. */
class StoreBanned(val ban: JSONObject, message: String = StoreBans.refusal(ban)) : IllegalArgumentException(message)

/**
 * Releases that broke the rules, by the SHA-256 of their archive. The list lives in the shell's
 * state next to the installer's registry, outside the workspace: a package or an edited screen
 * cannot rewrite it. A banned archive is not prepared, reviewed again or installed by anyone;
 * another version of the package is another archive and is reviewed from the start.
 */
class StoreBans(stateDir: File) {
    private val file = File(stateDir, "bans.json")

    private fun root(): JSONObject {
        if (!file.exists()) return JSONObject().put("version", 1).put("bans", JSONArray()).put("lifted", JSONArray())
        // A list that cannot be read is not an empty list: nothing is installed until it is mended.
        val root = try { StoreDisk.read(file) } catch (error: Exception) { throw IllegalStateException("Список банов магазина повреждён; установка остановлена.", error) }
        check(root.optInt("version") == 1 && root.optJSONArray("bans") != null) { "Неподдерживаемый список банов магазина." }
        return root
    }

    private fun rows(root: JSONObject, key: String = "bans"): List<JSONObject> =
        (root.optJSONArray(key) ?: JSONArray()).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }

    /** The bans in force, the latest first. */
    @Synchronized fun all(): List<JSONObject> = rows(root()).map(StoreDisk::copy).sortedByDescending { it.optLong("bannedAt") }

    @Synchronized fun list(): JSONArray = JSONArray(all())

    @Synchronized fun find(sha256: String): JSONObject? = rows(root()).firstOrNull { it.getString("artifactSha256") == sha256 }?.let(StoreDisk::copy)

    /** The first ban of an archive stands: a later report cannot rewrite why it was banned. */
    @Synchronized fun add(record: JSONObject): JSONObject {
        StoreFormat.hash(record.getString("artifactSha256"))
        val root = root()
        rows(root).firstOrNull { it.getString("artifactSha256") == record.getString("artifactSha256") }?.let { return StoreDisk.copy(it) }
        val saved = StoreDisk.copy(record)
        if (!saved.has("bannedAt")) saved.put("bannedAt", System.currentTimeMillis())
        root.getJSONArray("bans").put(saved)
        StoreDisk.write(file, root)
        return StoreDisk.copy(saved)
    }

    /** The owner's decision only; what was lifted stays on record. */
    @Synchronized fun lift(sha256: String): JSONObject {
        val root = root()
        val ban = rows(root).firstOrNull { it.getString("artifactSha256") == sha256 } ?: throw IllegalArgumentException("Этот выпуск не забанен.")
        val lifted = rows(root, "lifted").takeLast(99) + StoreDisk.copy(ban).put("liftedAt", System.currentTimeMillis())
        root.put("bans", JSONArray(rows(root).filter { it !== ban })).put("lifted", JSONArray(lifted))
        StoreDisk.write(file, root)
        return StoreDisk.copy(ban)
    }

    companion object {
        /** Bans of other releases of the same package of the same repository, the latest first. */
        fun earlier(bans: List<JSONObject>, packageId: String, sourceId: String, repositoryId: String, sha256: String): JSONArray = JSONArray(
            bans.filter { ban ->
                ban.optString("packageId") == packageId && ban.optString("artifactSha256") != sha256 &&
                    (ban.optString("sourceId") == sourceId || repositoryId.isNotEmpty() && ban.optString("repositoryId") == repositoryId)
            }.map(::brief),
        )

        /** What a catalog card and a plan show of a ban. */
        fun brief(ban: JSONObject): JSONObject = JSONObject().put("artifactSha256", ban.optString("artifactSha256"))
            .put("packageId", ban.optString("packageId")).put("title", ban.optString("title")).put("version", ban.optString("version"))
            .put("sourceTitle", ban.optString("sourceTitle")).put("bannedAt", ban.optLong("bannedAt")).put("origin", ban.optString("origin"))
            .put("reason", reason(ban)).put("violations", ban.optJSONArray("violations") ?: JSONArray())

        /** One line for an error or a list: the rules broken, by their titles. */
        fun reason(ban: JSONObject): String {
            val violations = ban.optJSONArray("violations") ?: JSONArray()
            val titles = (0 until violations.length()).map { violations.getJSONObject(it).optString("title") }.filter { it.isNotEmpty() }.distinct()
            return titles.joinToString("; ").ifEmpty { ban.optString("summary") }
        }

        fun refusal(ban: JSONObject): String =
            "Выпуск «${ban.optString("title").ifEmpty { ban.optString("packageId") }}» ${ban.optString("version")} забанен за нарушение правил магазина: ${reason(ban)}. " +
                "Снять бан может только владелец на экране магазина, в разделе «Источники»."
    }
}
