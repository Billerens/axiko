package app.axiko.core

import android.content.Context as AndroidContext
import android.util.Log
import app.axiko.App
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.Wrapper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one JavaScript engine of the app: Rhino in interpreted mode, where script code calls
 * any Android class directly (`Packages.android...`). Pages, the agent's tools, background
 * jobs and the system services all run their code here.
 */
object Engine {
    private const val TAG = "AxikoEngine"
    private const val STACK_BYTES = 16L * 1024 * 1024

    val host = Host()

    lateinit var scope: ScriptableObject
        private set

    /** Why the engine did not start, when it did not. */
    @Volatile
    var bootError: String? = null
        private set

    private val ready = CountDownLatch(1)

    /** The workspace's start-up script has run: its subscriptions and jobs are in place. */
    private val booted = CountDownLatch(1)
    private val deadline = ThreadLocal<Long>()

    /** Script threads: a deep stack, because the interpreter recurses. */
    val pool: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(null, task, "axiko-js", STACK_BYTES).apply { isDaemon = true }
    }

    class Timeout : Error("Скрипт остановлен: превышено время выполнения")

    private val wraps = WrapFactory().apply { isJavaPrimitiveWrap = false }

    /**
     * Classes a script does not see: the vault, what hands out provider keys, and the keystore the vault's
     * key lives in. A name ending with a dot is a package. Reflection still reaches them; this closes the
     * plain way, the one a script would take without meaning harm.
     */
    private val SEALED = listOf("app.axiko.core.Vault", "app.axiko.agent.Models", "java.security.KeyStore", "java.security.KeyStoreSpi", "android.security.")

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            isInterpretedMode = true
            languageVersion = Context.VERSION_ES6
            wrapFactory = wraps
            applicationClassLoader = App.instance.classLoader
            instructionObserverThreshold = 50_000
            // Everything in the phone is in a script's reach except where secrets are kept and unsealed.
            setClassShutter { name -> SEALED.none { name == it || name.startsWith("$it$") || it.endsWith(".") && name.startsWith(it) } }
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
            // Scripts reach non-public members too: nothing in the phone is off limits.
            Context.FEATURE_ENHANCED_JAVA_ACCESS -> true
            else -> super.hasFeature(cx, featureIndex)
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val limit = deadline.get() ?: return
            if (System.nanoTime() > limit) {
                deadline.remove()
                throw Timeout()
            }
        }
    }

    fun init(app: AndroidContext) {
        if (!ContextFactory.hasExplicitGlobal()) ContextFactory.initGlobal(factory)
        pool.execute {
            try {
                enter { cx ->
                    val top = ImporterTopLevel(cx)
                    ScriptableObject.putProperty(top, "host", Context.javaToJS(host, top))
                    ScriptableObject.putProperty(top, "context", Context.javaToJS(app, top))
                    scope = top
                    val source = app.assets.open("engine/bootstrap.js").use { it.readBytes().decodeToString() }
                    cx.evaluateString(top, source, "bootstrap.js", 1, null)
                }
            } catch (error: Throwable) {
                bootError = describe(error)
                Log.e(TAG, "Engine failed to start: $bootError", error)
            } finally {
                ready.countDown()
            }
            if (bootError == null && !Workspace.safeMode) runBoot()
            booted.countDown()
        }
    }

    /** The workspace's own start-up script: registers jobs and hooks. A failure is only logged. */
    fun runBoot() {
        val boot = File(Workspace.root, "lib/boot.js")
        if (!boot.isFile) return
        try {
            eval("require('lib/boot.js')", "boot")
        } catch (error: Throwable) {
            host.log("error", "lib/boot.js: " + describe(error))
        }
    }

    fun <T> enter(block: (Context) -> T): T {
        val cx = factory.enterContext()
        try {
            return block(cx)
        } finally {
            Context.exit()
        }
    }

    private fun awaitReady() {
        ready.await()
        bootError?.let { throw IllegalStateException("Движок не запустился: $it") }
    }

    private fun <T> timed(timeoutMs: Long, block: () -> T): T {
        if (timeoutMs <= 0) return block()
        deadline.set(System.nanoTime() + timeoutMs * 1_000_000)
        try {
            return block()
        } finally {
            deadline.remove()
        }
    }

    /** Runs code in the shared global scope; the value of the last expression comes back. */
    fun eval(code: String, name: String = "eval", timeoutMs: Long = 0): Any? {
        awaitReady()
        return enter { cx -> timed(timeoutMs) { plain(cx.evaluateString(scope, code, name, 1, null)) } }
    }

    /** Runs code and renders its value the way a console would. */
    fun evalShown(code: String, timeoutMs: Long = 0): String {
        awaitReady()
        return enter { cx ->
            timed(timeoutMs) {
                val value = cx.evaluateString(scope, code, "eval", 1, null)
                val show = scope.get("__show", scope) as Function
                Context.toString(show.call(cx, scope, scope, arrayOf(value)))
            }
        }
    }

    /** Calls a global function with plain arguments. */
    fun call(name: String, vararg args: Any?, timeoutMs: Long = 0): Any? {
        awaitReady()
        return enter { cx ->
            val fn = scope.get(name, scope) as? Function
                ?: throw IllegalStateException("Нет функции $name")
            val jsArgs = Array(args.size) { Context.javaToJS(args[it], scope) }
            timed(timeoutMs) { plain(fn.call(cx, scope, scope, jsArgs)) }
        }
    }

    private val listening = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Notes that some script subscribed to the event with `on(name, fn)`. */
    fun listen(name: String) {
        listening.add(name)
    }

    /** A phone event (a notification, another app coming to the front) for the scripts that asked for it. */
    fun fire(name: String, data: org.json.JSONObject) {
        pool.execute {
            // An alarm may start the app from nothing: wait until the scripts have subscribed.
            booted.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (name !in listening || bootError != null) return@execute
            runCatching { call("__fire", name, data.toString()) }
                .onFailure { host.log("error", "on $name: " + describe(it)) }
        }
    }

    /** A page's call into a workspace module: `require(path)[fn](...args)`, as JSON. */
    fun rpc(path: String, fn: String, argsJson: String): String? =
        call("__rpc", path, fn, argsJson) as String?

    /** A script value as Kotlin sees it: strings and numbers plain, Java objects unwrapped. */
    fun plain(value: Any?): Any? = when (value) {
        null, is Undefined -> null
        is Wrapper -> value.unwrap()
        is CharSequence -> value.toString()
        else -> value
    }

    /** An error the way the agent should read it: message plus the script stack. */
    fun describe(error: Throwable): String {
        if (error is RhinoException) {
            val stack = error.scriptStackTrace.trim()
            val details = error.details() ?: error.message ?: error.toString()
            return if (stack.isEmpty()) details else "$details\n$stack"
        }
        val cause = error.cause
        if (cause is RhinoException) return describe(cause)
        return error.toString()
    }
}
