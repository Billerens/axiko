package app.axiko.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.axiko.R
import app.axiko.core.Pages
import app.axiko.store.StoreFormat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** Checks releases and prepares verified APKs. Installation belongs to the foreground shell. */
internal class AppUpdates(
    context: Context,
    private val source: UpdateSource,
    private val expectedSignerSha256: String,
    private val now: () -> Long = System::currentTimeMillis,
    supportedPackage: String = "app.axiko",
    private val publish: (JSONObject) -> Unit = { Pages.broadcast("update.changed", it.toString()) },
) {
    private val app = context.applicationContext
    private val daemon = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-app-update").apply { isDaemon = true }
    }
    private val lock = Any()
    private val prefs = app.getSharedPreferences("axiko.app-updates", Context.MODE_PRIVATE)
    private val downloadDir = File(app.cacheDir, "app-updates")
    @Suppress("DEPRECATION")
    private val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    private val installedVersion = installed.versionName.orEmpty()
    private val abis = Build.SUPPORTED_ABIS.toList()
    private val restriction = when {
        installed.packageName != supportedPackage -> "package"
        UpdateVersion.parse(installedVersion) == null -> "version"
        !hasExpectedSigner(installed) -> "signer"
        else -> null
    }
    private var lastAttempt = prefs.getLong("lastAttempt", 0)
    private var failure = prefs.getString("failure", null)
    private var inspection = prefs.getString("release", null)?.let { saved ->
        runCatching { inspect(if (saved.isEmpty()) null else JSONObject(saved)) }.getOrNull()
    } ?: when (restriction) {
        "package", "version", "signer" -> UpdateInspection("unavailable", restriction)
        else -> UpdateInspection("idle")
    }
    private val checkListeners = mutableListOf<(JSONObject) -> Unit>()
    private var checking = false
    private var downloading = false
    private var downloadFailure: String? = null

    fun state(): JSONObject = synchronized(lock) {
        val offer = inspection.offer
        val status = when {
            checking -> "checking"
            downloading -> "downloading"
            failure != null -> "error"
            else -> inspection.status
        }
        JSONObject().put("status", status).put("installedVersion", installedVersion)
            .put("canInstall", offer != null && !checking && !downloading)
            .put("downloaded", offer != null && fileFor(offer).length() == offer.asset.bytes)
            .put("code", failure ?: inspection.code)
            .put("downloadError", downloadFailure)
            .apply {
                if (lastAttempt > 0) put("checkedAt", lastAttempt)
                inspection.release?.let {
                    put("version", it.version).put("notes", it.notes).put("url", it.url)
                }
                offer?.let { put("bytes", it.asset.bytes) }
            }
    }

    /** A check started by the user skips the daily cooldown and joins an ongoing check. */
    fun check(manual: Boolean, onComplete: ((JSONObject) -> Unit)? = null) {
        val started = synchronized(lock) {
            if (checking) {
                if (onComplete != null) checkListeners.add(onComplete)
                return
            }
            val current = now()
            if (downloading || restriction == "package" || restriction == "version" ||
                (!manual && !checkDue(lastAttempt, current))
            ) false else {
                checking = true
                if (onComplete != null) checkListeners.add(onComplete)
                lastAttempt = current
                prefs.edit().putLong("lastAttempt", current).apply()
                true
            }
        }
        if (!started) {
            onComplete?.invoke(state())
            return
        }
        announce(state())
        daemon.execute {
            val updated = runCatching {
                val release = source.latest()
                inspect(release) to release?.toString().orEmpty()
            }
            val listeners = synchronized(lock) {
                updated.fold(
                    onSuccess = { (answer, release) ->
                        inspection = answer
                        downloadFailure = null
                        failure = null
                        prefs.edit().putString("release", release).remove("failure").apply()
                    },
                    onFailure = { error ->
                        failure = (error as? UpdateFailure)?.code ?: "network"
                        prefs.edit().putString("failure", failure).apply()
                    },
                )
                checking = false
                checkListeners.toList().also { checkListeners.clear() }
            }
            val snapshot = state()
            announce(snapshot)
            listeners.forEach { listener -> runCatching { listener(snapshot) } }
        }
    }

    fun prepare(version: String, onComplete: (Result<File>) -> Unit) {
        val prepared = synchronized(lock) {
            val current = inspection.offer
            when {
                checking || downloading -> Result.failure(UpdateFailure("busy"))
                current == null || current.release.version != version -> Result.failure(UpdateFailure("stale"))
                else -> {
                    downloading = true
                    downloadFailure = null
                    Result.success(current)
                }
            }
        }
        val offer = prepared.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        announce(state())
        daemon.execute {
            val result = runCatching { download(offer) }.recoverCatching { error ->
                throw if (error is UpdateFailure) error else UpdateFailure("storage", error)
            }
            synchronized(lock) {
                downloading = false
                downloadFailure = (result.exceptionOrNull() as? UpdateFailure)?.code
            }
            announce(state())
            onComplete(result)
        }
    }

    private fun announce(state: JSONObject) {
        runCatching { publish(state) }
    }

    private fun inspect(release: JSONObject?): UpdateInspection =
        UpdateProtocol.inspect(release, installedVersion, abis, restriction)

    private fun fileFor(offer: UpdateOffer): File = File(downloadDir, "${offer.asset.sha256}.apk")

    private fun download(offer: UpdateOffer): File {
        val asset = offer.asset
        if (!downloadDir.isDirectory && !downloadDir.mkdirs()) throw UpdateFailure("storage")
        val cached = fileFor(offer)
        if (cached.isFile) {
            if (cached.length() == asset.bytes && StoreFormat.sha256(cached) == asset.sha256) {
                verifyApk(cached, offer)
                return cached
            }
            cached.delete()
        }
        val temp = File.createTempFile("release-", ".apk", downloadDir)
        try {
            downloadTo(temp, asset)
            verifyApk(temp, offer)
            if (!temp.renameTo(cached)) throw UpdateFailure("storage")
            downloadDir.listFiles()?.filter { it != cached }?.forEach(File::delete)
            return cached
        } finally {
            temp.delete()
        }
    }

    private fun downloadTo(file: File, asset: UpdateAsset) {
        val hash = MessageDigest.getInstance("SHA-256")
        val input = try {
            source.openApk(asset)
        } catch (error: IOException) {
            throw UpdateFailure("network", error)
        }
        var size = 0L
        input.use { incoming ->
            file.outputStream().buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = try {
                        incoming.read(buffer)
                    } catch (error: IOException) {
                        throw UpdateFailure("network", error)
                    }
                    if (count < 0) break
                    size += count
                    if (size > asset.bytes) throw UpdateFailure("hash")
                    hash.update(buffer, 0, count)
                    out.write(buffer, 0, count)
                }
            }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        if (size != asset.bytes || digest != asset.sha256) throw UpdateFailure("hash")
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, offer: UpdateOffer) {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateFailure("apk")
        if (info.packageName != installed.packageName) throw UpdateFailure("package")
        if (!hasExpectedSigner(info)) throw UpdateFailure("signer")
        if (info.versionName != offer.release.version || info.longVersionCode <= installed.longVersionCode ||
            (info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT
        ) throw UpdateFailure("version")
        val packagedAbis = ZipFile(file).use { apk ->
            apk.entries().asSequence().mapNotNull { entry ->
                entry.name.takeIf { it.startsWith("lib/") && it.endsWith(".so") }
                    ?.substringAfter("lib/")?.substringBefore('/')
            }.toSet()
        }
        if (abis.none { it in packagedAbis } || offer.asset.abi?.let { it !in packagedAbis } == true) {
            throw UpdateFailure("abi")
        }
    }

    private fun hasExpectedSigner(info: PackageInfo): Boolean =
        info.signingInfo?.apkContentsSigners?.singleOrNull()?.let { signer ->
            StoreFormat.sha256(signer.toByteArray()) == expectedSignerSha256
        } == true

    companion object {
        @Volatile private var instance: AppUpdates? = null
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun get(context: Context): AppUpdates = instance ?: synchronized(this) {
            instance ?: AppUpdates(context, GitHubUpdateSource(), context.getString(R.string.update_signer_sha256))
                .also { instance = it }
        }

        internal fun checkDue(lastAttempt: Long, now: Long): Boolean =
            lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= DAY_MS
    }
}
