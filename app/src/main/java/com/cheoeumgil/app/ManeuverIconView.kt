package com.cheoeumgil.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import com.cheoeumgil.core.Glyph
import com.cheoeumgil.core.Icon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the hard-section pictogram (see [com.cheoeumgil.core.iconFor]) in a 100x100 box:
 * the path to take in cyan, other lanes muted. Code instead of a drawable per maneuver.
 */
class ManeuverIconView(context: Context) : View(context) {
    var icon: Icon? = null
        set(value) {
            field = value
            visibility = if (value == null) GONE else VISIBLE
            invalidate()
        }

    private val take = stroke("#7FD4FF", 9f)
    private val other = stroke("#3E5A75", 9f)
    private val warn = stroke("#FFB020", 8f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun stroke(color: String, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor(color)
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val i = icon ?: return
        val s = minOf(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * s) / 2, (height - 100 * s) / 2)
        canvas.scale(s, s)
        if (i.left && i.glyph != Glyph.UTURN) canvas.scale(-1f, 1f, 50f, 50f)
        when (i.glyph) {
            Glyph.TURN -> arrow(canvas, take, Path().apply { moveTo(40f, 92f); lineTo(40f, 58f); quadTo(40f, 38f, 60f, 38f); lineTo(84f, 38f) }, PointF(60f, 38f), PointF(84f, 38f))
            Glyph.DOUBLE_TURN -> arrow(canvas, take, Path().apply {
                moveTo(28f, 94f); lineTo(28f, 64f); quadTo(28f, 52f, 40f, 52f); lineTo(60f, 52f); quadTo(72f, 52f, 72f, 40f); lineTo(72f, 14f)
            }, PointF(72f, 40f), PointF(72f, 14f))
            Glyph.FORK -> {
                canvas.drawPath(Path().apply { moveTo(50f, 60f); lineTo(30f, 14f) }, other)
                arrow(canvas, take, Path().apply { moveTo(50f, 94f); lineTo(50f, 60f); lineTo(70f, 18f) }, PointF(50f, 60f), PointF(70f, 18f))
            }
            Glyph.ROUNDABOUT -> {
                canvas.drawCircle(50f, 50f, 18f, other)
                canvas.drawLine(50f, 94f, 50f, 68f, take)
                arrow(canvas, take, Path().apply { moveTo(63f, 37f); lineTo(84f, 16f) }, PointF(63f, 37f), PointF(84f, 16f))
            }
            Glyph.MERGE -> {
                canvas.drawLine(62f, 94f, 62f, 10f, other)
                arrow(canvas, take, Path().apply { moveTo(30f, 94f); quadTo(30f, 62f, 62f, 44f); lineTo(62f, 12f) }, PointF(62f, 40f), PointF(62f, 12f))
            }
            Glyph.UTURN -> arrow(canvas, take, Path().apply {
                moveTo(66f, 94f); lineTo(66f, 44f); quadTo(66f, 22f, 50f, 22f); quadTo(34f, 22f, 34f, 44f); lineTo(34f, 70f)
            }, PointF(34f, 50f), PointF(34f, 72f))
            Glyph.WARNING -> {
                canvas.drawPath(Path().apply { moveTo(50f, 14f); lineTo(88f, 82f); lineTo(12f, 82f); close() }, warn)
                canvas.drawLine(50f, 38f, 50f, 60f, warn)
                fill.color = warn.color
                canvas.drawCircle(50f, 71f, 4.5f, fill)
            }
        }
        canvas.restore()
    }

    /** Draws [path] plus a filled arrowhead at [tip], pointing away from [from]. */
    private fun arrow(canvas: Canvas, paint: Paint, path: Path, from: PointF, tip: PointF) {
        canvas.drawPath(path, paint)
        val a = atan2(tip.y - from.y, tip.x - from.x)
        val len = 16f
        val spread = 0.55f
        fill.color = paint.color
        canvas.drawPath(Path().apply {
            moveTo(tip.x + 4f * cos(a), tip.y + 4f * sin(a))
            lineTo(tip.x - len * cos(a - spread), tip.y - len * sin(a - spread))
            lineTo(tip.x - len * cos(a + spread), tip.y - len * sin(a + spread))
            close()
        }, fill)
    }
}
