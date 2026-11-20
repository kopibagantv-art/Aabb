package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.firebase.AiEnhancementService
import com.example.core.firebase.ScannedOcrResult
import com.example.core.imaging.DocumentEdgeDetector
import com.example.core.imaging.ImageFilterEngine
import com.example.core.model.DocumentFilter
import com.example.core.model.PageSizeOption
import com.example.core.pdf.PdfEngine
import com.example.core.permissions.AccessSpec
import com.example.core.permissions.LocalAccessGate
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

private enum class ScannerStage {
    SOURCE,
    CROP_PERSPECTIVE,
    FILTER_AND_EXPORT
}

private data class ScannedDocPage(
    val id: String,
    val warpedBitmap: Bitmap,
    val filter: DocumentFilter,
    val filteredBitmap: Bitmap
)

@Composable
fun DocumentScannerScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiService = remember(context) { AiEnhancementService(context) }
    val accessGate = LocalAccessGate.current

    var stage by remember { mutableStateOf(ScannerStage.SOURCE) }
    var useCamera by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    // Current page being cropped
    var rawCapturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedCorners by remember { mutableStateOf<List<PointF>>(emptyList()) }

    // Multi-page session
    val scannedPages = remember { mutableStateListOf<ScannedDocPage>() }
    var activePageIndex by remember { mutableIntStateOf(0) }

    // Export options
    var outputName by remember { mutableStateOf("Scan_Document") }
    var pageSize by remember { mutableStateOf(PageSizeOption.A4) }
    var passwordEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    var isBusy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("Processing...") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreviewModal by remember { mutableStateOf(false) }

    // AI OCR
    var showAiConsent by remember { mutableStateOf(false) }
    var ocrResult by remember { mutableStateOf<ScannedOcrResult?>(null) }

    BackHandler(enabled = stage != ScannerStage.SOURCE) {
        when (stage) {
            ScannerStage.CROP_PERSPECTIVE -> {
                stage = if (scannedPages.isNotEmpty()) ScannerStage.FILTER_AND_EXPORT else ScannerStage.SOURCE
            }
            ScannerStage.FILTER_AND_EXPORT -> {
                stage = ScannerStage.SOURCE
            }
            ScannerStage.SOURCE -> onBack()
        }
    }

    fun startEdgeDetectionForBitmap(bmp: Bitmap) {
        scope.launch {
            isBusy = true
            busyMsg = "Detecting document edges..."
            val corners = withContext(Dispatchers.Default) {
                DocumentEdgeDetector.detectDocumentCorners(bmp)
            }
            rawCapturedBitmap = bmp
            detectedCorners = corners
            stage = ScannerStage.CROP_PERSPECTIVE
            isBusy = false
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                busyMsg = "Loading image..."
                val bmp = filesViewModel.repository.decodeBitmapFromUri(uri, maxDim = 2000)
                isBusy = false
                if (bmp != null) {
                    startEdgeDetectionForBitmap(bmp)
                } else {
                    errorMsg = "Could not load selected image."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = when (stage) {
                    ScannerStage.SOURCE -> "Document Scanner"
                    ScannerStage.CROP_PERSPECTIVE -> "Perspective Crop"
                    ScannerStage.FILTER_AND_EXPORT -> "Enhance & Save (${scannedPages.size})"
                },
                subtitle = when (stage) {
                    ScannerStage.SOURCE -> "Camera scan or gallery import"
                    ScannerStage.CROP_PERSPECTIVE -> "Drag corners to refine auto-detected edges"
                    ScannerStage.FILTER_AND_EXPORT -> "10 document filters & HD scan"
                },
                onBack = {
                    if (stage == ScannerStage.SOURCE) onBack()
                    else stage = ScannerStage.SOURCE
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (stage) {
                ScannerStage.SOURCE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (useCamera && (accessGate?.isCameraGranted == true)) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.Black)
                            ) {
                                CameraXScannerPreview(
                                    flashEnabled = flashEnabled,
                                    onImageCaptureReady = { imageCaptureRef = it },
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Viewfinder guide frame
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(28.dp)
                                        .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                )

                                // Top flash toggle
                                IconButton(
                                    onClick = { flashEnabled = !flashEnabled },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                ) {
                                    Icon(
                                        imageVector = if (flashEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                        contentDescription = "Toggle flash",
                                        tint = Color.White
                                    )
                                }

                                // Shutter bar
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            val launchGallery = {
                                                galleryPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            }
                                            accessGate.requestStorageAccess(
                                                AccessSpec.ScopedStorage.PhotoPicker,
                                                onProceed = launchGallery
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Pick from gallery")
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White,
                                        border = BorderStroke(4.dp, MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                val capture = imageCaptureRef ?: return@clickable
                                                isBusy = true
                                                busyMsg = "Capturing scan..."
                                                val outFile = File(filesViewModel.repository.scanTempDir, "cap_${System.currentTimeMillis()}.jpg")
                                                val opts = ImageCapture.OutputFileOptions.Builder(outFile).build()
                                                capture.takePicture(
                                                    opts,
                                                    ContextCompat.getMainExecutor(context),
                                                    object : ImageCapture.OnImageSavedCallback {
                                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                            scope.launch {
                                                                val bmp = withContext(Dispatchers.IO) {
                                                                    BitmapFactory.decodeFile(outFile.absolutePath)
                                                                }
                                                                isBusy = false
                                                                if (bmp != null) {
                                                                    startEdgeDetectionForBitmap(bmp)
                                                                } else {
                                                                    errorMsg = "Could not decode captured photo."
                                                                }
                                                            }
                                                        }

                                                        override fun onError(exception: ImageCaptureException) {
                                                            isBusy = false
                                                            errorMsg = exception.localizedMessage ?: "Camera capture failed."
                                                        }
                                                    }
                                                )
                                            }
                                            .testTag("camera_shutter_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.CameraAlt,
                                                contentDescription = "Capture document",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }

                                    FilledTonalIconButton(onClick = { useCamera = false }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Close camera")
                                    }
                                }
                            }
                        } else {
                            // Clean source launcher card (no permission requested until user taps Camera Scan!)
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DocumentScanner,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Text("Smart Document Scanner", style = MaterialTheme.typography.headlineSmall)
                                    Text(
                                        text = "Automatic edge detection, 4-corner perspective correction, and 10 studio scan filters completely offline.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Button(
                                        onClick = {
                                            val launchGallery = {
                                                galleryPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            }
                                            if (accessGate != null) {
                                                accessGate.requestCameraAccess(
                                                    onFallbackGallery = {
                                                        accessGate.requestStorageAccess(
                                                            AccessSpec.ScopedStorage.PhotoPicker,
                                                            onProceed = launchGallery
                                                        )
                                                    },
                                                    onGranted = { useCamera = true }
                                                )
                                            } else {
                                                useCamera = true
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("start_camera_scan_button"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Open Camera Scanner")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            val launchGallery = {
                                                galleryPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            }
                                            if (accessGate != null) {
                                                accessGate.requestStorageAccess(
                                                    AccessSpec.ScopedStorage.PhotoPicker,
                                                    onProceed = launchGallery
                                                )
                                            } else {
                                                launchGallery()
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("scan_from_gallery_button"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Scan Photo from Gallery")
                                    }

                                    if (scannedPages.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { stage = ScannerStage.FILTER_AND_EXPORT },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("Return to Current Scan (${scannedPages.size} pages)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ScannerStage.CROP_PERSPECTIVE -> {
                    val rawBmp = rawCapturedBitmap
                    if (rawBmp != null && detectedCorners.size == 4) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF0F172A))
                            ) {
                                PerspectiveCropOverlay(
                                    bitmap = rawBmp,
                                    corners = detectedCorners,
                                    onCornersChange = { detectedCorners = it },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            detectedCorners = withContext(Dispatchers.Default) {
                                                DocumentEdgeDetector.detectDocumentCorners(rawBmp)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Auto-Detect")
                                }
                                OutlinedButton(
                                    onClick = {
                                        detectedCorners = listOf(
                                            PointF(0f, 0f),
                                            PointF(1f, 0f),
                                            PointF(1f, 1f),
                                            PointF(0f, 1f)
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Full Frame")
                                }
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        busyMsg = "Applying perspective correction & HD scan..."
                                        val warped = withContext(Dispatchers.Default) {
                                            DocumentEdgeDetector.perspectiveWarp(rawBmp, detectedCorners)
                                        }
                                        val previewScaled = ImageFilterEngine.scaleForPreview(warped, maxDim = 1500)
                                        val defaultFilter = DocumentFilter.AUTO_ENHANCE
                                        val filtered = withContext(Dispatchers.Default) {
                                            ImageFilterEngine.applyDocumentFilter(previewScaled, defaultFilter)
                                        }
                                        scannedPages.add(
                                            ScannedDocPage(
                                                id = "${System.nanoTime()}",
                                                warpedBitmap = previewScaled,
                                                filter = defaultFilter,
                                                filteredBitmap = filtered
                                            )
                                        )
                                        activePageIndex = scannedPages.lastIndex
                                        stage = ScannerStage.FILTER_AND_EXPORT
                                        isBusy = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("apply_perspective_crop_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Crop & Enhance")
                            }
                        }
                    }
                }

                ScannerStage.FILTER_AND_EXPORT -> {
                    val current = scannedPages.getOrNull(activePageIndex)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (current != null) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                shadowElevation = 3.dp,
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 240.dp, max = 380.dp)
                            ) {
                                Image(
                                    bitmap = current.filteredBitmap.asImageBitmap(),
                                    contentDescription = "Scanned page ${activePageIndex + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                )
                            }

                            // Multi-page thumbnails + Add page
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                itemsIndexed(scannedPages, key = { _, p -> p.id }) { idx, page ->
                                    val selected = idx == activePageIndex
                                    Box(
                                        modifier = Modifier
                                            .size(width = 62.dp, height = 80.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                width = if (selected) 2.5.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable { activePageIndex = idx }
                                    ) {
                                        Image(
                                            bitmap = page.filteredBitmap.asImageBitmap(),
                                            contentDescription = "Page ${idx + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(3.dp)
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = { stage = ScannerStage.SOURCE },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.height(80.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                            Text("Add", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // 10 Document Filters
                            Text("Document Filters (10)", style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(DocumentFilter.entries) { filter ->
                                    FilterChip(
                                        selected = current.filter == filter,
                                        onClick = {
                                            scope.launch {
                                                isBusy = true
                                                busyMsg = "Applying ${filter.label}..."
                                                val updated = withContext(Dispatchers.Default) {
                                                    ImageFilterEngine.applyDocumentFilter(current.warpedBitmap, filter)
                                                }
                                                scannedPages[activePageIndex] = current.copy(
                                                    filter = filter,
                                                    filteredBitmap = updated
                                                )
                                                isBusy = false
                                            }
                                        },
                                        label = { Text(filter.label) },
                                        modifier = Modifier.testTag("doc_filter_${filter.name.lowercase()}")
                                    )
                                }
                            }

                            // Opt-in Online AI OCR & Smart Rename
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column {
                                            Text("AI Smart OCR & Title", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Extract text & suggest filename (opt-in)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    FilledTonalButton(onClick = { showAiConsent = true }) {
                                        Text("Analyze")
                                    }
                                }
                            }

                            ocrResult?.let { ocr ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("Extracted Text (${ocr.suggestedTitle})", style = MaterialTheme.typography.titleSmall)
                                        Text(ocr.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            ocr.extractedText.take(600),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = outputName,
                                onValueChange = { outputName = it },
                                label = { Text("PDF File Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            Text("Page Size", style = MaterialTheme.typography.titleSmall)
                            PageSizeSelector(selected = pageSize, onSelect = { pageSize = it })

                            PasswordProtectCard(
                                enabled = passwordEnabled,
                                password = password,
                                onEnabledChange = { passwordEnabled = it },
                                onPasswordChange = { password = it }
                            )

                            errorMsg?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        if (passwordEnabled && password.isBlank()) {
                                            errorMsg = "Enter a password or disable password protection."
                                            return@launch
                                        }
                                        isBusy = true
                                        busyMsg = "Saving scanned PDF..."
                                        errorMsg = null
                                        runCatching {
                                            val outFile = filesViewModel.repository.uniquePdfFile(outputName)
                                            PdfEngine.createPdfFromImages(
                                                context = context,
                                                images = scannedPages.map { it.filteredBitmap },
                                                outputFile = outFile,
                                                pageSize = pageSize,
                                                marginPt = 16f,
                                                password = if (passwordEnabled) password else null
                                            )
                                        }.onSuccess { saved ->
                                            resultFile = saved
                                            filesViewModel.refreshFiles()
                                        }.onFailure { err ->
                                            errorMsg = err.localizedMessage ?: "Failed to save PDF."
                                        }
                                        isBusy = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("save_scanned_pdf_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Save Scan as PDF (${scannedPages.size} Pages)")
                            }

                            resultFile?.let { file ->
                                ResultSuccessBanner(
                                    file = file,
                                    subtitle = "${pageSize.label} • ${scannedPages.size} scanned page(s)",
                                    onOpen = { showPreviewModal = true },
                                    onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                                )
                            }
                        }
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = busyMsg)
        }
    }

    if (showAiConsent) {
        val targetPage = scannedPages.getOrNull(activePageIndex)
        AiConsentDialog(
            featureTitle = "AI Smart OCR & Title",
            dataDescription = "Only the currently selected scanned page image will be uploaded to Google Gemini AI to extract text and suggest a title.",
            onConfirm = {
                showAiConsent = false
                if (targetPage != null) {
                    scope.launch {
                        isBusy = true
                        busyMsg = "Extracting text with Gemini AI..."
                        errorMsg = null
                        aiService.extractDocumentOcr(targetPage.filteredBitmap)
                            .onSuccess { res ->
                                ocrResult = res
                                if (res.suggestedTitle.isNotBlank()) {
                                    outputName = res.suggestedTitle
                                }
                            }
                            .onFailure { err ->
                                errorMsg = err.localizedMessage ?: "Online AI OCR failed."
                            }
                        isBusy = false
                    }
                }
            },
            onDismiss = { showAiConsent = false }
        )
    }

    if (showPreviewModal && resultFile != null) {
        FilePreviewModal(
            file = resultFile!!,
            initialPassword = if (passwordEnabled) password else null,
            onDismiss = { showPreviewModal = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(resultFile!!)) }
        )
    }
}

@Composable
private fun CameraXScannerPreview(
    flashEnabled: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    LaunchedEffect(flashEnabled) {
        imageCapture.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                onImageCaptureReady(imageCapture)
            } catch (_: Exception) {
            }
        }, executor)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun PerspectiveCropOverlay(
    bitmap: Bitmap,
    corners: List<PointF>,
    onCornersChange: (List<PointF>) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCornerIdx by remember { mutableIntStateOf(-1) }
    val latestCorners by rememberUpdatedState(corners)

    Box(
        modifier = modifier.onSizeChanged { boxSize = it }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Document edges preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        if (boxSize.width > 0 && boxSize.height > 0 && corners.size == 4) {
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val vw = boxSize.width.toFloat()
            val vh = boxSize.height.toFloat()
            val scale = minOf(vw / bw, vh / bh)
            val drawW = bw * scale
            val drawH = bh * scale
            val offsetX = (vw - drawW) / 2f
            val offsetY = (vh - drawH) / 2f

            fun normToScreen(pt: PointF) = Offset(
                offsetX + pt.x * drawW,
                offsetY + pt.y * drawH
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(boxSize, bitmap) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val screenPts = latestCorners.map { normToScreen(it) }
                                var bestIdx = -1
                                var bestDist = 96f
                                screenPts.forEachIndexed { idx, p ->
                                    val d = hypot(p.x - start.x, p.y - start.y)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = idx
                                    }
                                }
                                activeCornerIdx = bestIdx
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val idx = activeCornerIdx
                                if (idx in 0..3) {
                                    val cur = latestCorners[idx]
                                    val nx = (cur.x + dragAmount.x / drawW).coerceIn(0.01f, 0.99f)
                                    val ny = (cur.y + dragAmount.y / drawH).coerceIn(0.01f, 0.99f)
                                    val updated = latestCorners.toMutableList()
                                    updated[idx] = PointF(nx, ny)
                                    onCornersChange(updated)
                                }
                            },
                            onDragEnd = { activeCornerIdx = -1 },
                            onDragCancel = { activeCornerIdx = -1 }
                        )
                    }
            ) {
                val pts = corners.map { normToScreen(it) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    lineTo(pts[1].x, pts[1].y)
                    lineTo(pts[2].x, pts[2].y)
                    lineTo(pts[3].x, pts[3].y)
                    close()
                }
                drawPath(path, color = Color(0x280EA5E9))
                drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 5f))

                pts.forEachIndexed { idx, p ->
                    drawCircle(
                        color = Color.White,
                        radius = if (idx == activeCornerIdx) 24f else 18f,
                        center = p
                    )
                    drawCircle(
                        color = Color(0xFF1565C0),
                        radius = if (idx == activeCornerIdx) 18f else 13f,
                        center = p
                    )
                }
            }
        }
    }
}
