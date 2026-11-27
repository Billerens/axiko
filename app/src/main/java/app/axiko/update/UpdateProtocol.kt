package app.axiko.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val RELEASES = "https://github.com/Billerens/axiko/releases"
private const val LATEST = "https://api.github.com/repos/Billerens/axiko/releases/latest"
private const val MAX_APK_BYTES = 512L * 1024 * 1024
private const val MAX_RELEASE_BYTES = 512L * 1024

internal class UpdateFailure(val code: String, cause: Throwable? = null) : Exception(code, cause)

internal data class UpdateVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int =
        compareValuesBy(this, other, UpdateVersion::major, UpdateVersion::minor, UpdateVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")

        fun parse(text: String): UpdateVersion? {
            val parts = pattern.matchEntire(text)?.groupValues ?: return null
            return UpdateVersion(
                parts[1].toLongOrNull() ?: return null,
                parts[2].toLongOrNull() ?: return null,
                parts[3].toLongOrNull() ?: return null,
            )
        }
    }
}

internal data class UpdateAsset(val url: HttpUrl, val bytes: Long, val sha256: String, val abi: String?)
internal data class UpdateRelease(val version: String, val notes: String, val url: String)
internal data class UpdateOffer(val release: UpdateRelease, val asset: UpdateAsset)
internal data class UpdateInspection(
    val status: String,
    val code: String? = null,
    val release: UpdateRelease? = null,
    val offer: UpdateOffer? = null,
)

internal object UpdateProtocol {
    private val sha256 = Regex("[0-9a-f]{64}")

    fun inspect(release: JSONObject?, installed: String, abis: List<String>, restriction: String?): UpdateInspection {
        if (restriction == "package") return UpdateInspection("unavailable", "package")
        val local = UpdateVersion.parse(installed) ?: return UpdateInspection("unavailable", "version")
        if (release == null) return UpdateInspection("unavailable", "noRelease")
        val tag = release.optString("tag_name")
        val remote = UpdateVersion.parse(tag.removePrefix("v"))
        if (remote == null || tag != "v$remote" || release.optBoolean("draft") || release.optBoolean("prerelease")) {
            throw UpdateFailure("metadata")
        }
        if (remote <= local) return UpdateInspection("current")
        val details = UpdateRelease(remote.toString(), release.optString("body", "").take(96_000), "$RELEASES/tag/$tag")
        val assets = release.optJSONArray("assets") ?: throw UpdateFailure("metadata")
        val choices = (0 until assets.length()).mapNotNull(assets::optJSONObject)
        if (restriction != null) return UpdateInspection("unavailable", restriction, details)
        for (abi in abis + "universal") {
            val name = "axiko-$remote-$abi.apk"
            val matches = choices.filter { it.optString("name") == name }
            if (matches.isEmpty()) continue
            val asset = matches.singleOrNull() ?: throw UpdateFailure("metadata")
            val url = "$RELEASES/download/$tag/$name"
            val size = (asset.opt("size") as? Number)?.toString()?.toLongOrNull()
            val digest = asset.optString("digest").removePrefix("sha256:")
            if (asset.optString("browser_download_url") != url || size == null || size !in 1..MAX_APK_BYTES ||
                !asset.optString("digest").startsWith("sha256:") || !sha256.matches(digest)
            ) throw UpdateFailure("metadata")
            return UpdateInspection("available", release = details, offer = UpdateOffer(
                details, UpdateAsset(url.toHttpUrl(), size, digest, abi.takeUnless { it == "universal" }),
            ))
        }
        return UpdateInspection("unavailable", "abi", details)
    }
}

internal interface UpdateSource {
    fun latest(): JSONObject?
    fun openApk(asset: UpdateAsset): InputStream
}

internal class GitHubUpdateSource : UpdateSource {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    override fun latest(): JSONObject? {
        val request = request(LATEST.toHttpUrl()).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw UpdateFailure("network")
            val body = response.body?.source() ?: throw UpdateFailure("metadata")
            if (body.request(MAX_RELEASE_BYTES + 1)) throw UpdateFailure("metadata")
            return try {
                JSONObject(body.readUtf8())
            } catch (badJson: JSONException) {
                throw UpdateFailure("metadata", badJson)
            }
        }
    }

    override fun openApk(asset: UpdateAsset): InputStream {
        var url = asset.url
        repeat(5) {
            val response = client.newCall(request(url).build()).execute()
            if (!response.isRedirect) return downloadBody(response, asset.bytes)
            val next = response.use { it.header("Location")?.let(url::resolve) }
                ?: throw UpdateFailure("network")
            if (!trustedDownload(next)) throw UpdateFailure("metadata")
            url = next
        }
        throw UpdateFailure("network")
    }

    private fun downloadBody(response: Response, size: Long): InputStream {
        val body = response.body
        if (response.code != 200 || body == null) {
            response.close()
            throw UpdateFailure("network")
        }
        if (body.contentLength() >= 0 && body.contentLength() != size) {
            response.close()
            throw UpdateFailure("hash")
        }
        // Closing the stream closes the response body and releases the connection.
        return body.byteStream()
    }

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("User-Agent", "Axiko-Update")

    private fun trustedDownload(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}
