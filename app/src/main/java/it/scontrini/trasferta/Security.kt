package it.scontrini.trasferta

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifratura dei dati dell'app con una chiave AES custodita nel Keystore di Android:
 * la chiave non esce mai dal telefono e non può essere copiata.
 */
object Vault {
    private const val ALIAS = "notespese_dati_v1"
    private val MAGIC = "NSV1".toByteArray()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return MAGIC + byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    fun isEncrypted(data: ByteArray) = data.size > 5 && data.copyOfRange(0, 4).contentEquals(MAGIC)

    fun decrypt(data: ByteArray): ByteArray {
        val ivLen = data[4].toInt()
        val iv = data.copyOfRange(5, 5 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(data, 5 + ivLen, data.size - 5 - ivLen)
    }
}

/** Blocco dell'app con impronta, volto o PIN/sequenza del telefono. */
object Lock {
    private const val TIMEOUT_MS = 5 * 60_000L
    @Volatile private var unlockedAt = 0L
    @Volatile private var lastSeen = 0L

    fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** null se il telefono può sbloccare l'app, altrimenti il motivo. */
    fun unavailable(ctx: Context): String? = when (BiometricManager.from(ctx).canAuthenticate(authenticators())) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Imposta prima un blocco schermo (PIN, sequenza o impronta) nelle impostazioni di Android."
        else -> "Questo telefono non permette di bloccare l'app con impronta o PIN."
    }

    fun needed(ctx: Context): Boolean {
        Store.load(ctx)
        if (!Store.lockEnabled || unavailable(ctx) != null) return false
        val now = System.currentTimeMillis()
        if (unlockedAt == 0L) return true
        return lastSeen > 0 && now - lastSeen > TIMEOUT_MS
    }

    fun unlocked() { unlockedAt = System.currentTimeMillis(); lastSeen = 0L }
    fun paused() { if (unlockedAt > 0) lastSeen = System.currentTimeMillis() }
    fun resumed() { if (lastSeen > 0 && System.currentTimeMillis() - lastSeen <= TIMEOUT_MS) lastSeen = 0L }

    /** Da chiamare in onResume delle schermate con dati. */
    fun guard(act: AppCompatActivity) {
        if (needed(act)) act.startActivity(Intent(act, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        else resumed()
    }

    fun prompt(act: AppCompatActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { onFail(msg.toString()) }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(listOf(Store.myName, Store.myEmail).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Note spese trasferta" })
                .setAllowedAuthenticators(authenticators())
                .build()
        )
    }
}

/** Schermata di accesso: impronta, volto o PIN del telefono. */
class LockActivity : AppCompatActivity() {
    private lateinit var msg: TextView
    private lateinit var frame: android.widget.FrameLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        supportActionBar?.hide()
        val root = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32)); setBackgroundColor(col(R.color.graphite))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.avd_logo)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(R.color.red)) }
        }
        root.addView(logo, lp(w = dp(112), h = dp(112)))
        logo.post { (logo.drawable as? android.graphics.drawable.Animatable)?.start() }
        root.addView(txt("Note spese trasferta", 22f, bold = true).apply { gravity = Gravity.CENTER }, lp(top = dp(16)))
        root.addView(txt(listOf(Store.myName, Store.myEmail).filter { it.isNotBlank() }.joinToString("\n"), 14f, R.color.ink2).apply { gravity = Gravity.CENTER }, lp(top = dp(6)))
        msg = txt("", 14f, R.color.warn).apply { gravity = Gravity.CENTER }
        root.addView(msg, lp(top = dp(16)))
        root.addView(btn("Entra con impronta o PIN") { ask() }, lp(top = dp(20)))
        frame = android.widget.FrameLayout(this).apply { addView(root, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        setContentView(frame)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
        if (b == null) root.post { ask() }
    }

    private fun ask() {
        msg.text = ""
        Lock.prompt(this, "Entra in Note spese trasferta", {
            Lock.unlocked()
            // impronta = frenata: disco incandescente, raffreddamento, poi l'app
            if (Intro.on(this)) BrakeShow(this, frame).let { s -> s.brake { s.close { finish() } } } else finish()
        }, { e -> msg.text = e })
    }
}
