package com.example.footballfixturewidget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max

/**
 * Player avatar renderer.
 *
 * v12.4 intentionally preserves the original photo background. If a provider image
 * has a white background, that white background stays inside the circular crop instead
 * of being made transparent. Only the pixels outside the circle are transparent.
 */
object AvatarProcessor {
    fun preparePlayerAvatar(source: Bitmap, outputSizePx: Int = 256, ringWidthPx: Int = 6): Bitmap {
        val size = outputSizePx.coerceAtLeast(64)
        val ringWidth = ringWidthPx.coerceAtLeast(0)
        val sourceArgb = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val center = size / 2f
        val outerRadius = size / 2f - 1f
        val imageRadius = (outerRadius - ringWidth).coerceAtLeast(size * 0.42f)

        // Keep white provider backgrounds white inside the circle.
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, imageRadius, basePaint)

        val shader = BitmapShader(sourceArgb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(
            (imageRadius * 2f) / sourceArgb.width.toFloat(),
            (imageRadius * 2f) / sourceArgb.height.toFloat()
        )
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                center - sourceArgb.width * scale / 2f,
                center - sourceArgb.height * scale / 2f
            )
        }
        shader.setLocalMatrix(matrix)

        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, imageRadius, imagePaint)

        if (ringWidth > 0) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = ringWidth.toFloat()
            }
            canvas.drawCircle(center, center, imageRadius - ringWidth / 2f, ringPaint)
        }
        return out
    }
}
