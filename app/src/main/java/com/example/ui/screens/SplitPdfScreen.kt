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
import androidx.compose.material.icons.automirrored.rounded.CallSplit
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
import com.example.core.model.LocalFileItem
import com.example.core.pdf.PdfEngine
import com.example.core.pdf.SplitMode
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.io.File

private enum class SplitTab(val label: String) {
    SELECT_PAGES("Select Pages"),
    RANGES("Page Ranges"),
    EVERY_N("Every N Pages")
}

@Composable
fun SplitPdfScreen(
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
    val selectedPages = remember { mutableStateListOf<Int>() }
    var splitTab by remember { mutableStateOf(SplitTab.SELECT_PAGES) }
    var rangeText by remember { mutableStateOf("1-2") }
    var everyNText by remember { mutableStateOf("1") }

    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var createdFiles by remember { mutableStateOf<List<File>>(emptyList()) }
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
                selectedPages.clear()
                if (rendered.isNotEmpty()) selectedPages.add(0)
                rangeText = if (rendered.size > 1) "1-${rendered.size / 2}, ${rendered.size / 2 + 1}-${rendered.size}" else "1"
                createdFiles = emptyList()
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
                title = "Split PDF",
                subtitle = selectedFile?.name ?: "Choose a PDF to split",
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
                        Text("Select PDF to Split")
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
                    TabRow(
                        selectedTabIndex = splitTab.ordinal,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    ) {
                        SplitTab.entries.forEach { t ->
                            Tab(
                                selected = splitTab == t,
                                onClick = { splitTab = t },
                                text = { Text(t.label, maxLines = 1) }
                            )
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(thumbnails) { idx, bmp ->
                            val isSelected = idx in selectedPages
                            Box(
                                modifier = Modifier
                                    .size(width = 86.dp, height = 114.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isSelected && splitTab == SplitTab.SELECT_PAGES) 2.5.dp else 1.dp,
                                        color = if (isSelected && splitTab == SplitTab.SELECT_PAGES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable(enabled = splitTab == SplitTab.SELECT_PAGES) {
                                        if (idx in selectedPages) selectedPages.remove(idx) else selectedPages.add(idx)
                                    }
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = if (isSelected && splitTab == SplitTab.SELECT_PAGES) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.65f),
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

                    when (splitTab) {
                        SplitTab.SELECT_PAGES -> {
                            Text(
                                text = "Tap pages above to extract (${selectedPages.size} selected)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        SplitTab.RANGES -> {
                            OutlinedTextField(
                                value = rangeText,
                                onValueChange = { rangeText = it },
                                label = { Text("Page Ranges (comma-separated)") },
                                placeholder = { Text("e.g. 1-3, 4-6") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        SplitTab.EVERY_N -> {
                            OutlinedTextField(
                                value = everyNText,
                                onValueChange = { everyNText = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Pages per output PDF") },
                                placeholder = { Text("e.g. 1 to burst every page") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Button(
                        onClick = {
                            val src = selectedFile ?: return@Button
                            scope.launch {
                                isBusy = true
                                errorMsg = null
                                val mode = when (splitTab) {
                                    SplitTab.SELECT_PAGES -> SplitMode.ExtractPages(selectedPages.sorted())
                                    SplitTab.RANGES -> SplitMode.PageRanges(rangeText)
                                    SplitTab.EVERY_N -> SplitMode.EveryNPages(everyNText.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                                }
                                runCatching {
                                    PdfEngine.splitPdf(
                                        context = context,
                                        inputFile = src.file,
                                        password = sourcePassword,
                                        mode = mode,
                                        outputDir = filesViewModel.repository.pdfDir,
                                        baseName = "${src.name.substringBeforeLast(".")}_split_${System.currentTimeMillis() % 10000}"
                                    )
                                }.onSuccess { outs ->
                                    createdFiles = outs
                                    filesViewModel.refreshFiles()
                                }.onFailure { err ->
                                    errorMsg = err.localizedMessage ?: "Split failed."
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("split_execute_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Split PDF Now")
                    }

                    createdFiles.forEach { out ->
                        ResultSuccessBanner(
                            file = out,
                            subtitle = "Split output saved",
                            onOpen = { previewFile = out },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(out)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = "Splitting PDF...")
        }
    }

    if (showPicker) {
        SelectPdfSourceDialog(
            title = "Select PDF to Split",
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
