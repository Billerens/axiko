package app.axiko

import app.axiko.update.AppUpdates
import app.axiko.update.UpdateFailure
import app.axiko.update.UpdateProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test fun stableVersionsAreComparedNumerically() {
        assertEquals("current", check("0.3.1", "0.3.1").status)
        assertEquals("current", check("0.9.9", "0.10.0").status)
        assertEquals("available", check("0.10.0", "0.9.9", asset("0.10.0", "x86_64")).status)
    }

    @Test fun matchingAbiTakesPrecedenceOverUniversal() {
        val found = check("0.3.2", "0.3.1", asset("0.3.2", "universal"), asset("0.3.2", "x86_64"))
        assertEquals("x86_64", found.offer?.asset?.abi)
        assertEquals(42L, found.offer?.asset?.bytes)
        assertEquals("## English\nHello", found.release?.notes)
        assertEquals("https://github.com/Billerens/axiko/releases/tag/v0.3.2", found.release?.url)

        val universal = check("0.3.2", "0.3.1", asset("0.3.2", "arm64-v8a"), asset("0.3.2", "universal"))
        assertEquals("available", universal.status)
        assertNull(universal.offer?.asset?.abi)
        assertEquals("abi", check("0.3.2", "0.3.1", asset("0.3.2", "arm64-v8a")).code)
    }

    @Test fun unstableReleaseAndUntrustedOrIncompleteAssetMetadataAreRejected() {
        fun fails(release: JSONObject) {
            val error = assertThrows(UpdateFailure::class.java) {
                UpdateProtocol.inspect(release, "0.3.1", listOf("x86_64"), null)
            }
            assertEquals("metadata", error.code)
        }
        val normal = asset("0.3.2", "x86_64")
        fails(release("0.3.2", normal).put("prerelease", true))
        fails(release("0.3.2", normal).put("draft", true))
        fails(release("0.3.2-rc.1", normal))
        fails(release("0.3.2", normal, normal))
        fails(release("0.3.2", asset("0.3.2", "x86_64").put("size", 0)))
        fails(release("0.3.2", asset("0.3.2", "x86_64").put("digest", "a".repeat(64))))
        fails(release("0.3.2", asset("0.3.2", "x86_64").put("browser_download_url", "https://example.org/update.apk")))
    }

    @Test fun unsupportedInstalledSignerCanSeeNotesWithoutAnInstallOffer() {
        val found = UpdateProtocol.inspect(release("0.3.2", asset("0.3.2", "x86_64")), "0.3.1", listOf("x86_64"), "signer")
        assertEquals("unavailable", found.status)
        assertEquals("signer", found.code)
        assertEquals("0.3.2", found.release?.version)
        assertNull(found.offer)
    }

    @Test fun automaticCheckUsesRollingDayAndRecoversFromClockRollback() {
        val last = 1780000000000L
        val day = 86_400_000L
        assertTrue(AppUpdates.checkDue(0, last))
        assertFalse(AppUpdates.checkDue(last, last + day - 1))
        assertTrue(AppUpdates.checkDue(last, last + day))
        assertTrue(AppUpdates.checkDue(last, last - 1))
    }

    private fun check(remote: String, installed: String, vararg assets: JSONObject) =
        UpdateProtocol.inspect(release(remote, *assets), installed, listOf("x86_64"), null)

    private fun release(version: String, vararg assets: JSONObject) = JSONObject()
        .put("tag_name", "v$version").put("draft", false).put("prerelease", false)
        .put("body", "## English\nHello").put("assets", JSONArray(assets.toList()))

    private fun asset(version: String, abi: String): JSONObject {
        val name = "axiko-$version-$abi.apk"
        return JSONObject().put("name", name).put("size", 42).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/Billerens/axiko/releases/download/v$version/$name")
    }
}
