package com.example.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object DocumentEdgeDetector {

    /**
     * Detects 4 normalized [0f..1f] corners in order:
     * [TopLeft, TopRight, BottomRight, BottomLeft].
     */
    fun detectDocumentCorners(source: Bitmap): List<PointF> {
        val defaultCorners = listOf(
            PointF(0.08f, 0.08f),
            PointF(0.92f, 0.08f),
            PointF(0.92f, 0.92f),
            PointF(0.08f, 0.92f)
        )
        if (source.width < 32 || source.height < 32) return defaultCorners

        val targetMax = 240
        val scale = targetMax.toFloat() / max(source.width, source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(32)
        val h = (source.height * scale).toInt().coerceAtLeast(32)
        val small = Bitmap.createScaledBitmap(source, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small != source) small.recycle()

        // 1. Grayscale luminance
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 2. Sobel gradient magnitude (skip outer 2px border)
        val grad = FloatArray(w * h)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 2 until h - 2) {
            val row = y * w
            for (x in 2 until w - 2) {
                val tl = gray[(y - 1) * w + (x - 1)]
                val tc = gray[(y - 1) * w + x]
                val tr = gray[(y - 1) * w + (x + 1)]
                val ml = gray[row + (x - 1)]
                val mr = gray[row + (x + 1)]
                val bl = gray[(y + 1) * w + (x - 1)]
                val bc = gray[(y + 1) * w + x]
                val br = gray[(y + 1) * w + (x + 1)]

                val gx = -tl + tr - 2f * ml + 2f * mr - bl + br
                val gy = -tl - 2f * tc - tr + bl + 2f * bc + br
                val mag = sqrt(gx * gx + gy * gy)
                grad[row + x] = mag
                sum += mag
                sumSq += mag * mag
                count++
            }
        }
        if (count == 0) return defaultCorners

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        val std = sqrt(max(0.0, variance))
        val threshold = (mean + 1.45 * std).toFloat().coerceAtLeast(35f)

        // 3. Find extreme edge coordinates for the 4 corners inside a safe margin
        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE // y - x -> small means top-right
        var maxDiff = -Float.MAX_VALUE // y - x -> large means bottom-left

        var tl = PointF(w * 0.08f, h * 0.08f)
        var tr = PointF(w * 0.92f, h * 0.08f)
        var br = PointF(w * 0.92f, h * 0.92f)
        var bl = PointF(w * 0.08f, h * 0.92f)

        var edgeHits = 0
        val marginX = (w * 0.03f).toInt().coerceAtLeast(3)
        val marginY = (h * 0.03f).toInt().coerceAtLeast(3)

        for (y in marginY until h - marginY) {
            val row = y * w
            for (x in marginX until w - marginX) {
                if (grad[row + x] >= threshold) {
                    edgeHits++
                    val s = (x + y).toFloat()
                    val d = (y - x).toFloat()
                    if (s < minSum) {
                        minSum = s
                        tl = PointF(x.toFloat(), y.toFloat())
                    }
                    if (s > maxSum) {
                        maxSum = s
                        br = PointF(x.toFloat(), y.toFloat())
                    }
                    if (d < minDiff) {
                        minDiff = d
                        tr = PointF(x.toFloat(), y.toFloat())
                    }
                    if (d > maxDiff) {
                        maxDiff = d
                        bl = PointF(x.toFloat(), y.toFloat())
                    }
                }
            }
        }

        if (edgeHits < 25) return defaultCorners

        val normalized = listOf(
            PointF((tl.x / w).coerceIn(0.02f, 0.48f), (tl.y / h).coerceIn(0.02f, 0.48f)),
            PointF((tr.x / w).coerceIn(0.52f, 0.98f), (tr.y / h).coerceIn(0.02f, 0.48f)),
            PointF((br.x / w).coerceIn(0.52f, 0.98f), (br.y / h).coerceIn(0.52f, 0.98f)),
            PointF((bl.x / w).coerceIn(0.02f, 0.48f), (bl.y / h).coerceIn(0.52f, 0.98f))
        )

        // Check shoelace area is meaningful (> 20% of frame)
        val area = polygonArea(normalized)
        return if (area > 0.20f) normalized else defaultCorners
    }

    private fun polygonArea(pts: List<PointF>): Float {
        var area = 0f
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        return abs(area) * 0.5f
    }

    /**
     * Applies true projective perspective correction via Matrix.setPolyToPoly.
     * Corners order: [TopLeft, TopRight, BottomRight, BottomLeft] normalized in [0f..1f].
     */
    fun perspectiveWarp(source: Bitmap, corners: List<PointF>): Bitmap {
        if (corners.size != 4) return source
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()

        val tlX = corners[0].x * sw
        val tlY = corners[0].y * sh
        val trX = corners[1].x * sw
        val trY = corners[1].y * sh
        val brX = corners[2].x * sw
        val brY = corners[2].y * sh
        val blX = corners[3].x * sw
        val blY = corners[3].y * sh

        val topWidth = hypot(trX - tlX, trY - tlY)
        val bottomWidth = hypot(brX - blX, brY - blY)
        val leftHeight = hypot(blX - tlX, blY - tlY)
        val rightHeight = hypot(brX - trX, brY - trY)

        var outW = max(topWidth, bottomWidth).toInt().coerceAtLeast(100)
        var outH = max(leftHeight, rightHeight).toInt().coerceAtLeast(100)

        // Cap extreme dimensions while preserving aspect ratio
        val maxDim = 2200
        if (max(outW, outH) > maxDim) {
            val factor = maxDim.toFloat() / max(outW, outH)
            outW = (outW * factor).toInt().coerceAtLeast(100)
            outH = (outH * factor).toInt().coerceAtLeast(100)
        }

        val srcPts = floatArrayOf(
            tlX, tlY,
            trX, trY,
            brX, brY,
            blX, blY
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )

        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        if (!ok) return source

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return result
    }
}
