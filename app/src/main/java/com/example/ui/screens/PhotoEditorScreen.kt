package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.imaging.ImageFilterEngine
import com.example.core.model.NormalizedCropRect
import com.example.core.model.PageSizeOption
import com.example.core.model.PhotoAdjustments
import com.example.core.model.PhotoFilter
import com.example.core.pdf.PdfEngine
import com.example.core.permissions.AccessSpec
import com.example.core.permissions.LocalAccessGate
import com.example.ui.components.BusyOverlay
import com.example.ui.components.FilePreviewModal
import com.example.ui.components.PdfSuperTopBar
import com.example.ui.components.ResultSuccessBanner
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class PhotoEditTab(val label: String) {
    FILTERS("Filters (12)"),
    ADJUST("Adjust (8)"),
    CROP_ROTATE("Crop & Rotate")
}

@Composable
fun PhotoEditorScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessGate = LocalAccessGate.current

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderedPreview by remember { mutableStateOf<Bitmap?>(null) }

    var selectedFilter by remember { mutableStateOf(PhotoFilter.NATURAL) }
    var adjustments by remember { mutableStateOf(PhotoAdjustments()) }
    var activeTab by remember { mutableStateOf(PhotoEditTab.FILTERS) }
    var comparingOriginal by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("Saving...") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                busyMsg = "Loading photo..."
                val full = filesViewModel.repository.decodeBitmapFromUri(uri, maxDim = 2048)
                if (full != null) {
                    originalBitmap = full
                    previewSourceBitmap = ImageFilterEngine.scaleForPreview(full, maxDim = 820)
                    selectedFilter = PhotoFilter.NATURAL
                    adjustments = PhotoAdjustments()
                    resultFile = null
                }
                isBusy = false
            }
        }
    }

    // Recompute preview smoothly when filter or adjustments change
    LaunchedEffect(previewSourceBitmap, selectedFilter, adjustments) {
        val base = previewSourceBitmap ?: return@LaunchedEffect
        delay(65) // light debounce for smooth slider dragging
        val out = withContext(Dispatchers.Default) {
            val filtered = ImageFilterEngine.applyPhotoFilter(base, selectedFilter)
            val adjusted = ImageFilterEngine.applyPhotoAdjustments(filtered, adjustments)
            if (filtered !== base && filtered !== adjusted) filtered.recycle()
            adjusted
        }
        renderedPreview = out
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "Photo Editor",
                subtitle = if (originalBitmap == null) "Select a photo to edit" else selectedFilter.label,
                onBack = onBack,
                actions = {
                    if (originalBitmap != null) {
                        TextButton(
                            onClick = {
                                selectedFilter = PhotoFilter.NATURAL
                                adjustments = PhotoAdjustments()
                            }
                        ) {
                            Text("Reset")
                        }
                    }
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
            if (originalBitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.widthIn(max = 460.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(52.dp)
                            )
                            Text("Pro Photo Studio", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = "12 studio filters (DSLR, Portrait, HDR, Cinematic, Vintage, Beauty, Background Blur...) and 8 precision adjustments.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    val launchPicker = {
                                        picker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                    if (accessGate != null) {
                                        accessGate.requestStorageAccess(
                                            AccessSpec.ScopedStorage.PhotoPicker,
                                            onProceed = launchPicker
                                        )
                                    } else {
                                        launchPicker()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("photo_editor_pick_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose Photo")
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Canvas Preview with Hold-to-Compare
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0F172A))
                    ) {
                        val displayBmp = if (comparingOriginal) previewSourceBitmap else (renderedPreview ?: previewSourceBitmap)
                        displayBmp?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Edited photo preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            comparingOriginal = true
                                            tryAwaitRelease()
                                            comparingOriginal = false
                                        }
                                    )
                                }
                        ) {
                            Text(
                                text = if (comparingOriginal) "Original" else "Hold to Compare",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                                val launchPicker = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                if (accessGate != null) {
                                    accessGate.requestStorageAccess(
                                        AccessSpec.ScopedStorage.PhotoPicker,
                                        onProceed = launchPicker
                                    )
                                } else {
                                    launchPicker()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Change photo")
                        }
                    }

                    // Mode Tabs
                    TabRow(
                        selectedTabIndex = activeTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    ) {
                        PhotoEditTab.entries.forEach { tab ->
                            Tab(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                text = { Text(tab.label, maxLines = 1) }
                            )
                        }
                    }

                    when (activeTab) {
                        PhotoEditTab.FILTERS -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(PhotoFilter.entries) { filter ->
                                    FilterChip(
                                        selected = selectedFilter == filter,
                                        onClick = { selectedFilter = filter },
                                        label = { Text(filter.label) },
                                        modifier = Modifier.testTag("photo_filter_${filter.name.lowercase()}")
                                    )
                                }
                            }
                        }

                        PhotoEditTab.ADJUST -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AdjustmentSlider("Brightness", adjustments.brightness, -100f..100f) {
                                    adjustments = adjustments.copy(brightness = it)
                                }
                                AdjustmentSlider("Contrast", adjustments.contrast, -100f..100f) {
                                    adjustments = adjustments.copy(contrast = it)
                                }
                                AdjustmentSlider("Saturation", adjustments.saturation, -100f..100f) {
                                    adjustments = adjustments.copy(saturation = it)
                                }
                                AdjustmentSlider("Sharpness", adjustments.sharpness, 0f..100f) {
                                    adjustments = adjustments.copy(sharpness = it)
                                }
                                AdjustmentSlider("Exposure", adjustments.exposure, -100f..100f) {
                                    adjustments = adjustments.copy(exposure = it)
                                }
                                AdjustmentSlider("Highlights", adjustments.highlights, -100f..100f) {
                                    adjustments = adjustments.copy(highlights = it)
                                }
                                AdjustmentSlider("Shadows", adjustments.shadows, -100f..100f) {
                                    adjustments = adjustments.copy(shadows = it)
                                }
                            }
                        }

                        PhotoEditTab.CROP_ROTATE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            adjustments = adjustments.copy(
                                                rotationDegrees = (adjustments.rotationDegrees - 90 + 360) % 360
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("-90°")
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            adjustments = adjustments.copy(
                                                rotationDegrees = (adjustments.rotationDegrees + 90) % 360
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("+90°")
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            adjustments = adjustments.copy(flipHorizontal = !adjustments.flipHorizontal)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.Flip, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Flip")
                                    }
                                }

                                Text("Quick Crop Ratio", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val src = previewSourceBitmap
                                    AssistChip(
                                        onClick = { adjustments = adjustments.copy(cropRect = NormalizedCropRect()) },
                                        label = { Text("Original") }
                                    )
                                    AssistChip(
                                        onClick = {
                                            if (src != null) {
                                                adjustments = adjustments.copy(
                                                    cropRect = centeredRatioCrop(src.width, src.height, 1f)
                                                )
                                            }
                                        },
                                        label = { Text("1:1") }
                                    )
                                    AssistChip(
                                        onClick = {
                                            if (src != null) {
                                                adjustments = adjustments.copy(
                                                    cropRect = centeredRatioCrop(src.width, src.height, 4f / 3f)
                                                )
                                            }
                                        },
                                        label = { Text("4:3") }
                                    )
                                    AssistChip(
                                        onClick = {
                                            if (src != null) {
                                                adjustments = adjustments.copy(
                                                    cropRect = centeredRatioCrop(src.width, src.height, 0.707f)
                                                )
                                            }
                                        },
                                        label = { Text("A4") }
                                    )
                                }
                            }
                        }
                    }

                    // Export Actions
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val full = originalBitmap ?: return@Button
                                scope.launch {
                                    isBusy = true
                                    busyMsg = "Rendering full-resolution photo..."
                                    val finalBmp = withContext(Dispatchers.Default) {
                                        val f = ImageFilterEngine.applyPhotoFilter(full, selectedFilter)
                                        val a = ImageFilterEngine.applyPhotoAdjustments(f, adjustments)
                                        if (f !== full && f !== a) f.recycle()
                                        a
                                    }
                                    val saved = filesViewModel.repository.saveBitmapToImageFile(
                                        finalBmp,
                                        "Photo_Edit_${System.currentTimeMillis()}"
                                    )
                                    resultFile = saved
                                    filesViewModel.refreshFiles()
                                    isBusy = false
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("save_edited_photo_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save Image")
                        }

                        OutlinedButton(
                            onClick = {
                                val full = originalBitmap ?: return@OutlinedButton
                                scope.launch {
                                    isBusy = true
                                    busyMsg = "Exporting photo as PDF..."
                                    val finalBmp = withContext(Dispatchers.Default) {
                                        val f = ImageFilterEngine.applyPhotoFilter(full, selectedFilter)
                                        val a = ImageFilterEngine.applyPhotoAdjustments(f, adjustments)
                                        if (f !== full && f !== a) f.recycle()
                                        a
                                    }
                                    val outFile = filesViewModel.repository.uniquePdfFile("Edited_Photo")
                                    PdfEngine.createPdfFromImages(
                                        context = context,
                                        images = listOf(finalBmp),
                                        outputFile = outFile,
                                        pageSize = PageSizeOption.A4
                                    )
                                    resultFile = outFile
                                    filesViewModel.refreshFiles()
                                    isBusy = false
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("export_photo_pdf_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("As PDF")
                        }
                    }

                    resultFile?.let { file ->
                        ResultSuccessBanner(
                            file = file,
                            subtitle = "Saved to My Files",
                            onOpen = { showPreview = true },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = busyMsg)
        }
    }

    if (showPreview && resultFile != null) {
        FilePreviewModal(
            file = resultFile!!,
            onDismiss = { showPreview = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(resultFile!!)) }
        )
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(82.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${value.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(34.dp)
        )
    }
}

private fun centeredRatioCrop(w: Int, h: Int, targetRatio: Float): NormalizedCropRect {
    val currentRatio = w.toFloat() / h.coerceAtLeast(1)
    return if (currentRatio > targetRatio) {
        val normW = targetRatio / currentRatio
        val margin = (1f - normW) / 2f
        NormalizedCropRect(left = margin, top = 0f, right = 1f - margin, bottom = 1f)
    } else {
        val normH = currentRatio / targetRatio
        val margin = (1f - normH) / 2f
        NormalizedCropRect(left = 0f, top = margin, right = 1f, bottom = 1f - margin)
    }
}
