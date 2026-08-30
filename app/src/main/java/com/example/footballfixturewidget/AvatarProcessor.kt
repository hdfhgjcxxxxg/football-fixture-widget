package com.example.footballfixturewidget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

object AvatarProcessor {
    private const val BACKGROUND_ALPHA_THRESHOLD = 16
    private const val VISIBLE_ALPHA_THRESHOLD = 28

    fun preparePlayerAvatar(source: Bitmap, outputSizePx: Int = 256, ringWidthPx: Int = 8): Bitmap {
        val argb = ensureArgb8888(source)
        val transparent = removeEdgeBackground(argb)
        val cropped = cropVisibleArea(transparent)
        return renderCircularAvatar(cropped, outputSizePx, ringWidthPx)
    }

    private fun ensureArgb8888(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun removeEdgeBackground(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 1 || h <= 1) return source

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun enqueue(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val index = y * w + x
            if (visited[index]) return
            val color = pixels[index]
            if (!isBackgroundCandidate(color)) return
            visited[index] = true
            queue[tail++] = index
        }

        for (x in 0 until w) {
            enqueue(x, 0)
            enqueue(x, h - 1)
        }
        for (y in 1 until h - 1) {
            enqueue(0, y)
            enqueue(w - 1, y)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % w
            val y = index / w
            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }

        val cleaned = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (index in pixels.indices) {
            val color = pixels[index]
            if (visited[index]) {
                pixels[index] = Color.TRANSPARENT
            } else if (isSoftEdgeCandidate(color)) {
                val alpha = Color.alpha(color)
                val softenedAlpha = (alpha * 0.35f).toInt().coerceIn(0, 255)
                pixels[index] = Color.argb(softenedAlpha, Color.red(color), Color.green(color), Color.blue(color))
            }
        }
        cleaned.setPixels(pixels, 0, w, 0, 0, w, h)
        return cleaned
    }

    private fun cropVisibleArea(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var left = w
        var top = h
        var right = -1
        var bottom = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                val alpha = Color.alpha(pixels[y * w + x])
                if (alpha >= VISIBLE_ALPHA_THRESHOLD) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) return source

        val contentWidth = right - left + 1
        val contentHeight = bottom - top + 1
        val square = max(contentWidth, contentHeight)
        val padding = max((square * 0.10f).toInt(), 4)
        val outputSize = square + padding * 2

        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dstLeft = (outputSize - contentWidth) / 2
        val dstTop = (outputSize - contentHeight) / 2
        val srcRect = android.graphics.Rect(left, top, right + 1, bottom + 1)
        val dstRect = android.graphics.Rect(dstLeft, dstTop, dstLeft + contentWidth, dstTop + contentHeight)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        return out
    }

    private fun renderCircularAvatar(source: Bitmap, outputSizePx: Int, ringWidthPx: Int): Bitmap {
        val size = outputSizePx.coerceAtLeast(64)
        val ringWidth = ringWidthPx.coerceAtLeast(2)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val center = size / 2f
        val outerRadius = size / 2f - 1f
        val imageRadius = outerRadius - ringWidth

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, imageRadius, fillPaint)

        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max((imageRadius * 2f) / source.width.toFloat(), (imageRadius * 2f) / source.height.toFloat())
        val dx = center - source.width * scale / 2f
        val dy = center - source.height * scale / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        shader.setLocalMatrix(matrix)

        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, imageRadius, imagePaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = ringWidth.toFloat()
        }
        canvas.drawCircle(center, center, imageRadius - ringWidth / 2f, ringPaint)
        return out
    }

    private fun isBackgroundCandidate(color: Int): Boolean {
        val alpha = Color.alpha(color)
        if (alpha <= BACKGROUND_ALPHA_THRESHOLD) return true
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        return maxChannel >= 238 && (maxChannel - minChannel) <= 22
    }

    private fun isSoftEdgeCandidate(color: Int): Boolean {
        val alpha = Color.alpha(color)
        if (alpha <= BACKGROUND_ALPHA_THRESHOLD) return false
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        return maxChannel >= 245 && (maxChannel - minChannel) <= 12
    }
}
