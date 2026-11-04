package it.scontrini.trasferta

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Anteprima del PDF appena creato: pagina 1 con la foto e i dati, pagina 2 con l'inflazione (CPI)
 * per pasti e pernotti. Da qui si invia (OneDrive, mail…) o si apre con un'altra app.
 */
class PdfViewActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_CPI_MISSING = "cpi_missing"
        fun intent(ctx: Context, f: File, cpiMissing: Boolean = false): Intent =
            Intent(ctx, PdfViewActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath).putExtra(EXTRA_CPI_MISSING, cpiMissing)
    }

    private lateinit var pages: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        if (!f.exists()) { finish(); return }
        title = "PDF della spesa"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = vbox().apply { setBackgroundColor(col(R.color.graphite)) }
        val head = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(6)) }
        head.addView(mono(f.name, 12f, R.color.ink2))
        val info = txt("", 13f, R.color.ink2)
        head.addView(info, lp(top = dp(4)))
        if (intent.getBooleanExtra(EXTRA_CPI_MISSING, false)) head.addView(txt(
            "Senza internet non ho potuto scaricare i dati dell'inflazione: la pagina 2 si completa da sola la prossima volta che apri o esporti il PDF con la connessione.",
            13f, R.color.warn), lp(top = dp(6)))
        root.addView(head)
        pages = vbox().apply { setPadding(dp(12), dp(4), dp(12), dp(16)) }
        val sv = ScrollView(this).apply { addView(pages) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        pages.addView(hbox().apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(40)); addView(Disc.spinner(this@PdfViewActivity, 56)) })
        val bar = hbox().apply { setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(col(R.color.surface)) }
        bar.addView(btn("Invia il PDF") { startActivity(Exporter.shareIntent(this, f, "application/pdf", "Invia il PDF a OneDrive")) },
            LinearLayout.LayoutParams(0, -2, 1.3f).apply { marginEnd = dp(8) })
        bar.addView(btn("Apri con…", false) {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Exporter.uri(this, f), "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Apri il PDF"))
            } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        bar.addView(btn("Fatto", false) { finish() }, LinearLayout.LayoutParams(0, -2, 0.9f))
        root.addView(bar)
        setContentView(root)
        root.padForSystemBars()

        val width = minOf(resources.displayMetrics.widthPixels - dp(24), 1400)
        Thread {
            val bmps = mutableListOf<Bitmap>()
            try {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                            val h = (width.toFloat() * p.height / p.width).toInt()
                            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmps.add(bmp)
                        }
                    }
                }
            } catch (_: Exception) {}
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                pages.removeAllViews()
                val kb = (f.length() + 1023) / 1024
                info.text = "${bmps.size} " + (if (bmps.size == 1) "pagina" else "pagine") + " · $kb KB" +
                    (if (bmps.size >= 2) " · pagina 2: inflazione del paese (CPI)" else "") + (if (kb > 600) " · oltre 600 KB" else "")
                if (bmps.isEmpty()) pages.addView(txt("Non riesco a mostrare il PDF qui: usa \"Apri con…\".", 14f, R.color.warn), lp(top = dp(24)))
                bmps.forEachIndexed { i, bmp ->
                    pages.addView(cap(if (i == 0) "Pagina 1 · foto e dati" else if (i == 1 && bmps.size == 2) "Pagina 2 · inflazione (CPI)" else "Pagina ${i + 1}"), lp(top = dp(if (i == 0) 6 else 18)))
                    pages.addView(ImageView(this).apply {
                        setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                        elevation = dp(2).toFloat(); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lp(top = dp(6)))
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); Lock.guard(this) }
    override fun onPause() { super.onPause(); Lock.paused() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
