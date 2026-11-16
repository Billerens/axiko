package app.assi.store

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
    const val ASSI_API = 1
    const val GAMES_ENGINE = 1
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

    fun index(bytes: ByteArray): JSONObject = parse(bytes).also { value ->
        fields(value, setOf("schemaVersion", "repository", "packages"))
        require(integer(value, "schemaVersion", 1, 1) == 1L)
        val repository = obj(value, "repository")
        fields(repository, setOf("id", "title", "homepage"))
        id(str(repository, "id", 96)); str(repository, "title", 120); https(str(repository, "homepage", 2048))
        val packages = array(value, "packages", 2000)
        val ids = HashSet<String>()
        for (i in 0 until packages.length()) {
            val p = packages.optJSONObject(i) ?: error("packages[$i] должен быть объектом.")
            packageEntry(p)
            require(ids.add(p.getString("id"))) { "Повторяющийся ID пакета: ${p.getString("id")}." }
        }
    }

    fun packageEntry(value: JSONObject): JSONObject = value.also {
        fields(it, setOf("id", "kind", "title", "summary", "version", "publisher", "license", "manifestUrl", "archive", "compatibility"), setOf("tags", "screenshots"))
        identity(it); str(it, "title", 120); str(it, "summary", 600); https(str(it, "manifestUrl", 2048))
        val archive = obj(it, "archive")
        fields(archive, setOf("url", "sha256", "size", "unpackedSize", "fileCount"))
        https(str(archive, "url", 2048)); hash(str(archive, "sha256", 64))
        integer(archive, "size", 1, MAX_ARCHIVE.toLong()); integer(archive, "unpackedSize", 1, MAX_UNPACKED.toLong()); integer(archive, "fileCount", 4, MAX_FILES.toLong())
        val compatibility = obj(it, "compatibility")
        fields(compatibility, setOf("minAssiApi") + if (it.getString("kind") == "game") setOf("gamesEngine") else emptySet(), setOf("gamesEngine"))
        integer(compatibility, "minAssiApi", 1, Int.MAX_VALUE.toLong())
        if (compatibility.has("gamesEngine")) integer(compatibility, "gamesEngine", 1, Int.MAX_VALUE.toLong())
        if (it.has("tags")) strings(it, "tags", 12, 32)
        if (it.has("screenshots")) strings(it, "screenshots", 8, 2048).forEach(::https)
    }

    fun manifest(bytes: ByteArray): JSONObject = parse(bytes).also { value ->
        fields(value, setOf("schemaVersion", "id", "kind", "version", "publisher", "license", "sourceFormat", "entry", "runtime", "dependencies", "optionalDependencies", "capabilities", "files"), setOf("networkDomains"))
        integer(value, "schemaVersion", 1, 1); identity(value)
        require(str(value, "sourceFormat", 32) == "readable") { "Пакет должен содержать читаемые исходники." }
        val entry = path(str(value, "entry", 240))
        require(entry.startsWith("code/")) { "Точка входа должна находиться в code/." }
        val runtime = obj(value, "runtime")
        fields(runtime, setOf("assiApi") + if (value.getString("kind") == "game") setOf("gamesEngine") else emptySet(), setOf("gamesEngine"))
        integer(runtime, "assiApi", 1, Int.MAX_VALUE.toLong())
        if (runtime.has("gamesEngine")) integer(runtime, "gamesEngine", 1, Int.MAX_VALUE.toLong())
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
            require(p == "README.md" || p == "LICENSE" || p.startsWith("code/") || p.startsWith("assets/")) { "Файл вне разрешённой структуры: $p." }
            paths.add(p); hash(str(file, "sha256", 64)); total += integer(file, "size", 0, MAX_FILE.toLong())
        }
        uniquePaths(paths)
        require(paths.containsAll(listOf("README.md", "LICENSE", entry))) { "В описи отсутствуют README.md, LICENSE или точка входа." }
        require(total <= MAX_UNPACKED) { "Распакованный пакет превышает 20 МиБ." }
    }

    fun compatible(entry: JSONObject, manifest: JSONObject) {
        for (key in listOf("id", "kind", "version", "publisher", "license")) require(entry.getString(key) == manifest.getString(key)) { "Манифест не совпадает с индексом: $key." }
        val expected = entry.getJSONObject("compatibility"); val runtime = manifest.getJSONObject("runtime")
        require(expected.getInt("minAssiApi") == runtime.getInt("assiApi")) { "Требования API не совпадают с индексом." }
        require(runtime.getInt("assiApi") <= ASSI_API) { "Нужна более новая версия API Axiko." }
        if (manifest.getString("kind") == "game") {
            require(expected.getInt("gamesEngine") == runtime.getInt("gamesEngine") && runtime.getInt("gamesEngine") == GAMES_ENGINE) { "Несовместимая версия игрового движка." }
        }
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
    private fun identity(value: JSONObject) { id(str(value, "id", 96)); require(str(value, "kind", 16) in setOf("module", "game")) { "Неизвестный тип пакета." }; version(str(value, "version", 96)); id(str(value, "publisher", 96)); str(value, "license", 128) }
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
