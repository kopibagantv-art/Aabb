package com.example.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.example.core.model.DocumentFilter
import com.example.core.model.PhotoAdjustments
import com.example.core.model.PhotoFilter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ImageFilterEngine {

    fun scaleForPreview(bitmap: Bitmap, maxDim: Int = 960): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    fun applyDocumentFilter(source: Bitmap, filter: DocumentFilter): Bitmap {
        return when (filter) {
            DocumentFilter.ORIGINAL -> source.copy(Bitmap.Config.ARGB_8888, false)
            DocumentFilter.GRAYSCALE -> applyColorMatrix(source, grayscaleMatrix(contrast = 1.12f, brightness = 6f))
            DocumentFilter.AUTO_ENHANCE -> autoEnhanceDocument(source)
            DocumentFilter.BLACK_AND_WHITE -> adaptiveThresholdBw(source)
            DocumentFilter.MAGIC_COLOR -> magicColorDocument(source)
            DocumentFilter.HD_SCAN -> hdScanDocument(source)
            DocumentFilter.CLEAN_DOCUMENT -> illuminationNormalizeDocument(source, sharpenAmount = 0.45f, whitenStrength = 0.85f, keepColor = false)
            DocumentFilter.SHADOW_REMOVAL -> illuminationNormalizeDocument(source, sharpenAmount = 0.25f, whitenStrength = 0.72f, keepColor = true)
            DocumentFilter.BACKGROUND_WHITENING -> backgroundWhitening(source)
            DocumentFilter.NEWSPAPER -> newspaperFilter(source)
        }
    }

    fun applyPhotoFilter(source: Bitmap, filter: PhotoFilter): Bitmap {
        return when (filter) {
            PhotoFilter.NATURAL -> applyColorMatrix(source, saturationContrastMatrix(1.06f, 1.04f, 2f))
            PhotoFilter.DSLR -> dslrEffect(source)
            PhotoFilter.PORTRAIT -> portraitEffect(source)
            PhotoFilter.HDR -> hdrToneMap(source)
            PhotoFilter.CINEMATIC -> cinematicSplitTone(source)
            PhotoFilter.VINTAGE -> vintageEffect(source)
            PhotoFilter.BEAUTY -> beautySmooth(source)
            PhotoFilter.BACKGROUND_BLUR -> radialBackgroundBlur(source, blurRadius = 10, sharpRadiusRatio = 0.36f)
            PhotoFilter.WARM -> warmFilter(source)
            PhotoFilter.COOL -> coolFilter(source)
            PhotoFilter.VIVID -> applyColorMatrix(source, saturationContrastMatrix(1.42f, 1.16f, 4f))
            PhotoFilter.BLACK_AND_WHITE -> applyColorMatrix(source, grayscaleMatrix(contrast = 1.28f, brightness = -6f))
        }
    }

    fun applyPhotoAdjustments(source: Bitmap, adj: PhotoAdjustments): Bitmap {
        // 1. Crop first (normalized)
        val crop = adj.cropRect
        val cx = (crop.left.coerceIn(0f, 0.95f) * source.width).toInt()
        val cy = (crop.top.coerceIn(0f, 0.95f) * source.height).toInt()
        val cw = ((crop.right.coerceIn(crop.left + 0.05f, 1f) - crop.left) * source.width).toInt()
            .coerceIn(1, source.width - cx)
        val ch = ((crop.bottom.coerceIn(crop.top + 0.05f, 1f) - crop.top) * source.height).toInt()
            .coerceIn(1, source.height - cy)

        var working = if (cx != 0 || cy != 0 || cw != source.width || ch != source.height) {
            Bitmap.createBitmap(source, cx, cy, cw, ch)
        } else {
            source
        }

        // 2. Rotate & Flip
        val rot = ((adj.rotationDegrees % 360) + 360) % 360
        if (rot != 0 || adj.flipHorizontal) {
            val m = Matrix()
            if (adj.flipHorizontal) {
                m.postScale(-1f, 1f, working.width / 2f, working.height / 2f)
            }
            if (rot != 0) {
                m.postRotate(rot.toFloat())
            }
            val rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, m, true)
            if (working !== source) working.recycle()
            working = rotated
        }

        // 3. ColorMatrix adjustments (Brightness, Contrast, Saturation, Exposure)
        val hasMatrix = adj.brightness != 0f || adj.contrast != 0f || adj.saturation != 0f || adj.exposure != 0f
        if (hasMatrix) {
            val sat = (1f + adj.saturation / 100f).coerceIn(0f, 2.2f)
            val con = (1f + adj.contrast / 100f).coerceIn(0.3f, 2.2f)
            val expGain = 2.0.pow((adj.exposure / 65.0)).toFloat()
            val brightShift = adj.brightness * 1.4f

            val cm = ColorMatrix()
            cm.setSaturation(sat)

            val scale = con * expGain
            val translate = (1f - con) * 128f + brightShift
            val toneMatrix = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(toneMatrix)
            val toned = applyColorMatrix(working, cm)
            if (working !== source) working.recycle()
            working = toned
        }

        // 4. Highlights & Shadows pixel pass
        if (adj.highlights != 0f || adj.shadows != 0f) {
            val hs = adjustHighlightsShadows(working, adj.highlights / 100f, adj.shadows / 100f)
            if (working !== source) working.recycle()
            working = hs
        }

        // 5. Sharpness 3x3 unsharp kernel
        if (adj.sharpness > 1f) {
            val sharpened = unsharpMask(working, amount = (adj.sharpness / 100f) * 1.4f)
            if (working !== source) working.recycle()
            working = sharpened
        }

        return if (working === source) source.copy(Bitmap.Config.ARGB_8888, false) else working
    }

    // --- Document Filter Implementations ---

    private fun autoEnhanceDocument(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        var minLum = 255
        var maxLum = 0
        for (c in px) {
            val lum = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }
        val span = (maxLum - minLum).coerceAtLeast(24)
        val scale = 255f / span
        for (i in px.indices) {
            val c = px[i]
            val r = (((((c shr 16) and 0xFF) - minLum) * scale)).toInt().coerceIn(0, 255)
            val g = (((((c shr 8) and 0xFF) - minLum) * scale)).toInt().coerceIn(0, 255)
            val b = ((((c and 0xFF) - minLum) * scale)).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        val sharp = unsharpMask(out, 0.45f)
        out.recycle()
        return sharp
    }

    private fun adaptiveThresholdBw(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
        }
        val radius = max(8, min(w, h) / 28)
        val localMean = boxBlurChannel(lum, w, h, radius)
        val outPx = IntArray(w * h)
        for (i in px.indices) {
            val v = lum[i]
            val thresh = localMean[i] - 11
            outPx[i] = if (v < thresh) 0xFF0B1120.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun magicColorDocument(src: Bitmap): Bitmap {
        val norm = illuminationNormalizeDocument(src, sharpenAmount = 0.5f, whitenStrength = 0.7f, keepColor = true)
        val vivid = applyColorMatrix(norm, saturationContrastMatrix(1.45f, 1.24f, 6f))
        norm.recycle()
        return vivid
    }

    private fun hdScanDocument(src: Bitmap): Bitmap {
        val norm = illuminationNormalizeDocument(src, sharpenAmount = 0.85f, whitenStrength = 0.88f, keepColor = true)
        val boosted = applyColorMatrix(norm, saturationContrastMatrix(1.18f, 1.22f, 4f))
        norm.recycle()
        return boosted
    }

    private fun illuminationNormalizeDocument(
        src: Bitmap,
        sharpenAmount: Float,
        whitenStrength: Float,
        keepColor: Boolean
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
        }
        val radius = max(14, min(w, h) / 16)
        val bgLum = boxBlurChannel(lum, w, h, radius)

        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val bg = bgLum[i].coerceAtLeast(32)
            val l = lum[i]
            val ratio = (l.toFloat() / bg.toFloat())
            // Push background close to 255 while keeping dark ink crisp
            val targetLum = ((ratio - (1f - whitenStrength) * 0.15f) * 255f)
                .coerceIn(0f, 255f)
            val cleanLum = if (targetLum > 218f) 255f else targetLum

            if (!keepColor) {
                val g = cleanLum.toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            } else {
                val factor = if (l > 4) cleanLum / l.toFloat() else 1f
                val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val base = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return if (sharpenAmount > 0.05f) {
            val s = unsharpMask(base, sharpenAmount)
            base.recycle()
            s
        } else {
            base
        }
    }

    private fun backgroundWhitening(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 30 + g * 59 + b * 11) / 100
            if (lum > 165) {
                val t = ((lum - 165) / 90f).coerceIn(0f, 1f)
                val nr = (r + (255 - r) * t).toInt().coerceIn(0, 255)
                val ng = (g + (255 - g) * t).toInt().coerceIn(0, 255)
                val nb = (b + (255 - b) * t).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                // Darken ink slightly
                val nr = (r * 0.88f).toInt().coerceIn(0, 255)
                val ng = (g * 0.88f).toInt().coerceIn(0, 255)
                val nb = (b * 0.88f).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun newspaperFilter(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val c = px[idx]
                val lum = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
                // Subtle halftone threshold + warm newsprint paper
                val dither = if (((x + y) and 1) == 0) 6 else -6
                val v = ((lum + dither - 128) * 1.45f + 128f).toInt().coerceIn(24, 246)
                val nr = (v * 0.98f).toInt().coerceIn(0, 255)
                val ng = (v * 0.95f).toInt().coerceIn(0, 255)
                val nb = (v * 0.86f).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // --- Photo Filter Implementations ---

    private fun dslrEffect(src: Bitmap): Bitmap {
        val blurred = radialBackgroundBlur(src, blurRadius = 7, sharpRadiusRatio = 0.44f)
        val crisp = unsharpMask(blurred, 0.55f)
        blurred.recycle()
        val finalBmp = applyColorMatrix(crisp, saturationContrastMatrix(1.18f, 1.14f, 4f))
        crisp.recycle()
        return finalBmp
    }

    private fun portraitEffect(src: Bitmap): Bitmap {
        val soft = beautySmooth(src)
        val cm = ColorMatrix(
            floatArrayOf(
                1.06f, 0.02f, 0f, 0f, 6f,
                0f, 1.03f, 0f, 0f, 4f,
                0f, 0f, 0.97f, 0f, 2f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = applyColorMatrix(soft, cm)
        soft.recycle()
        return out
    }

    private fun hdrToneMap(src: Bitmap): Bitmap {
        val hs = adjustHighlightsShadows(src, highlights = -0.45f, shadows = 0.55f)
        val crisp = unsharpMask(hs, 0.55f)
        hs.recycle()
        val vivid = applyColorMatrix(crisp, saturationContrastMatrix(1.25f, 1.12f, 2f))
        crisp.recycle()
        return vivid
    }

    private fun cinematicSplitTone(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val lum = (r * 30 + g * 59 + b * 11) / 100
            if (lum < 115) {
                // Teal shadows
                r = (r * 0.90f).toInt()
                g = (g * 1.02f + 4).toInt()
                b = (b * 1.08f + 10).toInt()
            } else {
                // Warm amber highlights
                r = (r * 1.06f + 8).toInt()
                g = (g * 1.01f + 3).toInt()
                b = (b * 0.91f).toInt()
            }
            // S-curve contrast
            r = ((r - 128) * 1.16f + 128f).toInt().coerceIn(8, 250)
            g = ((g - 128) * 1.16f + 128f).toInt().coerceIn(8, 250)
            b = ((b - 128) * 1.16f + 128f).toInt().coerceIn(8, 250)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun vintageEffect(src: Bitmap): Bitmap {
        val cm = ColorMatrix(
            floatArrayOf(
                0.92f, 0.16f, 0.06f, 0f, 14f,
                0.04f, 0.88f, 0.08f, 0f, 10f,
                0.02f, 0.06f, 0.74f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val base = applyColorMatrix(src, cm)
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        val cx = w / 2f
        val cy = h / 2f
        val maxD = hypot(cx, cy).coerceAtLeast(1f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val d = (hypot(x - cx, y - cy) / maxD).coerceIn(0f, 1f)
                val vig = 1f - (d * d * 0.35f)
                val c = px[idx]
                val r = (((c shr 16) and 0xFF) * vig).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * vig).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * vig).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        base.recycle()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun beautySmooth(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val blurred = boxBlurRgb(px, w, h, radius = max(3, min(w, h) / 140))
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c1 = px[i]
            val c2 = blurred[i]
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF
            // Edge-preserving blend: smooth only low-gradient skin/surface regions
            val diff = (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)) / 3f
            val alpha = (1f - (diff / 34f)).coerceIn(0f, 0.58f)
            val r = (r1 * (1f - alpha) + r2 * alpha + 5f).toInt().coerceIn(0, 255)
            val g = (g1 * (1f - alpha) + g2 * alpha + 4f).toInt().coerceIn(0, 255)
            val b = (b1 * (1f - alpha) + b2 * alpha + 4f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun radialBackgroundBlur(src: Bitmap, blurRadius: Int, sharpRadiusRatio: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val r = max(3, (min(w, h) / 60).coerceAtLeast(blurRadius))
        val blurred = boxBlurRgb(px, w, h, r)
        val cx = w / 2f
        val cy = h / 2f
        val maxD = hypot(cx, cy).coerceAtLeast(1f)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val dist = hypot(x - cx, y - cy) / maxD
                val blend = ((dist - sharpRadiusRatio) / (0.85f - sharpRadiusRatio)).coerceIn(0f, 1f)
                if (blend <= 0.01f) {
                    out[idx] = px[idx]
                } else {
                    val c1 = px[idx]
                    val c2 = blurred[idx]
                    val cr = (((c1 shr 16) and 0xFF) * (1f - blend) + ((c2 shr 16) and 0xFF) * blend).toInt()
                    val cg = (((c1 shr 8) and 0xFF) * (1f - blend) + ((c2 shr 8) and 0xFF) * blend).toInt()
                    val cb = ((c1 and 0xFF) * (1f - blend) + (c2 and 0xFF) * blend).toInt()
                    out[idx] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun warmFilter(src: Bitmap): Bitmap {
        val cm = ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 10f,
                0f, 1.03f, 0f, 0f, 4f,
                0f, 0f, 0.88f, 0f, -4f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return applyColorMatrix(src, cm)
    }

    private fun coolFilter(src: Bitmap): Bitmap {
        val cm = ColorMatrix(
            floatArrayOf(
                0.90f, 0f, 0f, 0f, -4f,
                0f, 1.01f, 0f, 0f, 4f,
                0f, 0f, 1.15f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return applyColorMatrix(src, cm)
    }

    // --- Pixel & Matrix Utilities ---

    private fun adjustHighlightsShadows(src: Bitmap, highlights: Float, shadows: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val lum = (r * 30 + g * 59 + b * 11) / 25500f // 0..1

            // Shadows weight strong in dark regions, highlights weight strong in bright regions
            val shadowWeight = (1f - lum).pow(2)
            val highlightWeight = lum.pow(2)
            val delta = (shadows * 68f * shadowWeight) + (highlights * 68f * highlightWeight)

            r = (r + delta).toInt().coerceIn(0, 255)
            g = (g + delta).toInt().coerceIn(0, 255)
            b = (b + delta).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun unsharpMask(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val blurred = boxBlurRgb(px, w, h, radius = 1)
        val out = IntArray(w * h)
        for (i in px.indices) {
            val orig = px[i]
            val blur = blurred[i]
            val r = (((orig shr 16) and 0xFF) + amount * (((orig shr 16) and 0xFF) - ((blur shr 16) and 0xFF))).toInt().coerceIn(0, 255)
            val g = (((orig shr 8) and 0xFF) + amount * (((orig shr 8) and 0xFF) - ((blur shr 8) and 0xFF))).toInt().coerceIn(0, 255)
            val b = ((orig and 0xFF) + amount * ((orig and 0xFF) - (blur and 0xFF))).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun saturationContrastMatrix(sat: Float, contrast: Float, brightness: Float): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(sat)
        val t = (1f - contrast) * 128f + brightness
        val cMat = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(cMat)
        return cm
    }

    private fun grayscaleMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val t = (1f - contrast) * 128f + brightness
        val cMat = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(cMat)
        return cm
    }

    // Separable O(N) sliding-window box blur for grayscale channel
    private fun boxBlurChannel(input: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val temp = IntArray(w * h)
        val out = IntArray(w * h)
        val r = radius.coerceAtLeast(1)

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            var count = 0
            for (x in 0..min(r, w - 1)) {
                sum += input[row + x]
                count++
            }
            for (x in 0 until w) {
                temp[row + x] = sum / count
                val addIdx = x + r + 1
                if (addIdx < w) {
                    sum += input[row + addIdx]
                    count++
                }
                val remIdx = x - r
                if (remIdx >= 0) {
                    sum -= input[row + remIdx]
                    count--
                }
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var sum = 0
            var count = 0
            for (y in 0..min(r, h - 1)) {
                sum += temp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / count
                val addY = y + r + 1
                if (addY < h) {
                    sum += temp[addY * w + x]
                    count++
                }
                val remY = y - r
                if (remY >= 0) {
                    sum -= temp[remY * w + x]
                    count--
                }
            }
        }
        return out
    }

    private fun boxBlurRgb(input: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val temp = IntArray(w * h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (x in 0..min(r, w - 1)) {
                val c = input[row + x]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
                cnt++
            }
            for (x in 0 until w) {
                temp[row + x] = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                val addX = x + r + 1
                if (addX < w) {
                    val c = input[row + addX]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; cnt++
                }
                val remX = x - r
                if (remX >= 0) {
                    val c = input[row + remX]
                    sr -= (c shr 16) and 0xFF; sg -= (c shr 8) and 0xFF; sb -= c and 0xFF; cnt--
                }
            }
        }

        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (y in 0..min(r, h - 1)) {
                val c = temp[y * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; cnt++
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                val addY = y + r + 1
                if (addY < h) {
                    val c = temp[addY * w + x]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; cnt++
                }
                val remY = y - r
                if (remY >= 0) {
                    val c = temp[remY * w + x]
                    sr -= (c shr 16) and 0xFF; sg -= (c shr 8) and 0xFF; sb -= c and 0xFF; cnt--
                }
            }
        }
        return out
    }
}
