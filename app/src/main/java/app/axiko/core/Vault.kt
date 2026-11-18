package app.axiko.core

import android.app.AlertDialog
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.FrameLayout
import app.axiko.App
import app.axiko.MainActivity
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords and keys the modules need (a mailbox password, a search API key). They are typed
 * by the owner into a system dialog, never into the chat, and kept encrypted with a key that
 * lives in the phone's keystore, so they are not readable in a backup or a file listing.
 * The shell's own secrets are kept here too (the keys of model providers among them); which names
 * are the shell's, and what scripts may do with the rest, is in [Secrets].
 */
object Vault {
    private const val ALIAS = "axiko-vault"
    private val store get() = App.instance.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun set(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        store.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val sealed = Base64.decode(store.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }

    fun remove(name: String) = store.edit().remove(name).apply()

    fun names(): List<String> = store.all.keys.sorted()

    /** Whether a value is kept under the name; nothing is decrypted to say so. */
    fun has(name: String): Boolean = store.contains(name)

    /** Every value with its name. Read anew each time: no decrypted copy is kept where a script could find it. */
    fun known(): List<Pair<String, String>> =
        names().mapNotNull { name -> runCatching { get(name) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { name to it } }

    /** [text] as a model may see it: every stored secret is replaced by a mark with its name (see [Secrets.scrub]). */
    fun scrub(text: String): String =
        if (text.length < Secrets.MIN) text else runCatching { Secrets.scrub(text, known()) }.getOrDefault(text)

    /**
     * Asks the owner to type the secret and keeps it under [name]; false when the owner
     * cancelled or the app is not on screen. Call off the main thread: it waits for the answer.
     */
    fun ask(name: String, title: String, hint: String): Boolean {
        val activity = MainActivity.current ?: return false
        val done = CountDownLatch(1)
        var saved = false
        activity.runOnUiThread {
            val field = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                this.hint = hint
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val holder = FrameLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(field)
            }
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage("Значение сохранится в защищённом хранилище телефона под именем «$name». В чат оно не попадёт.")
                .setView(holder)
                .setPositiveButton("Сохранить") { _, _ ->
                    val value = field.text.toString()
                    if (value.isNotEmpty()) {
                        set(name, value)
                        saved = true
                    }
                }
                .setNegativeButton("Отмена", null)
                .setOnDismissListener { done.countDown() }
                .show()
        }
        done.await()
        return saved
    }
}
