package it.scontrini.trasferta

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Si apre quando un telefono con l'app legge il QR o l'NFC di un collega con la fotocamera o il lettore di sistema. */
class GuestLinkActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.load(this)
        val name = Tavolo.nameFrom(intent?.dataString)
        if (name != null) {
            Store.tableSeen[name] = System.currentTimeMillis()
            try { Store.save(this) } catch (_: Exception) {}
            Toast.makeText(this, "$name è tra i commensali suggeriti per le prossime 3 ore.", Toast.LENGTH_LONG).show()
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
