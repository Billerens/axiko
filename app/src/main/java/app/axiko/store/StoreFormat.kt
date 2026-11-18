package app.axiko.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Metadata policy shared by source discovery and installation. Nothing here executes package code. */
object StoreFormat {
    const val MAX_METADATA = 1_048_576
    const val MAX_ARCHIVE = 5_242_880
    const val MAX_UNPACKED = 20_971_520
    const val MAX_FILE = 2_097_152
    const val MAX_FILES = 512
    const val MAX_PAGES = 256
    const val MAX_PAGE_PACKAGES = 2000
    const val MAX_PACKAGES = 20_000
    const val MAX_DETAILS = 262_144
    const val MAX_ICON = 131_072
    const val MAX_SCREENSHOT = 1_048_576
    private val iconTypes = setOf("image/svg+xml", "image/png", "image/webp", "image/jpeg")
    private val screenshotTypes = setOf("image/png", "image/webp", "image/jpeg")
    /** A change to another package, carried over to the owner's version of it; the shell's own kind, like `module`. */
    const val MOD = "mod"
    /** The languages one listing may be given in besides English. */
    const val MAX_LOCALES = 16
    private val languagePattern = Regex("[a-z]{2,3}(?:-[A-Za-z]{2,4})?")
    private val kindPattern = Regex("[a-z][a-z0-9-]{0,31}")
    private val enginePattern = Regex("[a-z][A-Za-z0-9]{0,31}")
    private val datePattern = Regex("[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])")
    const val AXIKO_API = 1
    private val idPattern = Regex("[a-z0-9][a-z0-9.-]{0,95}")
    private val semver = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val forbiddenName = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")

    fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Нужен корректный UTF-8 без повреждённых символов.", error)
    }

    fun parse(bytes: ByteArray, limit: Int = MAX_METADATA): JSONObject {
        require(bytes.size <= limit) { "Метаданные превышают лимит $limit байт." }
        require(!(bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte())) { "Нужен UTF-8 без BOM." }
        return StrictJson(utf8(bytes)).parse() as? JSONObject ?: error("Корень JSON должен быть объектом.")
    }

    /**
     * The head of a catalog: who the repository is and which pages hold its packages. The packages
     * themselves are in the pages, so a large catalog is never one file (docs/marketplace/catalog-pages.md).
     */
    fun index(bytes: ByteArray): JSONObject = parse(bytes).also { value ->
        listed(value, setOf("schemaVersion", "repository", "packageCount", "pages"))
        require(integer(value, "schemaVersion", 1, 1) == 1L)
        val repository = obj(value, "repository")
        listed(repository, setOf("id", "title", "homepage"))
        id(str(repository, "id", 96)); str(repository, "title", 120); https(str(repository, "homepage", 2048))
        if (repository.has("description")) str(repository, "description", 600)
        val total = integer(value, "packageCount", 0, MAX_PACKAGES.toLong())
        val pages = array(value, "pages", MAX_PAGES)
        val paths = ArrayList<String>(); var counted = 0L
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: error("pages[$i] должен быть объектом.")
            listed(page, setOf("path", "sha256", "size", "count"))
            paths.add(path(str(page, "path", 240)).also { require(it.endsWith(".json")) { "Страница каталога должна быть файлом .json." } })
            hash(str(page, "sha256", 64)); integer(page, "size", 2, MAX_METADATA.toLong())
            counted += integer(page, "count", 1, MAX_PAGE_PACKAGES.toLong())
        }
        uniquePaths(paths)
        require(counted == total) { "Число пакетов на страницах не совпадает с packageCount." }
    }

    /** One page of a catalog: cards of packages. Whether an ID repeats on another page is the reader's to check. */
    fun page(bytes: ByteArray): JSONObject = parse(bytes).also { value ->
        listed(value, setOf("schemaVersion", "packages"))
        require(integer(value, "schemaVersion", 1, 1) == 1L)
        val packages = array(value, "packages", MAX_PAGE_PACKAGES)
        val ids = HashSet<String>()
        for (i in 0 until packages.length()) {
            val p = packages.optJSONObject(i) ?: error("packages[$i] должен быть объектом.")
            packageEntry(p)
            require(ids.add(p.getString("id"))) { "Повторяющийся ID пакета: ${p.getString("id")}." }
        }
    }

    /** A card: what the list shows and what an installation is decided by. Long texts and screenshots are in [details]. */
    fun packageEntry(value: JSONObject): JSONObject = value.also {
        listed(it, setOf("id", "kind", "title", "summary", "version", "publisher", "license", "manifestUrl", "archive", "compatibility"))
        // A mod is a change to another package and names it; nothing else may.
        require(it.has("extends") == (it.optString("kind") == MOD)) { "Поле extends есть у мода и только у него." }
        if (it.has("extends")) require(id(str(it, "extends", 96)) != it.optString("id")) { "Мод не может менять сам себя." }
        identity(it); str(it, "title", 120); str(it, "summary", 600); https(str(it, "manifestUrl", 2048))
        val archive = obj(it, "archive")
        listed(archive, setOf("url", "sha256", "size", "unpackedSize", "fileCount"))
        https(str(archive, "url", 2048)); hash(str(archive, "sha256", 64))
        integer(archive, "size", 1, MAX_ARCHIVE.toLong()); integer(archive, "unpackedSize", 1, MAX_UNPACKED.toLong()); integer(archive, "fileCount", 4, MAX_FILES.toLong())
        val compatibility = obj(it, "compatibility")
        versions(compatibility, "minAxikoApi")
        if (it.has("tags")) strings(it, "tags", 12, 32).forEach { tag -> require(tag == tag.lowercase(Locale.ROOT) && tag.trim() == tag && tag.none { c -> c.code < 32 }) { "Тег должен быть строчным, без пробелов по краям." } }
        if (it.has("locales")) cardLocales(obj(it, "locales"), it.optJSONArray("tags")?.length() ?: 0)
        if (it.has("icon")) media(obj(it, "icon"), MAX_ICON, iconTypes)
        if (it.has("updated")) require(datePattern.matches(str(it, "updated", 10))) { "Дата выпуска должна иметь вид ГГГГ-ММ-ДД." }
        if (it.has("details")) {
            val details = obj(it, "details")
            listed(details, setOf("path", "sha256", "size"))
            require(path(str(details, "path", 240)).endsWith(".json")) { "Подробности пакета должны быть файлом .json." }
            hash(str(details, "sha256", 64)); integer(details, "size", 2, MAX_DETAILS.toLong())
        }
    }

    /** The languages a listing is also given in: codes like `ru` or `pt-BR`; English is the base text and is not one of them. */
    private fun languages(locales: JSONObject) {
        require(locales.length() <= MAX_LOCALES) { "Слишком много языков описания: не больше $MAX_LOCALES." }
        locales.keys().forEach { code -> require(languagePattern.matches(code) && code != "en") { "Некорректный код языка описания: $code. Английский — основной текст, его языком не называют." } }
    }

    /**
     * A card's texts in other languages: only what a language translates. Its tags are the words to
     * show for the card's own tags, one for each in the same order: the tags stay what is counted and filtered by.
     */
    private fun cardLocales(locales: JSONObject, tagCount: Int) {
        languages(locales)
        locales.keys().forEach { code ->
            val shown = obj(locales, code)
            listed(shown, emptySet())
            require(shown.length() > 0) { "Язык $code не содержит ни одного текста." }
            if (shown.has("title")) str(shown, "title", 120)
            if (shown.has("summary")) str(shown, "summary", 600)
            if (shown.has("tags")) strings(shown, "tags", 12, 32).also { words ->
                require(words.size == tagCount) { "Теги языка $code: по одному слову на каждый тег карточки." }
                words.forEach { word -> require(word.trim() == word && word.none { c -> c.code < 32 || c.code == 127 }) { "Тег должен быть без пробелов по краям." } }
            }
        }
    }

    /** The description, the notes of versions and the captions of screenshots in other languages: each is a translation of something the details have in English. */
    private fun detailsLocales(locales: JSONObject, details: JSONObject) {
        languages(locales)
        val versions = details.optJSONArray("changes")?.let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("version") } } ?: emptyList()
        val shots = details.optJSONArray("screenshots")
        locales.keys().forEach { code ->
            val shown = obj(locales, code)
            listed(shown, emptySet())
            require(shown.length() > 0) { "Язык $code не содержит ни одного текста." }
            if (shown.has("description")) {
                str(shown, "description", 20_000)
                require(details.has("description")) { "Перевод описания без английского описания." }
            }
            if (shown.has("changes")) {
                val notes = obj(shown, "changes")
                require(notes.length() <= 20) { "Слишком много элементов changes." }
                notes.keys().forEach { name ->
                    require(version(name) in versions) { "Перевод изменений версии, которой нет в списке изменений: $name." }
                    str(notes, name, 4000)
                }
            }
            if (shown.has("captions")) {
                val captions = array(shown, "captions", 8)
                require(captions.length() <= (shots?.length() ?: 0)) { "Подписей больше, чем снимков экрана." }
                for (i in 0 until captions.length()) {
                    if (captions.isNull(i)) continue
                    require(captions.opt(i) is String && captions.getString(i).length in 1..200) { "Подпись снимка: от 1 до 200 знаков или null." }
                    require(shots!!.getJSONObject(i).has("caption")) { "Перевод подписи без английской подписи." }
                }
            }
        }
    }

    /** What is read when a package is opened: its description, screenshots, what changed, where to ask. */
    fun details(bytes: ByteArray): JSONObject = parse(bytes, MAX_DETAILS).also { value ->
        listed(value, setOf("schemaVersion", "id", "version"))
        require(integer(value, "schemaVersion", 1, 1) == 1L)
        id(str(value, "id", 96)); version(str(value, "version", 96))
        if (value.has("description")) str(value, "description", 20_000)
        if (value.has("screenshots")) {
            val shots = array(value, "screenshots", 8); val paths = ArrayList<String>()
            for (i in 0 until shots.length()) {
                val shot = shots.optJSONObject(i) ?: error("screenshots[$i] должен быть объектом.")
                paths.add(media(shot, MAX_SCREENSHOT, screenshotTypes, setOf("width", "height"), setOf("caption")))
                integer(shot, "width", 1, 10_000); integer(shot, "height", 1, 10_000)
                if (shot.has("caption")) str(shot, "caption", 200)
            }
            uniquePaths(paths)
        }
        if (value.has("changes")) {
            val changes = array(value, "changes", 20); val seen = HashSet<String>()
            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: error("changes[$i] должен быть объектом.")
                listed(change, setOf("version", "notes"))
                require(seen.add(version(str(change, "version", 96)))) { "Повторяющаяся версия в списке изменений." }
                str(change, "notes", 4000)
                if (change.has("date")) require(datePattern.matches(str(change, "date", 10))) { "Дата должна иметь вид ГГГГ-ММ-ДД." }
            }
        }
        if (value.has("locales")) detailsLocales(obj(value, "locales"), value)
        if (value.has("links")) {
            val links = obj(value, "links")
            listed(links, emptySet())
            links.keys().forEach { https(str(links, it, 2048)) }
        }
    }

    /**
     * Versions a package is written for: the application's API under [api], and at most one more, the
     * rules of the module that hosts packages of its kind, under the name that host gave them. Which
     * name and version a kind needs is not known here: [StoreKinds] says.
     */
    private fun versions(value: JSONObject, api: String) {
        require(value.has(api)) { "Отсутствуют обязательные поля: $api." }
        val extra = value.keys().asSequence().filter { it != api }.toList()
        require(extra.size <= 1 && extra.all { enginePattern.matches(it) && it != "axikoApi" && it != "minAxikoApi" }) { "Неизвестные поля метаданных." }
        (extra + api).forEach { integer(value, it, 1, Int.MAX_VALUE.toLong()) }
    }

    /** A picture of the listing: a path next to the index, pinned by size and hash; returns the path. */
    private fun media(value: JSONObject, max: Int, types: Set<String>, required: Set<String> = emptySet(), optional: Set<String> = emptySet()): String {
        listed(value, setOf("path", "sha256", "size", "type") + required)
        hash(str(value, "sha256", 64)); integer(value, "size", 1, max.toLong())
        require(str(value, "type", 32) in types) { "Неподдерживаемый тип картинки." }
        return path(str(value, "path", 240))
    }

    /**
     * What a downloaded picture really is, by its bytes: the declared type is the source's word only.
     * An SVG is accepted as a drawing and nothing else: no scripts, handlers or embedded pages.
     */
    fun pictureType(bytes: ByteArray): String? {
        fun at(offset: Int, vararg values: Int) = bytes.size >= offset + values.size && values.indices.all { bytes[offset + it] == values[it].toByte() }
        if (at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png"
        if (at(0, 0xff, 0xd8, 0xff)) return "image/jpeg"
        if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) return "image/webp"
        val text = runCatching { utf8(bytes) }.getOrNull() ?: return null
        if (text.any { it == '\u0000' } || !Regex("^\\uFEFF?\\s*(?:<\\?xml[^>]*>\\s*)?(?:<!--[\\s\\S]*?-->\\s*)*<svg[\\s>]").containsMatchIn(text)) return null
        if (Regex("(?i)<\\s*(?:script|foreignObject|iframe|object|embed|use\\s[^>]*href\\s*=\\s*[\"'](?!#))|\\son[a-z]+\\s*=|javascript:|<!ENTITY|<!DOCTYPE[^>]*\\[").containsMatchIn(text)) return null
        return "image/svg+xml"
    }

    fun manifest(bytes: ByteArray): JSONObject = parse(bytes).also { value ->
        // A mod runs nothing by itself: no entry. It names the package it changes and what its sample was made on.
        val mod = value.optString("kind") == MOD
        fields(value, setOf("schemaVersion", "id", "kind", "version", "publisher", "license", "sourceFormat", "runtime", "dependencies", "optionalDependencies", "capabilities", "files") +
            if (mod) setOf("extends", "reference") else setOf("entry"), setOf("networkDomains"))
        integer(value, "schemaVersion", 1, 1); identity(value)
        require(str(value, "sourceFormat", 32) == "readable") { "Пакет должен содержать читаемые исходники." }
        val entry = if (mod) "mod/brief.md" else path(str(value, "entry", 240))
        require(mod || entry.startsWith("code/")) { "Точка входа должна находиться в code/." }
        val runtime = obj(value, "runtime")
        versions(runtime, "axikoApi")
        val dependencies = HashSet<String>()
        for (field in listOf("dependencies", "optionalDependencies")) {
            val list = array(value, field, 64)
            for (i in 0 until list.length()) {
                val dependency = list.optJSONObject(i) ?: error("$field[$i] должен быть объектом.")
                fields(dependency, setOf("id", "version")); val name = id(str(dependency, "id", 96))
                require(name != value.getString("id") && dependencies.add(name)) { "Повторная зависимость или ссылка пакета на себя: $name." }
                version(str(dependency, "version", 98).removePrefix(">="))
            }
        }
        strings(value, "capabilities", 64, 96).forEach { require(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+").matches(it)) { "Некорректная возможность: $it." } }
        if (value.has("networkDomains")) strings(value, "networkDomains", 32, 253).forEach {
            require(Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it)) { "Некорректный сетевой домен." }
        }
        val files = array(value, "files", MAX_FILES - 1)
        require(files.length() >= 3) { "В описи нужны README.md, LICENSE и точка входа." }
        var total = bytes.size.toLong(); val paths = ArrayList<String>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: error("files[$i] должен быть объектом.")
            fields(file, setOf("path", "sha256", "size")); val p = path(str(file, "path", 240))
            val folders = if (mod) listOf("mod/", "reference/", "base/", "assets/") else listOf("code/", "assets/")
            require(p == "README.md" || p == "LICENSE" || folders.any(p::startsWith)) { "Файл вне разрешённой структуры: $p." }
            paths.add(p); hash(str(file, "sha256", 64)); total += integer(file, "size", 0, MAX_FILE.toLong())
        }
        uniquePaths(paths)
        require(paths.containsAll(listOf("README.md", "LICENSE", entry))) { if (mod) "В описи мода отсутствуют README.md, LICENSE или задание mod/brief.md." else "В описи отсутствуют README.md, LICENSE или точка входа." }
        require(total <= MAX_UNPACKED) { "Распакованный пакет превышает 20 МиБ." }
        if (mod) reference(value, paths)
    }

    /**
     * What a mod says about its sample must be what its files are: `reference/` holds the author's files
     * of the target, `base/` what those files were before. The version the sample was made on is
     * information for whoever carries the mod over, never a requirement.
     */
    private fun reference(value: JSONObject, paths: List<String>) {
        require(id(str(value, "extends", 96)) != value.getString("id")) { "Мод не может менять сам себя." }
        val reference = obj(value, "reference")
        fields(reference, setOf("version", "base", "added", "removed"), setOf("archiveSha256"))
        version(str(reference, "version", 96))
        if (reference.has("archiveSha256")) hash(str(reference, "archiveSha256", 64))
        val sample = paths.filter { it.startsWith("reference/") }.map { it.removePrefix("reference/") }.toSet()
        val before = paths.filter { it.startsWith("base/") }.map { it.removePrefix("base/") }.toSet()
        val base = obj(reference, "base")
        base.keys().forEach { path(it); hash(str(base, it, 64)) }
        val added = strings(reference, "added", MAX_FILES, 240).onEach { path(it) }.toSet()
        val removed = strings(reference, "removed", MAX_FILES, 240).onEach { path(it) }.toSet()
        require(base.keys().asSequence().toSet() == before) { "reference.base должен перечислять ровно файлы из base/." }
        require(added == sample - before) { "reference.added должен перечислять файлы reference/, у которых нет исходника в base/." }
        require(removed.intersect(sample).isEmpty() && (before - sample - removed).isEmpty()) { "Файл из base/ либо изменён в reference/, либо назван в removed." }
        require(sample.isNotEmpty() || removed.isNotEmpty()) { "Мод ничего не меняет." }
    }

    fun compatible(entry: JSONObject, manifest: JSONObject) {
        for (key in listOf("id", "kind", "version", "publisher", "license")) require(entry.getString(key) == manifest.getString(key)) { "Манифест не совпадает с индексом: $key." }
        require(entry.optString("extends") == manifest.optString("extends")) { "Манифест не совпадает с индексом: extends." }
        val expected = entry.getJSONObject("compatibility"); val runtime = manifest.getJSONObject("runtime")
        require(expected.getInt("minAxikoApi") == runtime.getInt("axikoApi")) { "Требования API не совпадают с индексом." }
        require(runtime.getInt("axikoApi") <= AXIKO_API) { "Нужна более новая версия API Axiko." }
        // What the card says about the rules of the package's host must be what the package itself says.
        val asked = expected.keys().asSequence().filter { it != "minAxikoApi" }.associateWith { expected.getInt(it) }
        val named = runtime.keys().asSequence().filter { it != "axikoApi" }.associateWith { runtime.getInt(it) }
        require(asked == named) { "Требования пакета к его хозяину не совпадают с индексом." }
    }

    fun id(text: String): String { require(idPattern.matches(text)) { "Некорректный идентификатор." }; return text }
    fun version(text: String): String { require(text.length <= 96 && semver.matches(text)) { "Нужна точная версия SemVer." }; return text }
    fun hash(text: String): String { require(digestPattern.matches(text)) { "Нужна контрольная сумма SHA-256." }; return text }

    fun https(text: String): String {
        require(text.length <= 2048 && text.none { it.code <= 32 || it.code == 127 } && '\\' !in text) { "Некорректный HTTPS-адрес." }
        val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException("Некорректный HTTPS-адрес.") }
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535) { "Нужен HTTPS без пароля и фрагмента в адресе." }
        return text
    }

    fun path(raw: String): String {
        require(raw.isNotEmpty() && raw.length <= 240 && !raw.startsWith('/') && '\\' !in raw && Normalizer.isNormalized(raw, Normalizer.Form.NFC)) { "Недопустимый путь пакета." }
        require(raw.split('/').none { part -> part.isEmpty() || part.startsWith('.') || part.endsWith('.') || part.endsWith(' ') || forbiddenName.matches(part) || part.any { it.code < 32 || it.code == 127 || it in "<>:\"|?*" } }) { "Недопустимый сегмент пути: $raw." }
        return raw
    }

    fun uniquePaths(paths: Collection<String>) {
        val seen = HashSet<String>(); val spelling = HashMap<String, String>()
        paths.forEach { raw ->
            val p = path(raw); val folded = p.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
            require(seen.add(folded)) { "Повторяющееся имя файла: $p." }
            val parts = p.split('/')
            for (i in parts.indices) {
                val prefix = parts.take(i + 1).joinToString("/"); val key = prefix.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
                require(spelling[key]?.let { it == prefix } != false) { "Коллизия регистра имён: $prefix." }
                spelling[key] = prefix
            }
        }
        seen.forEach { file -> require(file.split('/').indices.drop(1).none { n -> file.split('/').take(n).joinToString("/") in seen }) { "Файл используется как каталог: $file." } }
    }

    fun compareVersions(a: String, b: String): Int {
        val x = semver.matchEntire(version(a))!!; val y = semver.matchEntire(version(b))!!
        for (i in 1..3) { val c = BigInteger(x.groupValues[i]).compareTo(BigInteger(y.groupValues[i])); if (c != 0) return c }
        val xp = x.groupValues[4]; val yp = y.groupValues[4]
        if (xp.isEmpty() || yp.isEmpty()) return if (xp == yp) 0 else if (xp.isEmpty()) 1 else -1
        val xs = xp.split('.'); val ys = yp.split('.')
        for (i in 0 until minOf(xs.size, ys.size)) {
            val xn = xs[i].all(Char::isDigit); val yn = ys[i].all(Char::isDigit)
            val c = if (xn && yn) BigInteger(xs[i]).compareTo(BigInteger(ys[i])) else if (xn != yn) if (xn) -1 else 1 else xs[i].compareTo(ys[i])
            if (c != 0) return c
        }
        return xs.size.compareTo(ys.size)
    }
    fun versionMatches(actual: String, required: String): Boolean = if (required.startsWith(">=")) compareVersions(actual, required.substring(2)) >= 0 else compareVersions(actual, required) == 0

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Неизвестное значение JSON.")
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val b = ByteArray(16384); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    private fun identity(value: JSONObject) { id(str(value, "id", 96)); require(kindPattern.matches(str(value, "kind", 32))) { "Некорректный вид пакета." }; version(str(value, "version", 96)); id(str(value, "publisher", 96)); str(value, "license", 128) }
    /**
     * A structure of the catalog itself (its head, a page, a card, a listing, a picture): the fields this
     * version needs must be there, and each one it knows is checked where it is read. A field it does not
     * know is left alone: a catalog that says more than this version reads still opens, and a newer
     * catalog does not shut the store of an older app. What a package is made of is another matter: its
     * manifest is checked by [fields], which refuses anything it does not know.
     */
    private fun listed(value: JSONObject, required: Set<String>) {
        require(required.all(value::has)) { "Отсутствуют обязательные поля: ${(required - value.keys().asSequence().toSet()).sorted().joinToString()}." }
    }

    private fun fields(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) { require(required.all(value::has)) { "Отсутствуют обязательные поля: ${(required - value.keys().asSequence().toSet()).joinToString()}." }; require(value.keys().asSequence().all { it in required || it in optional }) { "Неизвестные поля метаданных." } }
    private fun str(value: JSONObject, key: String, max: Int): String = (value.opt(key) as? String)?.also { require(it.isNotEmpty() && it.length <= max) { "Недопустимая длина $key." } } ?: error("$key должен быть строкой.")
    private fun integer(value: JSONObject, key: String, min: Long, max: Long): Long { val n = value.opt(key); require(n is Number && n !is Double && n !is Float && n.toLong() in min..max) { "Некорректное целое число $key." }; return n.toLong() }
    private fun obj(value: JSONObject, key: String): JSONObject = value.optJSONObject(key) ?: error("$key должен быть объектом.")
    private fun array(value: JSONObject, key: String, max: Int): JSONArray = (value.optJSONArray(key) ?: error("$key должен быть массивом.")).also { require(it.length() <= max) { "Слишком много элементов $key." } }
    private fun strings(value: JSONObject, key: String, max: Int, length: Int): List<String> { val a = array(value, key, max); val out = (0 until a.length()).map { (a.opt(it) as? String)?.also { s -> require(s.isNotEmpty() && s.length <= length) } ?: error("$key содержит не строку.") }; require(out.toSet().size == out.size) { "Повторяющиеся элементы $key." }; return out }

    /** org.json accepts JavaScript-like syntax; downloaded metadata must be strict JSON instead. */
    private class StrictJson(private val text: String) {
        private var p = 0
        fun parse(): Any { val value = value(0); ws(); require(p == text.length) { "Лишние данные после JSON." }; return value }
        private fun ws() { while (p < text.length && text[p] in " \t\r\n") p++ }
        private fun value(depth: Int): Any {
            require(depth <= 64) { "Слишком глубокий JSON." }; ws(); require(p < text.length) { "Незавершённый JSON." }
            return when (text[p]) {
                '{' -> { p++; val o = JSONObject(); ws(); if (take('}')) o else { while (true) { ws(); require(p < text.length && text[p] == '"') { "Ключ JSON должен быть строкой." }; val key = string(); require(!o.has(key)) { "Повторяющийся ключ JSON: $key." }; ws(); require(take(':')); o.put(key, value(depth + 1)); ws(); if (take('}')) break; require(take(',')) }; o } }
                '[' -> { p++; val a = JSONArray(); ws(); if (take(']')) a else { while (true) { a.put(value(depth + 1)); ws(); if (take(']')) break; require(take(',')) }; a } }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> { val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, p); require(match != null && match.range.first == p) { "Некорректное число JSON." }; p = match.range.last + 1; val token = match.value; token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) { "Нечисловое значение JSON." } } }
            }
        }
        private fun literal(expected: String, result: Any): Any { require(text.startsWith(expected, p)); p += expected.length; return result }
        private fun take(c: Char): Boolean = if (p < text.length && text[p] == c) { p++; true } else false
        private fun string(): String {
            require(take('"')); val out = StringBuilder(); var ended = false
            while (p < text.length) {
                val c = text[p++]; if (c == '"') { ended = true; break }; require(c.code >= 32)
                if (c != '\\') out.append(c) else {
                    require(p < text.length); when (val escape = text[p++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> { require(p + 4 <= text.length); out.append(text.substring(p, p + 4).toIntOrNull(16)?.toChar() ?: error("Некорректный Unicode escape.")); p += 4 }
                        else -> error("Некорректный escape JSON.")
                    }
                }
            }
            require(ended) { "Незавершённая строка JSON." }
            val s = out.toString(); var i = 0
            while (i < s.length) { if (s[i].isHighSurrogate()) { require(i + 1 < s.length && s[i + 1].isLowSurrogate()) { "Некорректный Unicode." }; i += 2 } else { require(!s[i].isLowSurrogate()) { "Некорректный Unicode." }; i++ } }
            return s
        }
    }
}
