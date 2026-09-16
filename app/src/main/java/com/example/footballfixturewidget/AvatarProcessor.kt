package com.example.footballfixturewidget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Color
import kotlin.math.max

/** Rounded presentation ONLY: never change the background or recolor original pixels. */
object AvatarProcessor {
    fun preparePlayerAvatar(source: Bitmap, outputSizePx: Int = 256, ringWidthPx: Int = 4): Bitmap {
        val size = outputSizePx.coerceAtLeast(64)
        val ring = ringWidthPx.coerceIn(0, size / 12)
        val imageRadius = size / 2f - ring - 1f
        val center = size / 2f
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val bitmapShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(imageRadius * 2f / source.width, imageRadius * 2f / source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(center - source.width * scale / 2f, center - source.height * scale / 2f)
        }
        bitmapShader.setLocalMatrix(matrix)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = bitmapShader
        }
        canvas.drawCircle(center, center, imageRadius, imagePaint)
        if (ring > 0) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = ring.toFloat()
            }
            canvas.drawCircle(center, center, imageRadius + ring / 2f, ringPaint)
        }
        return result
    }
}
