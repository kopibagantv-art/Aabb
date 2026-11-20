package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.ImageExportFormat
import com.example.core.model.LocalFileItem
import com.example.core.pdf.PdfEngine
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PdfToImageScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by filesViewModel.files.collectAsState()

    var selectedFile by remember { mutableStateOf<LocalFileItem?>(null) }
    var sourcePassword by remember { mutableStateOf<String?>(null) }
    var needsUnlock by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(true) }

    var thumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var exportFormat by remember { mutableStateOf(ImageExportFormat.PNG) }
    var dpi by remember { mutableIntStateOf(200) }

    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var exportedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    fun loadPdf(item: LocalFileItem, pw: String?) {
        scope.launch {
            isBusy = true
            errorMsg = null
            val info = PdfEngine.inspectPdf(context, item.file)
            if (info.isEncrypted && pw == null) {
                needsUnlock = true
                isBusy = false
                return@launch
            }
            try {
                val rendered = PdfEngine.renderPdfPages(context, item.file, pw, targetDpiWidth = 360)
                thumbnails = rendered
                selectedIndices.clear()
                selectedIndices.addAll(rendered.indices)
                exportedImages = emptyList()
                needsUnlock = false
            } catch (e: Exception) {
                if (info.isEncrypted) {
                    unlockError = "Incorrect password."
                    needsUnlock = true
                } else {
                    errorMsg = e.localizedMessage ?: "Failed to load PDF."
                }
            }
            isBusy = false
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = filesViewModel.importExternalPdf(uri)
                if (imported != null) {
                    selectedFile = imported
                    sourcePassword = null
                    showPicker = false
                    loadPdf(imported, null)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "PDF to Image",
                subtitle = selectedFile?.name ?: "Export pages as PNG or JPEG",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Pick PDF")
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
            if (thumbnails.isEmpty() && !isBusy) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = { showPicker = true }, shape = RoundedCornerShape(14.dp)) {
                        Text("Select PDF to Export")
                    }
                }
            } else if (thumbnails.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pages to Export (${selectedIndices.size}/${thumbnails.size})", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            if (selectedIndices.size == thumbnails.size) selectedIndices.clear()
                            else {
                                selectedIndices.clear()
                                selectedIndices.addAll(thumbnails.indices)
                            }
                        }) {
                            Text(if (selectedIndices.size == thumbnails.size) "Clear All" else "Select All")
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(thumbnails) { idx, bmp ->
                            val selected = idx in selectedIndices
                            Box(
                                modifier = Modifier
                                    .size(width = 86.dp, height = 114.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        if (selected) selectedIndices.remove(idx) else selectedIndices.add(idx)
                                    }
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "P.${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text("Image Format", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImageExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = exportFormat == fmt,
                                onClick = { exportFormat = fmt },
                                label = { Text(fmt.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Text("Resolution", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(150 to "Standard (150 DPI)", 300 to "HD (300 DPI)").forEach { (valDpi, label) ->
                            FilterChip(
                                selected = dpi == valDpi,
                                onClick = { dpi = valDpi },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Button(
                        onClick = {
                            val src = selectedFile ?: return@Button
                            scope.launch {
                                if (selectedIndices.isEmpty()) {
                                    errorMsg = "Select at least one page to export."
                                    return@launch
                                }
                                isBusy = true
                                errorMsg = null
                                runCatching {
                                    PdfEngine.pdfToImages(
                                        context = context,
                                        inputFile = src.file,
                                        password = sourcePassword,
                                        pageIndices = selectedIndices.sorted(),
                                        format = exportFormat,
                                        dpi = dpi,
                                        outputDir = filesViewModel.repository.imageDir,
                                        baseName = "${src.name.substringBeforeLast(".")}_${System.currentTimeMillis() % 10000}"
                                    )
                                }.onSuccess { outs ->
                                    exportedImages = outs
                                    filesViewModel.refreshFiles()
                                }.onFailure { err ->
                                    errorMsg = err.localizedMessage ?: "Export failed."
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("export_pdf_images_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.BurstMode, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export ${selectedIndices.size} Image(s)")
                    }

                    exportedImages.forEach { imgFile ->
                        ResultSuccessBanner(
                            file = imgFile,
                            subtitle = "${exportFormat.extension.uppercase()} • $dpi DPI",
                            onOpen = { previewFile = imgFile },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(imgFile)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = "Exporting PDF pages as images...")
        }
    }

    if (showPicker) {
        SelectPdfSourceDialog(
            title = "Select PDF to Convert",
            files = files,
            onSelectLocalFile = { item ->
                selectedFile = item
                sourcePassword = null
                showPicker = false
                loadPdf(item, null)
            },
            onBrowseDevice = {
                showPicker = false
                openDocLauncher.launch(arrayOf("application/pdf"))
            },
            onDismiss = { showPicker = false }
        )
    }

    if (needsUnlock && selectedFile != null) {
        PasswordUnlockDialog(
            fileName = selectedFile!!.name,
            errorText = unlockError,
            onUnlock = { pw ->
                unlockError = null
                sourcePassword = pw
                loadPdf(selectedFile!!, pw)
            },
            onDismiss = { needsUnlock = false }
        )
    }

    previewFile?.let { f ->
        FilePreviewModal(
            file = f,
            onDismiss = { previewFile = null },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(f)) }
        )
    }
}
