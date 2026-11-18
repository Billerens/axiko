package app.axiko

import app.axiko.store.StoreFormat
import app.axiko.store.StoreInstaller
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

/**
 * Opt-in release validation: set AXIKO_STORE_CATALOG_DIST to a local builder output directory, and
 * AXIKO_STORE_SOURCE to the store's sources to have the pictures the catalog names checked as well.
 */
class StoreCatalogDistributionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun everyRealCatalogArchivePassesTheActualInstallerAndPreservesData() {
        val configured = System.getenv("AXIKO_STORE_CATALOG_DIST")
        assumeTrue("Set AXIKO_STORE_CATALOG_DIST for local release validation", !configured.isNullOrBlank())
        val distribution = File(configured!!).canonicalFile
        assertTrue("Catalog distribution must exist", distribution.isDirectory)
        val validationFile = File(distribution, "catalog-validation.json")
        validationFile.delete()
        val indexBytes = File(distribution, "index.json").readBytes()
        // The head names the pages; each page must be the file the head pins, and an ID must not repeat across them.
        val index = StoreFormat.index(indexBytes)
        val entries = JSONArray(); val ids = HashSet<String>()
        val pages = index.getJSONArray("pages")
        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i); val raw = File(distribution, page.getString("path")).readBytes()
            assertEquals("Page ${page.getString("path")} differs from the head", page.getString("sha256"), StoreFormat.sha256(raw))
            assertEquals(page.getLong("size"), raw.size.toLong())
            val cards = StoreFormat.page(raw).getJSONArray("packages")
            assertEquals(page.getInt("count"), cards.length())
            for (n in 0 until cards.length()) {
                val card = cards.getJSONObject(n)
                assertTrue("Package ${card.getString("id")} is on two pages", ids.add(card.getString("id")))
                // What a card points at is published next to the head, by its hash: the details here, pictures in the store itself.
                card.optJSONObject("details")?.let { ref ->
                    val details = File(distribution, ref.getString("path")).readBytes()
                    assertEquals(ref.getString("sha256"), StoreFormat.sha256(details)); assertEquals(ref.getLong("size"), details.size.toLong())
                    val parsed = StoreFormat.details(details)
                    assertEquals(card.getString("id"), parsed.getString("id")); assertEquals(card.getString("version"), parsed.getString("version"))
                }
                System.getenv("AXIKO_STORE_SOURCE")?.takeIf { it.isNotBlank() }?.let { source ->
                    val pictures = listOfNotNull(card.optJSONObject("icon")) + (card.optJSONObject("details")?.let { ref ->
                        StoreFormat.details(File(distribution, ref.getString("path")).readBytes()).optJSONArray("screenshots")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
                    } ?: emptyList())
                    for (picture in pictures) {
                        val bytes = File(source, picture.getString("path")).readBytes()
                        assertEquals("Picture ${picture.getString("path")} differs from the catalog", picture.getString("sha256"), StoreFormat.sha256(bytes))
                        assertEquals(picture.getString("type"), StoreFormat.pictureType(bytes))
                    }
                }
                entries.put(card)
            }
        }
        assertEquals(index.getInt("packageCount"), entries.length())
        assertTrue("Catalog must not be empty", entries.length() > 0)
        val workspace = temp.newFolder("workspace")
        val sentinel = File(workspace, "data/owner-save.json").apply {
            parentFile.mkdirs(); writeText("{\"ownerProgress\":12345}")
        }
        var snapshots = 0
        // The kinds of the catalog's packages are declared by the modules the application ships with, as on a device.
        val factory = listOf(File("src/main/assets/factory/modules"), File("app/src/main/assets/factory/modules")).first { it.isDirectory }
        factory.listFiles().orEmpty().filter { File(it, "module.json").isFile }.forEach { module ->
            File(workspace, "modules/${module.name}").mkdirs(); File(module, "module.json").copyTo(File(workspace, "modules/${module.name}/module.json"))
        }
        val installer = StoreInstaller(workspace, File(temp.root, "state/store"), { snapshots++; "release-check-$snapshots" })
        val verified = JSONArray()
        // A package that did not change keeps its published release: the build has no new archive of it to check.
        val notRebuilt = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val archiveName = File(URI(entry.getJSONObject("archive").getString("url")).path).name
            val manifestName = File(URI(entry.getString("manifestUrl")).path).name
            val archive = File(distribution, archiveName)
            if (!archive.exists() && !File(distribution, manifestName).exists()) {
                notRebuilt.put(JSONObject().put("id", entry.getString("id")).put("version", entry.getString("version"))); continue
            }
            val manifest = File(distribution, manifestName).readBytes()
            try {
                val plan = installer.prepare("release-validation", entry, manifest, archive)
                assertEquals("ready", plan.getString("status"))
                assertEquals(0, plan.getJSONArray("conflicts").length())
                val result = installer.apply(plan.getString("id"), plan.getString("digest"))
                assertTrue(result.getBoolean("ok"))
                assertEquals("{\"ownerProgress\":12345}", sentinel.readText())
                verified.put(JSONObject().put("id", entry.getString("id")).put("version", entry.getString("version"))
                    .put("archiveSha256", StoreFormat.sha256(archive)).put("entryPath", plan.getString("entryPath")))
            } catch (error: Throwable) {
                throw AssertionError("Package ${entry.getString("id")} failed actual installer validation: ${error.message}", error)
            }
        }
        // A build that only changed listings has no new archive; the catalog itself was checked above.
        assertEquals(verified.length(), snapshots)
        assertEquals(verified.length(), installer.installed().length())
        validationFile.writeText(JSONObject().put("ok", true).put("scope", "actual-kotlin-installer")
            .put("indexSha256", StoreFormat.sha256(indexBytes)).put("packageCount", entries.length())
            .put("packageCodeExecuted", false).put("dataPreserved", true).put("packages", verified)
            .put("notRebuilt", notRebuilt).toString(2) + "\n")
    }
}
