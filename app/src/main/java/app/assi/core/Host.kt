package app.assi.core

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.assi.App
import app.assi.MainActivity
import org.json.JSONArray
import com.android.dx.stock.ProxyBuilder
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch

/**
 * What scripts see as `host`: the few things that are easier from Kotlin than through
 * reflection. Everything else scripts take straight from the Android classes.
 */
class Host {
    val console = RingLog(600)
    private val main = Handler(Looper.getMainLooper())

    val workspace: String get() = Workspace.root.path
    val dataDir: String get() = Workspace.data.path

    /** The visible activity, or null when the app is in the background. */
    val activity: Activity? get() = MainActivity.current

    fun log(level: String, message: String) {
        console.add("[$level] $message")
        when (level) {
            "error" -> Log.e("AssiJS", message)
            "warn" -> Log.w("AssiJS", message)
            else -> Log.i("AssiJS", message)
        }
    }

    // ---- modules --------------------------------------------------------------------------

    /** Where `require(path)` from a module in [base] points. */
    fun resolveModule(base: String, path: String): String {
        val start = when {
            File(path).isAbsolute -> File(path)
            path.startsWith("./") || path.startsWith("../") -> File(base, path).normalize()
            else -> Workspace.resolve(path)
        }
        // By its workspace name when it has one: then code in a draft gets the draft's version
        // of a module, whichever file asked for it.
        val name = Workspace.display(start).takeIf { !File(it).isAbsolute }
        fun at(suffix: String): File = if (name != null) Workspace.resolve(name + suffix) else File(start.path + suffix)
        val candidates = listOf(at(""), at(".js"), at(".json"), at("/index.js"), at("/main.js"))
        val found = candidates.firstOrNull { it.isFile }
            ?: throw IllegalArgumentException("Модуль не найден: $path")
        return found.canonicalPath
    }

    fun mtime(path: String): Double = File(path).lastModified().toDouble()

    fun dirname(path: String): String = File(path).parent ?: "/"

    /** The module's body as a function of (exports, module, require, __filename, __dirname). */
    fun compileModule(path: String): Any? {
        val cx = Context.getCurrentContext()
        val source = File(path).readText()
        val wrapped = "(function(exports, module, require, __filename, __dirname){$source\n})"
        return cx.evaluateString(Engine.scope, wrapped, Workspace.display(File(path)), 1, null)
    }

    // ---- files ----------------------------------------------------------------------------

    fun path(path: String): String = Workspace.resolve(path).path

    fun readText(path: String): String = Workspace.resolve(path).readText()

    fun writeText(path: String, text: String) {
        val file = Workspace.resolve(path, write = true)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun exists(path: String): Boolean = Workspace.resolve(path).exists()

    fun list(path: String): Array<String> =
        Workspace.resolve(path).listFiles().orEmpty()
            .map { if (it.isDirectory) it.name + "/" else it.name }.sorted().toTypedArray()

    fun remove(path: String): Boolean = Workspace.remove(path)

    fun asset(path: String): String =
        App.instance.assets.open(path).use { it.readBytes().decodeToString() }

    // ---- threads --------------------------------------------------------------------------

    /** Runs the call on the main thread and waits for its value: views and toasts need it. */
    fun runOnUi(task: Callable<Any?>): Any? {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call()
        val done = CountDownLatch(1)
        var value: Any? = null
        var failure: Throwable? = null
        main.post {
            try {
                value = task.call()
            } catch (error: Throwable) {
                failure = error
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure?.let { throw it }
        return value
    }

    /** Runs the task on a script thread without waiting. */
    fun spawn(task: Runnable) {
        Engine.pool.execute {
            try {
                task.run()
            } catch (error: Throwable) {
                log("error", "spawn: " + Engine.describe(error))
            }
        }
    }

    // ---- classes --------------------------------------------------------------------------

    /**
     * An object of a class generated on the spot: [type] (abstract or not) with the methods
     * named in [impl] overridden by script functions. Interfaces need none of this — scripts
     * implement them directly; this is for classes such as BroadcastReceiver.
     */
    fun extend(type: Class<*>, impl: Scriptable, args: NativeArray): Any {
        val handler = InvocationHandler { proxy, method, passed ->
            val values = passed ?: emptyArray()
            val target = ScriptableObject.getProperty(impl, method.name)
            when {
                target is Function -> Engine.enter { cx ->
                    val jsArgs = Array(values.size) { Context.javaToJS(values[it], Engine.scope) }
                    val result = target.call(cx, Engine.scope, impl, jsArgs)
                    if (method.returnType == Void.TYPE) null else Context.jsToJava(result, method.returnType)
                }
                Modifier.isAbstract(method.modifiers) -> zero(method.returnType)
                else -> ProxyBuilder.callSuper(proxy, method, *values)
            }
        }
        val builder = ProxyBuilder.forClass(type).dexCache(App.instance.codeCacheDir).handler(handler)
        if (args.size > 0) {
            val constructor = type.declaredConstructors.firstOrNull { it.parameterTypes.size == args.size }
                ?: throw IllegalArgumentException("У ${type.name} нет конструктора с ${args.size} аргументами")
            val types = constructor.parameterTypes
            builder.constructorArgTypes(*types)
            builder.constructorArgValues(*Array(types.size) { Context.jsToJava(args[it], types[it]) })
        }
        val made = builder.build()
        ScriptableObject.putProperty(impl, "\$self", Context.javaToJS(made, Engine.scope))
        return made
    }

    private fun zero(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    // ---- shell ----------------------------------------------------------------------------

    /** Called by `on(name, fn)`: the shell starts delivering that phone event to scripts. */
    fun listening(name: String) = Engine.listen(name)

    /** Sends an event to every open page: `assi.on(event, data => ...)`. */
    fun emit(event: String, json: String) = Pages.broadcast(event, json)

    /** The shell's own operations (agent, snapshots, settings, navigation), JSON in and out. */
    fun native(method: String, argsJson: String): String? =
        Native.encode(Native.call(method, JSONArray(argsJson)))
}
