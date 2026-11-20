package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.imaging.DocumentEdgeDetector
import com.example.core.imaging.ImageFilterEngine
import com.example.core.model.DocumentFilter
import com.example.core.model.PageSizeOption
import com.example.core.model.PhotoAdjustments
import com.example.core.model.PhotoFilter
import com.example.core.pdf.EditablePageSpec
import com.example.core.pdf.PdfEngine
import com.example.core.pdf.SignatureOverlay
import com.example.core.pdf.SplitMode
import com.example.core.pdf.TextOverlay
import com.example.core.permissions.AccessGateHost
import com.example.core.permissions.AccessSpec
import com.example.core.permissions.rememberAccessGateController
import com.example.core.storage.LocalFileRepository
import com.example.ui.theme.PdfSuperTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfSuperRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "pdf_super_test_${System.nanoTime()}").apply { mkdirs() }
    }

    private fun createSampleDocumentBitmap(w: Int = 180, h: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paperPaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(24f, 28f, w - 24f, h - 28f, paperPaint)
        val inkPaint = Paint().apply { color = Color.BLACK; strokeWidth = 4f }
        canvas.drawLine(40f, 60f, w - 40f, 60f, inkPaint)
        canvas.drawLine(40f, 90f, w - 50f, 90f, inkPaint)
        return bmp
    }

    @Test
    fun `all 10 document filters produce valid bitmaps`() {
        val sample = createSampleDocumentBitmap()
        for (filter in DocumentFilter.entries) {
            val out = ImageFilterEngine.applyDocumentFilter(sample, filter)
            assertEquals("Filter $filter width mismatch", sample.width, out.width)
            assertEquals("Filter $filter height mismatch", sample.height, out.height)
            if (out !== sample) out.recycle()
        }
        sample.recycle()
    }

    @Test
    fun `all 12 photo filters and 8 photo adjustments produce valid bitmaps`() {
        val sample = createSampleDocumentBitmap(160, 120)
        for (filter in PhotoFilter.entries) {
            val out = ImageFilterEngine.applyPhotoFilter(sample, filter)
            assertEquals(sample.width, out.width)
            assertEquals(sample.height, out.height)
            if (out !== sample) out.recycle()
        }

        val adjusted = ImageFilterEngine.applyPhotoAdjustments(
            sample,
            PhotoAdjustments(
                brightness = 15f,
                contrast = 20f,
                saturation = -10f,
                sharpness = 40f,
                exposure = 10f,
                highlights = -25f,
                shadows = 30f,
                rotationDegrees = 90,
                flipHorizontal = true
            )
        )
        // Rotated 90 degrees swaps width and height
        assertEquals(120, adjusted.width)
        assertEquals(160, adjusted.height)
        adjusted.recycle()
        sample.recycle()
    }

    @Test
    fun `edge detection and perspective warp produce valid cropped document`() {
        val sample = createSampleDocumentBitmap(200, 260)
        val corners = DocumentEdgeDetector.detectDocumentCorners(sample)
        assertEquals(4, corners.size)
        val warped = DocumentEdgeDetector.perspectiveWarp(sample, corners)
        assertTrue(warped.width >= 100)
        assertTrue(warped.height >= 100)
        warped.recycle()
        sample.recycle()
    }

    @Test
    fun `text to pdf, password protection, extract, merge, split, and edit signature work end-to-end`() = runTest {
        val pdf1 = File(tempDir, "doc1.pdf")
        PdfEngine.createPdfFromText(
            context = context,
            title = "PDF SUPER Report",
            body = "Line one of the offline generated PDF document.\n\nSecond paragraph with clean wrapping.",
            outputFile = pdf1,
            pageSize = PageSizeOption.A4,
            password = "secret"
        )
        assertTrue(pdf1.exists())
        assertTrue(pdf1.length() > 500)

        val metaLocked = PdfEngine.inspectPdf(context, pdf1)
        assertTrue(metaLocked.isEncrypted)
        assertTrue(PdfEngine.verifyPassword(context, pdf1, "secret"))
        assertFalse(PdfEngine.verifyPassword(context, pdf1, "wrong"))

        val extracted = PdfEngine.extractPdfText(context, pdf1, "secret")
        assertTrue(extracted.contains("PDF SUPER Report"))

        // Second unlocked multi-image PDF (Letter)
        val pdf2 = File(tempDir, "doc2.pdf")
        val img1 = createSampleDocumentBitmap()
        val img2 = createSampleDocumentBitmap()
        PdfEngine.createPdfFromImages(
            context = context,
            images = listOf(img1, img2),
            outputFile = pdf2,
            pageSize = PageSizeOption.LETTER
        )
        val meta2 = PdfEngine.inspectPdf(context, pdf2)
        assertEquals(2, meta2.pageCount)

        // Merge locked pdf1 + unlocked pdf2
        val merged = File(tempDir, "merged.pdf")
        PdfEngine.mergePdfs(
            context = context,
            inputFiles = listOf(pdf1, pdf2),
            outputFile = merged,
            passwords = mapOf(pdf1.absolutePath to "secret")
        )
        val mergedMeta = PdfEngine.inspectPdf(context, merged)
        assertEquals(3, mergedMeta.pageCount)

        // Split merged PDF every 1 page -> 3 files
        val splitDir = File(tempDir, "splits")
        val parts = PdfEngine.splitPdf(
            context = context,
            inputFile = merged,
            password = null,
            mode = SplitMode.EveryNPages(1),
            outputDir = splitDir,
            baseName = "part"
        )
        assertEquals(3, parts.size)

        // Edit merged PDF: reorder pages [2, 0], rotate page 0, embed signature & text overlay, lock with new password
        val sigBmp = Bitmap.createBitmap(120, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val edited = File(tempDir, "edited.pdf")
        PdfEngine.saveEditedPdf(
            context = context,
            inputFile = merged,
            sourcePassword = null,
            pageSpecs = listOf(EditablePageSpec(2, 90), EditablePageSpec(0, 0)),
            signatures = listOf(SignatureOverlay(0, sigBmp, 0.7f, 0.8f, 0.25f)),
            textAnnotations = listOf(TextOverlay(1, "APPROVED", 0.2f, 0.2f)),
            outputFile = edited,
            newPassword = "newpw"
        )
        assertTrue(edited.exists())
        assertTrue(PdfEngine.verifyPassword(context, edited, "newpw"))
    }

    @Test
    fun `access gate shows user-friendly camera and storage picker prompts`() {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.CAMERA)
        val repo = LocalFileRepository(context)
        var storageProceeded = false
        var fallbackUsed = false

        composeRule.setContent {
            PdfSuperTheme {
                val gate = rememberAccessGateController(repo)
                androidx.compose.foundation.layout.Column {
                    Button(onClick = {
                        gate.requestCameraAccess(
                            onFallbackGallery = { fallbackUsed = true },
                            onGranted = {}
                        )
                    }) {
                        Text("Trigger Camera")
                    }
                    Button(onClick = {
                        gate.requestStorageAccess(AccessSpec.ScopedStorage.DocumentPicker, forcePrompt = true) {
                            storageProceeded = true
                        }
                    }) {
                        Text("Trigger Storage")
                    }
                }
                AccessGateHost(controller = gate)
            }
        }

        // Camera gate shows rationale and gallery fallback when camera isn't granted
        composeRule.onNodeWithText("Trigger Camera").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Camera Access for Scanner").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_gate_allow_button").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_gate_gallery_fallback").performClick()
        composeRule.waitForIdle()
        assertTrue(fallbackUsed)

        // Storage gate explains zero-permission scoped picker and invokes callback
        composeRule.onNodeWithText("Trigger Storage").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Storage Access for PDFs").assertIsDisplayed()
        composeRule.onNodeWithTag("storage_gate_continue_button").performClick()
        composeRule.waitForIdle()
        assertTrue(storageProceeded)
    }
}
