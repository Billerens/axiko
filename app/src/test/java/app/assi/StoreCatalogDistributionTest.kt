package app.assi

import app.assi.store.StoreFormat
import app.assi.store.StoreInstaller
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

/** Opt-in release validation: set ASSI_STORE_CATALOG_DIST to a local builder output directory. */
class StoreCatalogDistributionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun everyRealCatalogArchivePassesTheActualInstallerAndPreservesData() {
        val configured = System.getenv("ASSI_STORE_CATALOG_DIST")
        assumeTrue("Set ASSI_STORE_CATALOG_DIST for local release validation", !configured.isNullOrBlank())
        val distribution = File(configured!!).canonicalFile
        assertTrue("Catalog distribution must exist", distribution.isDirectory)
        val validationFile = File(distribution, "catalog-validation.json")
        validationFile.delete()
        val indexBytes = File(distribution, "index.json").readBytes()
        val index = StoreFormat.index(indexBytes)
        val entries = index.getJSONArray("packages")
        assertTrue("Catalog must not be empty", entries.length() > 0)
        val workspace = temp.newFolder("workspace")
        val sentinel = File(workspace, "data/owner-save.json").apply {
            parentFile.mkdirs(); writeText("{\"ownerProgress\":12345}")
        }
        var snapshots = 0
        val installer = StoreInstaller(workspace, File(temp.root, "state/store"), { snapshots++; "release-check-$snapshots" })
        val verified = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val archiveName = File(URI(entry.getJSONObject("archive").getString("url")).path).name
            val manifestName = File(URI(entry.getString("manifestUrl")).path).name
            val archive = File(distribution, archiveName)
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
        assertEquals(entries.length(), snapshots)
        assertEquals(entries.length(), installer.installed().length())
        validationFile.writeText(JSONObject().put("ok", true).put("scope", "actual-kotlin-installer")
            .put("indexSha256", StoreFormat.sha256(indexBytes)).put("packageCount", entries.length())
            .put("packageCodeExecuted", false).put("dataPreserved", true).put("packages", verified).toString(2) + "\n")
    }
}
