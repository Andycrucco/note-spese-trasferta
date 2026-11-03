package it.scontrini.trasferta

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView

/**
 * Apertura animata: l'auto arriva e corre (intro_guida), poi la frenata con il disco incandescente
 * e il raffreddamento (intro_frenata). Con il blocco attivo la frenata parte quando appoggi il dito.
 * Video senza audio e senza marchi; si salta con un tocco (il blocco no).
 */
object Intro {
    fun on(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("intro", true)
    fun set(ctx: Context, v: Boolean) { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("intro", v).apply() }
}

class BrakeShow(private val ctx: Context, private val parent: ViewGroup) {
    private val overlay = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true }
    private val video = VideoView(ctx)
    private val hint: TextView = ctx.txt("Tocca per saltare", 13f, R.color.ink2).apply { gravity = Gravity.CENTER }
    private var onTap: (() -> Unit)? = null
    private var closed = false
    private var seq = 0

    init {
        overlay.addView(video, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        overlay.addView(ctx.txt("Scontrini Trasferta", 22f, R.color.ink, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = ctx.dp(88) })
        overlay.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ctx.dp(64) })
        overlay.setOnClickListener { onTap?.invoke() }
        video.setOnClickListener { onTap?.invoke() }
        video.setOnPreparedListener { mp -> mp.setVolume(0f, 0f); video.start() }
        parent.addView(overlay, ViewGroup.LayoutParams(-1, -1))
    }

    /** riproduce un video; alla fine (o al tocco, se saltabile, o dopo maxMs) chiama onEnd una volta sola */
    private fun play(raw: Int, skippable: Boolean, maxMs: Long, onEnd: () -> Unit) {
        val my = ++seq
        var ended = false
        val end = { if (!ended && my == seq && !closed) { ended = true; onEnd() } }
        onTap = if (skippable) end else null
        video.setOnCompletionListener { end() }
        video.setOnErrorListener { _, _, _ -> end(); true }
        video.setVideoURI(Uri.parse("android.resource://" + ctx.packageName + "/" + raw))
        overlay.postDelayed({ end() }, maxMs)
    }

    /** l'auto arriva e corre */
    fun drive(onEnd: () -> Unit) { hint.text = "Tocca per saltare"; play(R.raw.intro_guida, true, 4_000, onEnd) }

    /** frenata, disco incandescente e raffreddamento */
    fun brake(onEnd: () -> Unit) { hint.text = "Tocca per saltare"; play(R.raw.intro_frenata, true, 6_000, onEnd) }

    /** in attesa dell'impronta: il video resta fermo sull'ultimo fotogramma della corsa */
    fun waitFinger(text: String, onTapAgain: (() -> Unit)?) { hint.text = text; onTap = onTapAgain }

    fun close(then: () -> Unit) {
        if (closed) return
        closed = true; seq++
        try { video.stopPlayback() } catch (_: Exception) {}
        overlay.animate().alpha(0f).setDuration(300).withEndAction { parent.removeView(overlay); then() }.start()
    }
}
