package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.core.model.CompressionLevel
import com.example.core.model.ImageExportFormat
import com.example.core.model.PageSizeOption
import com.example.core.model.PdfTextFont
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PdfMetadataInfo(
    val pageCount: Int,
    val isEncrypted: Boolean
)

data class CompressionStats(
    val outputFile: File,
    val originalBytes: Long,
    val compressedBytes: Long
) {
    val savedPercent: Int
        get() = if (originalBytes <= 0L) 0
        else (((originalBytes - compressedBytes).toFloat() / originalBytes) * 100f).toInt().coerceIn(0, 99)
}

sealed interface SplitMode {
    data class ExtractPages(val zeroBasedPages: List<Int>) : SplitMode
    data class PageRanges(val rangeSpec: String) : SplitMode
    data class EveryNPages(val n: Int) : SplitMode
}

data class EditablePageSpec(
    val originalIndex: Int,
    val extraRotationDeg: Int = 0
)

data class SignatureOverlay(
    val pagePositionInEditedDoc: Int,
    val signatureBitmap: Bitmap,
    val normX: Float,      // 0..1 center X
    val normY: Float,      // 0..1 center Y
    val normWidth: Float   // 0..1 width relative to page width
)

data class TextOverlay(
    val pagePositionInEditedDoc: Int,
    val text: String,
    val normX: Float,
    val normY: Float,
    val fontSizePt: Float = 14f
)

object PdfEngine {

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    suspend fun inspectPdf(context: Context, file: File): PdfMetadataInfo = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        try {
            PDDocument.load(file).use { doc ->
                PdfMetadataInfo(pageCount = doc.numberOfPages, isEncrypted = doc.isEncrypted)
            }
        } catch (_: Exception) {
            // Encrypted PDFs without empty user password throw InvalidPasswordException on load
            PdfMetadataInfo(pageCount = 0, isEncrypted = true)
        }
    }

    suspend fun verifyPassword(context: Context, file: File, password: String): Boolean =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)
            try {
                PDDocument.load(file, password).use { true }
            } catch (_: Exception) {
                false
            }
        }

    suspend fun createPdfFromImages(
        context: Context,
        images: List<Bitmap>,
        outputFile: File,
        pageSize: PageSizeOption,
        marginPt: Float = 20f,
        password: String? = null
    ): File = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        require(images.isNotEmpty()) { "At least one image is required." }

        PDDocument().use { doc ->
            for (bmp in images) {
                val landscape = bmp.width > bmp.height
                val rect = if (landscape) {
                    PDRectangle(pageSize.heightPt, pageSize.widthPt)
                } else {
                    PDRectangle(pageSize.widthPt, pageSize.heightPt)
                }
                val page = PDPage(rect)
                doc.addPage(page)

                val pdImage = JPEGFactory.createFromImage(doc, bmp, 0.90f)
                val availW = (rect.width - marginPt * 2f).coerceAtLeast(40f)
                val availH = (rect.height - marginPt * 2f).coerceAtLeast(40f)
                val scale = minOf(availW / bmp.width, availH / bmp.height)
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val x = (rect.width - drawW) / 2f
                val y = (rect.height - drawH) / 2f

                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(pdImage, x, y, drawW, drawH)
                }
            }

            applyOptionalProtection(doc, password)
            outputFile.parentFile?.mkdirs()
            doc.save(outputFile)
        }
        outputFile
    }

    suspend fun createPdfFromText(
        context: Context,
        title: String,
        body: String,
        outputFile: File,
        pageSize: PageSizeOption,
        fontSizePt: Float = 12f,
        fontStyle: PdfTextFont = PdfTextFont.SANS,
        password: String? = null
    ): File = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val regularFont = when (fontStyle) {
            PdfTextFont.SANS -> PDType1Font.HELVETICA
            PdfTextFont.SERIF -> PDType1Font.TIMES_ROMAN
            PdfTextFont.MONO -> PDType1Font.COURIER
        }
        val boldFont = when (fontStyle) {
            PdfTextFont.SANS -> PDType1Font.HELVETICA_BOLD
            PdfTextFont.SERIF -> PDType1Font.TIMES_BOLD
            PdfTextFont.MONO -> PDType1Font.COURIER_BOLD
        }

        val rect = PDRectangle(pageSize.widthPt, pageSize.heightPt)
        val margin = 52f
        val maxWidth = rect.width - margin * 2f
        val leading = fontSizePt * 1.48f

        PDDocument().use { doc ->
            var page = PDPage(rect)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            var cursorY = rect.height - margin

            if (title.isNotBlank()) {
                val titleSize = (fontSizePt + 7f).coerceAtLeast(18f)
                val titleLines = wrapText(sanitizeWinAnsi(title.trim()), boldFont, titleSize, maxWidth)
                for (line in titleLines) {
                    if (cursorY < margin + titleSize) {
                        cs.close()
                        page = PDPage(rect)
                        doc.addPage(page)
                        cs = PDPageContentStream(doc, page)
                        cursorY = rect.height - margin
                    }
                    cs.beginText()
                    cs.setFont(boldFont, titleSize)
                    cs.newLineAtOffset(margin, cursorY)
                    cs.showText(line)
                    cs.endText()
                    cursorY -= titleSize * 1.35f
                }
                // Accent divider rule under title
                cs.setStrokingColor(21f / 255f, 101f / 255f, 192f / 255f)
                cs.setLineWidth(1.2f)
                cs.moveTo(margin, cursorY + 4f)
                cs.lineTo(rect.width - margin, cursorY + 4f)
                cs.stroke()
                cursorY -= 18f
            }

            val paragraphs = body.replace("\r\n", "\n").split("\n")
            for (para in paragraphs) {
                val cleanPara = sanitizeWinAnsi(para)
                if (cleanPara.isBlank()) {
                    cursorY -= leading * 0.7f
                    if (cursorY < margin) {
                        cs.close()
                        page = PDPage(rect)
                        doc.addPage(page)
                        cs = PDPageContentStream(doc, page)
                        cursorY = rect.height - margin
                    }
                    continue
                }
                val wrapped = wrapText(cleanPara, regularFont, fontSizePt, maxWidth)
                for (line in wrapped) {
                    if (cursorY < margin + leading) {
                        cs.close()
                        page = PDPage(rect)
                        doc.addPage(page)
                        cs = PDPageContentStream(doc, page)
                        cursorY = rect.height - margin
                    }
                    cs.beginText()
                    cs.setFont(regularFont, fontSizePt)
                    cs.newLineAtOffset(margin, cursorY)
                    cs.showText(line)
                    cs.endText()
                    cursorY -= leading
                }
            }
            cs.close()

            // Add crisp footer page numbers
            val total = doc.numberOfPages
            for (idx in 0 until total) {
                val p = doc.getPage(idx)
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true).use { footerCs ->
                    val footerText = "Page ${idx + 1} of $total"
                    val tw = regularFont.getStringWidth(footerText) / 1000f * 9f
                    footerCs.beginText()
                    footerCs.setFont(regularFont, 9f)
                    footerCs.setNonStrokingColor(100f / 255f, 116f / 255f, 139f / 255f)
                    footerCs.newLineAtOffset((p.mediaBox.width - tw) / 2f, 24f)
                    footerCs.showText(footerText)
                    footerCs.endText()
                }
            }

            applyOptionalProtection(doc, password)
            outputFile.parentFile?.mkdirs()
            doc.save(outputFile)
        }
        outputFile
    }

    suspend fun mergePdfs(
        context: Context,
        inputFiles: List<File>,
        outputFile: File,
        passwords: Map<String, String> = emptyMap(),
        outputPassword: String? = null
    ): File = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        require(inputFiles.size >= 2) { "Select at least 2 PDFs to merge." }

        val tempFiles = mutableListOf<File>()
        try {
            val merger = PDFMergerUtility()
            merger.destinationFileName = outputFile.absolutePath
            outputFile.parentFile?.mkdirs()

            for (file in inputFiles) {
                val pw = passwords[file.absolutePath]
                if (!pw.isNullOrEmpty()) {
                    val unlocked = File.createTempFile("unlocked_", ".pdf", context.cacheDir)
                    tempFiles.add(unlocked)
                    PDDocument.load(file, pw).use { doc ->
                        doc.isAllSecurityToBeRemoved = true
                        doc.save(unlocked)
                    }
                    merger.addSource(unlocked)
                } else {
                    merger.addSource(file)
                }
            }
            merger.mergeDocuments(null)

            if (!outputPassword.isNullOrBlank()) {
                val tempMerged = File.createTempFile("merged_", ".pdf", context.cacheDir)
                tempFiles.add(tempMerged)
                outputFile.copyTo(tempMerged, overwrite = true)
                PDDocument.load(tempMerged).use { doc ->
                    applyOptionalProtection(doc, outputPassword)
                    doc.save(outputFile)
                }
            }
        } finally {
            tempFiles.forEach { it.delete() }
        }
        outputFile
    }

    suspend fun splitPdf(
        context: Context,
        inputFile: File,
        password: String?,
        mode: SplitMode,
        outputDir: File,
        baseName: String
    ): List<File> = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        outputDir.mkdirs()
        val created = mutableListOf<File>()

        PDDocument.load(inputFile, password ?: "").use { sourceDoc ->
            sourceDoc.isAllSecurityToBeRemoved = true
            val total = sourceDoc.numberOfPages

            when (mode) {
                is SplitMode.ExtractPages -> {
                    val valid = mode.zeroBasedPages.filter { it in 0 until total }.distinct()
                    require(valid.isNotEmpty()) { "Select at least one valid page." }
                    val out = File(outputDir, "${baseName}_extracted.pdf")
                    PDDocument().use { dst ->
                        for (idx in valid) {
                            dst.importPage(sourceDoc.getPage(idx))
                        }
                        dst.save(out)
                    }
                    created.add(out)
                }

                is SplitMode.PageRanges -> {
                    val ranges = parseRanges(mode.rangeSpec, total)
                    require(ranges.isNotEmpty()) { "Enter valid page ranges like 1-3, 4-5." }
                    ranges.forEachIndexed { rangeIdx, pageIndices ->
                        val out = File(outputDir, "${baseName}_part${rangeIdx + 1}.pdf")
                        PDDocument().use { dst ->
                            for (p in pageIndices) {
                                dst.importPage(sourceDoc.getPage(p))
                            }
                            dst.save(out)
                        }
                        created.add(out)
                    }
                }

                is SplitMode.EveryNPages -> {
                    val step = mode.n.coerceAtLeast(1)
                    var part = 1
                    var start = 0
                    while (start < total) {
                        val end = minOf(start + step, total)
                        val out = File(outputDir, "${baseName}_part${part}.pdf")
                        PDDocument().use { dst ->
                            for (p in start until end) {
                                dst.importPage(sourceDoc.getPage(p))
                            }
                            dst.save(out)
                        }
                        created.add(out)
                        part++
                        start = end
                    }
                }
            }
        }
        created
    }

    suspend fun compressPdf(
        context: Context,
        inputFile: File,
        password: String?,
        level: CompressionLevel,
        outputFile: File
    ): CompressionStats = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val originalSize = inputFile.length()
        outputFile.parentFile?.mkdirs()

        // Step 1: Try lossless-structure image recompression inside PDResources
        val candidateStruct = File.createTempFile("cmp_struct_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(inputFile, password ?: "").use { doc ->
                doc.isAllSecurityToBeRemoved = true
                for (page in doc.pages) {
                    val resources = page.resources ?: continue
                    for (name in resources.xObjectNames) {
                        val xObj = resources.getXObject(name)
                        if (xObj is PDImageXObject) {
                            val bmp = runCatching { xObj.image }.getOrNull() ?: continue
                            val maxTarget = (maxOf(bmp.width, bmp.height) * level.maxDpiScale).toInt().coerceAtLeast(240)
                            val scaled = if (maxOf(bmp.width, bmp.height) > maxTarget) {
                                val ratio = maxTarget.toFloat() / maxOf(bmp.width, bmp.height)
                                Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * ratio).toInt().coerceAtLeast(1),
                                    (bmp.height * ratio).toInt().coerceAtLeast(1),
                                    true
                                )
                            } else bmp
                            val recompressed = JPEGFactory.createFromImage(doc, scaled, level.jpegQuality / 100f)
                            resources.put(name, recompressed)
                            if (scaled !== bmp) scaled.recycle()
                            bmp.recycle()
                        }
                    }
                }
                doc.save(candidateStruct)
            }

            // Step 2: If structural compression didn't shrink much and user wants BALANCED/MAXIMUM,
            // render pages at compact DPI and rebuild with JPEGFactory, then keep whichever is smallest.
            val candidateRaster = File.createTempFile("cmp_raster_", ".pdf", context.cacheDir)
            try {
                val dpi = when (level) {
                    CompressionLevel.LOW -> 150
                    CompressionLevel.BALANCED -> 115
                    CompressionLevel.MAXIMUM -> 85
                }
                val pages = renderPdfPages(context, inputFile, password, targetDpiWidth = (dpi * 8.27f).toInt())
                if (pages.isNotEmpty()) {
                    PDDocument().use { dst ->
                        for (bmp in pages) {
                            val page = PDPage(PDRectangle(bmp.width.toFloat(), bmp.height.toFloat()))
                            dst.addPage(page)
                            val img = JPEGFactory.createFromImage(dst, bmp, level.jpegQuality / 100f)
                            PDPageContentStream(dst, page).use { cs ->
                                cs.drawImage(img, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                            }
                            bmp.recycle()
                        }
                        dst.save(candidateRaster)
                    }
                }

                val best = when {
                    candidateRaster.exists() && candidateRaster.length() in 1 until candidateStruct.length() -> candidateRaster
                    else -> candidateStruct
                }
                best.copyTo(outputFile, overwrite = true)
            } finally {
                candidateRaster.delete()
            }
        } finally {
            candidateStruct.delete()
        }

        CompressionStats(
            outputFile = outputFile,
            originalBytes = originalSize,
            compressedBytes = outputFile.length()
        )
    }

    suspend fun pdfToImages(
        context: Context,
        inputFile: File,
        password: String?,
        pageIndices: List<Int>,
        format: ImageExportFormat,
        dpi: Int,
        outputDir: File,
        baseName: String
    ): List<File> = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        outputDir.mkdirs()

        val targetWidth = ((dpi / 72f) * 595f).toInt().coerceIn(720, 2600)
        val created = mutableListOf<File>()

        val tempUnlocked: File? = if (!password.isNullOrEmpty()) {
            File.createTempFile("render_unlock_", ".pdf", context.cacheDir).also { tmp ->
                PDDocument.load(inputFile, password).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(tmp)
                }
            }
        } else null

        try {
            val fileToRender = tempUnlocked ?: inputFile
            ParcelFileDescriptor.open(fileToRender, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val indices = if (pageIndices.isEmpty()) (0 until renderer.pageCount).toList() else pageIndices
                    for (idx in indices) {
                        if (idx !in 0 until renderer.pageCount) continue
                        renderer.openPage(idx).use { page ->
                            val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val outFile = File(outputDir, "${baseName}_page_${idx + 1}.${format.extension}")
                            FileOutputStream(outFile).use { fos ->
                                val compressFmt = if (format == ImageExportFormat.PNG) {
                                    Bitmap.CompressFormat.PNG
                                } else {
                                    Bitmap.CompressFormat.JPEG
                                }
                                bmp.compress(compressFmt, 92, fos)
                            }
                            bmp.recycle()
                            created.add(outFile)
                        }
                    }
                }
            }
        } finally {
            tempUnlocked?.delete()
        }
        created
    }

    suspend fun renderPdfPages(
        context: Context,
        inputFile: File,
        password: String? = null,
        targetDpiWidth: Int = 520,
        maxPages: Int = 80
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val bitmaps = mutableListOf<Bitmap>()
        val tempUnlocked: File? = if (!password.isNullOrEmpty()) {
            File.createTempFile("thumb_unlock_", ".pdf", context.cacheDir).also { tmp ->
                PDDocument.load(inputFile, password).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(tmp)
                }
            }
        } else null

        try {
            val fileToRender = tempUnlocked ?: inputFile
            ParcelFileDescriptor.open(fileToRender, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = targetDpiWidth.toFloat() / page.width.coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(targetDpiWidth, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmaps.add(bmp)
                        }
                    }
                }
            }
        } finally {
            tempUnlocked?.delete()
        }
        bitmaps
    }

    suspend fun saveEditedPdf(
        context: Context,
        inputFile: File,
        sourcePassword: String?,
        pageSpecs: List<EditablePageSpec>,
        signatures: List<SignatureOverlay>,
        textAnnotations: List<TextOverlay>,
        outputFile: File,
        newPassword: String?
    ): File = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        require(pageSpecs.isNotEmpty()) { "Document must contain at least one page." }

        val tempOut = File.createTempFile("edited_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(inputFile, sourcePassword ?: "").use { srcDoc ->
                srcDoc.isAllSecurityToBeRemoved = true
                PDDocument().use { dstDoc ->
                    pageSpecs.forEachIndexed { newIdx, spec ->
                        val imported = dstDoc.importPage(srcDoc.getPage(spec.originalIndex))
                        imported.rotation = (imported.rotation + spec.extraRotationDeg) % 360

                        val mediaBox = imported.cropBox ?: imported.mediaBox
                        val pageW = mediaBox.width
                        val pageH = mediaBox.height

                        // Embed digital signatures for this page
                        val pageSigs = signatures.filter { it.pagePositionInEditedDoc == newIdx }
                        val pageTexts = textAnnotations.filter { it.pagePositionInEditedDoc == newIdx }

                        if (pageSigs.isNotEmpty() || pageTexts.isNotEmpty()) {
                            PDPageContentStream(
                                dstDoc,
                                imported,
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true
                            ).use { cs ->
                                for (sig in pageSigs) {
                                    val sigImg = LosslessFactory.createFromImage(dstDoc, sig.signatureBitmap)
                                    val drawW = (pageW * sig.normWidth).coerceIn(40f, pageW * 0.85f)
                                    val aspect = sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.coerceAtLeast(1)
                                    val drawH = drawW * aspect
                                    val pdfX = mediaBox.lowerLeftX + (sig.normX * pageW) - (drawW / 2f)
                                    val pdfY = mediaBox.lowerLeftY + ((1f - sig.normY) * pageH) - (drawH / 2f)
                                    cs.drawImage(sigImg, pdfX, pdfY, drawW, drawH)
                                }

                                for (txt in pageTexts) {
                                    val clean = sanitizeWinAnsi(txt.text)
                                    if (clean.isNotBlank()) {
                                        val pdfX = mediaBox.lowerLeftX + (txt.normX * pageW)
                                        val pdfY = mediaBox.lowerLeftY + ((1f - txt.normY) * pageH)
                                        cs.beginText()
                                        cs.setFont(PDType1Font.HELVETICA_BOLD, txt.fontSizePt)
                                        cs.setNonStrokingColor(21f / 255f, 101f / 255f, 192f / 255f)
                                        cs.newLineAtOffset(pdfX, pdfY)
                                        cs.showText(clean)
                                        cs.endText()
                                    }
                                }
                            }
                        }
                    }

                    applyOptionalProtection(dstDoc, newPassword)
                    dstDoc.save(tempOut)
                }
            }
            outputFile.parentFile?.mkdirs()
            tempOut.copyTo(outputFile, overwrite = true)
        } finally {
            tempOut.delete()
        }
        outputFile
    }

    suspend fun extractPdfText(
        context: Context,
        file: File,
        password: String? = null
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        PDDocument.load(file, password ?: "").use { doc ->
            val stripper = PDFTextStripper()
            stripper.getText(doc).trim()
        }
    }

    private fun applyOptionalProtection(doc: PDDocument, password: String?) {
        if (!password.isNullOrBlank()) {
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(password, password, ap).apply {
                encryptionKeyLength = 128
                permissions = ap
            }
            doc.protect(spp)
        }
    }

    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            val width = font.getStringWidth(candidate) / 1000f * fontSize
            if (width <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    // Break long single token
                    var chunk = ""
                    for (ch in word) {
                        val next = chunk + ch
                        if (font.getStringWidth(next) / 1000f * fontSize > maxWidth && chunk.isNotEmpty()) {
                            lines.add(chunk)
                            chunk = ch.toString()
                        } else {
                            chunk = next
                        }
                    }
                    current = StringBuilder(chunk)
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return if (lines.isEmpty()) listOf("") else lines
    }

    private fun sanitizeWinAnsi(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\u2018', '\u2019' -> sb.append('\'')
                '\u201C', '\u201D' -> sb.append('"')
                '\u2013', '\u2014' -> sb.append('-')
                '\u2022' -> sb.append('*')
                '\t' -> sb.append("    ")
                else -> if (ch.code in 32..255) sb.append(ch) else sb.append(' ')
            }
        }
        return sb.toString()
    }

    private fun parseRanges(spec: String, totalPages: Int): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        for (token in spec.split(",")) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            if ("-" in trimmed) {
                val parts = trimmed.split("-")
                val start = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val end = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                val s = start.coerceIn(1, totalPages)
                val e = end.coerceIn(1, totalPages)
                val list = if (s <= e) (s..e).map { it - 1 } else (e..s).map { it - 1 }
                if (list.isNotEmpty()) groups.add(list)
            } else {
                val p = trimmed.toIntOrNull() ?: continue
                if (p in 1..totalPages) groups.add(listOf(p - 1))
            }
        }
        return groups
    }
}
