package com.example.core.model

import android.graphics.PointF
import java.io.File

enum class PdfTool(
    val route: String,
    val title: String,
    val subtitle: String
) {
    IMAGE_TO_PDF("image_to_pdf", "Image to PDF", "Combine photos into PDF"),
    TEXT_TO_PDF("text_to_pdf", "Text to PDF", "Create formatted PDFs"),
    DOCUMENT_SCANNER("document_scanner", "Document Scanner", "Auto-edge & HD scan"),
    PHOTO_EDITOR("photo_editor", "Photo Editor", "12 filters & 8 pro tools"),
    PDF_EDITOR("pdf_editor", "PDF Editor", "Sign, reorder & protect"),
    MERGE_PDF("merge_pdf", "Merge PDF", "Join multiple PDFs"),
    SPLIT_PDF("split_pdf", "Split PDF", "Extract or burst pages"),
    COMPRESS_PDF("compress_pdf", "Compress PDF", "Reduce PDF file size"),
    PDF_TO_IMAGE("pdf_to_image", "PDF to Image", "Export crisp PNG/JPEG"),
    MY_FILES("my_files", "My Files", "Local vault & sharing")
}

enum class PageSizeOption(
    val label: String,
    val widthPt: Float,
    val heightPt: Float
) {
    A4("A4", 595f, 842f),
    A3("A3", 842f, 1191f),
    LETTER("Letter", 612f, 792f)
}

enum class DocumentFilter(val label: String) {
    ORIGINAL("Original"),
    AUTO_ENHANCE("Auto Enhance"),
    BLACK_AND_WHITE("Black & White"),
    GRAYSCALE("Grayscale"),
    MAGIC_COLOR("Magic Color"),
    HD_SCAN("HD Scan"),
    CLEAN_DOCUMENT("Clean Document"),
    SHADOW_REMOVAL("Shadow Removal"),
    BACKGROUND_WHITENING("Background Whitening"),
    NEWSPAPER("Newspaper")
}

enum class PhotoFilter(val label: String) {
    NATURAL("Natural"),
    DSLR("DSLR-style"),
    PORTRAIT("Portrait"),
    HDR("HDR"),
    CINEMATIC("Cinematic"),
    VINTAGE("Vintage"),
    BEAUTY("Beauty"),
    BACKGROUND_BLUR("Background Blur"),
    WARM("Warm"),
    COOL("Cool"),
    VIVID("Vivid"),
    BLACK_AND_WHITE("Black & White")
}

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
)

data class PhotoAdjustments(
    val brightness: Float = 0f,      // -100..100
    val contrast: Float = 0f,        // -100..100
    val saturation: Float = 0f,      // -100..100
    val sharpness: Float = 0f,       // 0..100
    val exposure: Float = 0f,        // -100..100
    val highlights: Float = 0f,      // -100..100
    val shadows: Float = 0f,         // -100..100
    val rotationDegrees: Int = 0,    // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val cropRect: NormalizedCropRect = NormalizedCropRect()
)

enum class CompressionLevel(val label: String, val description: String, val jpegQuality: Int, val maxDpiScale: Float) {
    LOW("Low Compression", "High visual clarity, ~20–35% smaller", 85, 1.0f),
    BALANCED("Balanced", "Recommended quality & size, ~45–65% smaller", 62, 0.78f),
    MAXIMUM("Maximum", "Smallest footprint for sharing, ~70–85% smaller", 38, 0.58f)
}

enum class ImageExportFormat(val label: String, val extension: String, val mimeType: String) {
    PNG("PNG (Lossless)", "png", "image/png"),
    JPEG("JPEG (Compact)", "jpg", "image/jpeg")
}

enum class PdfTextFont(val label: String) {
    SANS("Helvetica Sans"),
    SERIF("Times Serif"),
    MONO("Courier Mono")
}

data class LocalFileItem(
    val id: String,
    val name: String,
    val file: File,
    val isPdf: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val pageCount: Int = 0,
    val isEncrypted: Boolean = false
)

data class ScannedPage(
    val id: String,
    val originalPath: String,
    val croppedPath: String,
    val corners: List<PointF>,
    val filter: DocumentFilter = DocumentFilter.AUTO_ENHANCE
)

sealed interface AsyncTaskState<out T> {
    data object Idle : AsyncTaskState<Nothing>
    data class Loading(val message: String = "Processing...") : AsyncTaskState<Nothing>
    data class Success<T>(val data: T) : AsyncTaskState<T>
    data class Error(val message: String) : AsyncTaskState<Nothing>
}
