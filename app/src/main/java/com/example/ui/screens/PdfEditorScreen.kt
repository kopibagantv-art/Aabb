package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.core.firebase.AiEnhancementService
import com.example.core.model.LocalFileItem
import com.example.core.pdf.EditablePageSpec
import com.example.core.pdf.PdfEngine
import com.example.core.pdf.SignatureOverlay
import com.example.core.pdf.TextOverlay
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private data class EditorPageItem(
    val uid: String,
    val originalIndex: Int,
    val thumbnail: Bitmap,
    val rotationDeg: Int = 0
)

private data class DraftSignature(
    val uid: String,
    val pageUid: String,
    val bitmap: Bitmap,
    val normX: Float = 0.72f,
    val normY: Float = 0.80f,
    val normWidth: Float = 0.30f
)

private data class DraftTextNote(
    val uid: String,
    val pageUid: String,
    val text: String,
    val normX: Float = 0.14f,
    val normY: Float = 0.18f
)

@Composable
fun PdfEditorScreen(
    filesViewModel: FilesViewModel,
    initialFile: LocalFileItem? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by filesViewModel.files.collectAsState()
    val aiService = remember(context) { AiEnhancementService(context) }

    var selectedFile by remember { mutableStateOf(initialFile) }
    var sourcePassword by remember { mutableStateOf<String?>(null) }
    var needsUnlock by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var showSourcePicker by remember { mutableStateOf(initialFile == null) }

    val pages = remember { mutableStateListOf<EditorPageItem>() }
    var activeIndex by remember { mutableIntStateOf(0) }
    val signatures = remember { mutableStateListOf<DraftSignature>() }
    val textNotes = remember { mutableStateListOf<DraftTextNote>() }

    var showSignaturePad by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var outputName by remember { mutableStateOf("") }
    var protectEnabled by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }

    var showAiConsent by remember { mutableStateOf(false) }
    var aiSummary by remember { mutableStateOf<String?>(null) }

    var isBusy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("Loading PDF...") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreviewModal by remember { mutableStateOf(false) }

    fun loadPdfPages(item: LocalFileItem, pw: String?) {
        scope.launch {
            isBusy = true
            busyMsg = "Rendering PDF pages..."
            errorMsg = null
            val info = PdfEngine.inspectPdf(context, item.file)
            if (info.isEncrypted && pw == null) {
                needsUnlock = true
                isBusy = false
                return@launch
            }
            try {
                val rendered = PdfEngine.renderPdfPages(context, item.file, pw, targetDpiWidth = 680)
                pages.clear()
                signatures.clear()
                textNotes.clear()
                rendered.forEachIndexed { idx, bmp ->
                    pages.add(EditorPageItem(uid = "p_${idx}_${System.nanoTime()}", originalIndex = idx, thumbnail = bmp))
                }
                activeIndex = 0
                outputName = "${item.name.substringBeforeLast(".")}_edited"
                needsUnlock = false
                resultFile = null
                aiSummary = null
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

    LaunchedEffect(initialFile) {
        if (initialFile != null) {
            loadPdfPages(initialFile, null)
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
                    showSourcePicker = false
                    loadPdfPages(imported, null)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "PDF Editor",
                subtitle = selectedFile?.name ?: "Select a PDF to edit",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showSourcePicker = true }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Choose another PDF")
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
            if (pages.isEmpty() && !isBusy) {
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
                                imageVector = Icons.Rounded.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(52.dp)
                            )
                            Text("Edit, Sign & Protect", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = "Reorder or rotate pages, place your handwritten digital signature, add notes, and lock PDFs with a password.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showSourcePicker = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("pdf_editor_choose_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Choose PDF")
                            }
                        }
                    }
                }
            } else if (pages.isNotEmpty()) {
                val activePage = pages.getOrNull(activeIndex)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Active Page Interactive Canvas
                    if (activePage != null) {
                        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                        val pageSigs = signatures.filter { it.pageUid == activePage.uid }
                        val pageNotes = textNotes.filter { it.pageUid == activePage.uid }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            shadowElevation = 3.dp,
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp, max = 400.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { canvasSize = it }
                            ) {
                                Image(
                                    bitmap = activePage.thumbnail.asImageBitmap(),
                                    contentDescription = "Editing page ${activeIndex + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Draggable Signature Overlays
                                pageSigs.forEach { sig ->
                                    val sigIdx = signatures.indexOfFirst { it.uid == sig.uid }
                                    val wPx = (canvasSize.width * sig.normWidth).roundToInt().coerceAtLeast(60)
                                    val aspect = sig.bitmap.height.toFloat() / sig.bitmap.width.coerceAtLeast(1)
                                    val hPx = (wPx * aspect).roundToInt().coerceAtLeast(30)
                                    val offX = (sig.normX * canvasSize.width - wPx / 2f).roundToInt()
                                    val offY = (sig.normY * canvasSize.height - hPx / 2f).roundToInt()

                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset(offX, offY) }
                                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                            .pointerInput(sig.uid, canvasSize) {
                                                detectDragGestures { change, drag ->
                                                    change.consume()
                                                    if (sigIdx >= 0 && canvasSize.width > 0 && canvasSize.height > 0) {
                                                        val cur = signatures[sigIdx]
                                                        signatures[sigIdx] = cur.copy(
                                                            normX = (cur.normX + drag.x / canvasSize.width).coerceIn(0.1f, 0.9f),
                                                            normY = (cur.normY + drag.y / canvasSize.height).coerceIn(0.1f, 0.9f)
                                                        )
                                                    }
                                                }
                                            }
                                    ) {
                                        Image(
                                            bitmap = sig.bitmap.asImageBitmap(),
                                            contentDescription = "Digital signature overlay",
                                            modifier = Modifier.size(
                                                width = with(androidx.compose.ui.platform.LocalDensity.current) { wPx.toDp() },
                                                height = with(androidx.compose.ui.platform.LocalDensity.current) { hPx.toDp() }
                                            )
                                        )
                                    }
                                }

                                // Draggable Text Note Overlays
                                pageNotes.forEach { note ->
                                    val noteIdx = textNotes.indexOfFirst { it.uid == note.uid }
                                    val offX = (note.normX * canvasSize.width).roundToInt()
                                    val offY = (note.normY * canvasSize.height).roundToInt()
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .offset { IntOffset(offX, offY) }
                                            .pointerInput(note.uid, canvasSize) {
                                                detectDragGestures { change, drag ->
                                                    change.consume()
                                                    if (noteIdx >= 0 && canvasSize.width > 0 && canvasSize.height > 0) {
                                                        val cur = textNotes[noteIdx]
                                                        textNotes[noteIdx] = cur.copy(
                                                            normX = (cur.normX + drag.x / canvasSize.width).coerceIn(0.05f, 0.85f),
                                                            normY = (cur.normY + drag.y / canvasSize.height).coerceIn(0.05f, 0.92f)
                                                        )
                                                    }
                                                }
                                            }
                                    ) {
                                        Text(
                                            text = note.text,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Page Reorder, Rotate & Delete Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (activeIndex > 0) {
                                        val moved = pages.removeAt(activeIndex)
                                        pages.add(activeIndex - 1, moved)
                                        activeIndex--
                                    }
                                },
                                enabled = activeIndex > 0,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Move page left", modifier = Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    if (activeIndex < pages.lastIndex) {
                                        val moved = pages.removeAt(activeIndex)
                                        pages.add(activeIndex + 1, moved)
                                        activeIndex++
                                    }
                                },
                                enabled = activeIndex < pages.lastIndex,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Move page right", modifier = Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    val m = android.graphics.Matrix().apply { postRotate(90f) }
                                    val rotatedBmp = Bitmap.createBitmap(
                                        activePage.thumbnail,
                                        0, 0,
                                        activePage.thumbnail.width,
                                        activePage.thumbnail.height,
                                        m, true
                                    )
                                    pages[activeIndex] = activePage.copy(
                                        thumbnail = rotatedBmp,
                                        rotationDeg = (activePage.rotationDeg + 90) % 360
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "Rotate 90 degrees", modifier = Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    if (pages.size > 1) {
                                        val removedUid = pages[activeIndex].uid
                                        pages.removeAt(activeIndex)
                                        signatures.removeAll { it.pageUid == removedUid }
                                        textNotes.removeAll { it.pageUid == removedUid }
                                        activeIndex = activeIndex.coerceAtMost(pages.lastIndex)
                                    }
                                },
                                enabled = pages.size > 1,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete page",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Page Thumbnail Reorder Strip
                    Text("Pages (${pages.size}) — Tap to select", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(pages, key = { _, p -> p.uid }) { idx, page ->
                            val selected = idx == activeIndex
                            Box(
                                modifier = Modifier
                                    .size(width = 62.dp, height = 82.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { activeIndex = idx }
                            ) {
                                Image(
                                    bitmap = page.thumbnail.asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp),
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
                    }

                    // Signature, Text Note, and AI Insights
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { showSignaturePad = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_signature_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sign")
                        }
                        FilledTonalButton(
                            onClick = { showAddTextDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_text_note_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Note")
                        }
                        FilledTonalButton(
                            onClick = { showAiConsent = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_pdf_summary_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("AI Summary")
                        }
                    }

                    aiSummary?.let { summary ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("AI Document Insights", style = MaterialTheme.typography.titleSmall)
                                Text(summary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = outputName,
                        onValueChange = { outputName = it },
                        label = { Text("Saved PDF Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    PasswordProtectCard(
                        enabled = protectEnabled,
                        password = newPassword,
                        onEnabledChange = { protectEnabled = it },
                        onPasswordChange = { newPassword = it }
                    )

                    errorMsg?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            val src = selectedFile ?: return@Button
                            scope.launch {
                                if (protectEnabled && newPassword.isBlank()) {
                                    errorMsg = "Enter a password or disable password protection."
                                    return@launch
                                }
                                isBusy = true
                                busyMsg = "Saving edited PDF..."
                                errorMsg = null
                                runCatching {
                                    val pageIndexByUid = pages.mapIndexed { idx, p -> p.uid to idx }.toMap()
                                    val sigOverlays = signatures.mapNotNull { s ->
                                        val idx = pageIndexByUid[s.pageUid] ?: return@mapNotNull null
                                        SignatureOverlay(idx, s.bitmap, s.normX, s.normY, s.normWidth)
                                    }
                                    val txtOverlays = textNotes.mapNotNull { n ->
                                        val idx = pageIndexByUid[n.pageUid] ?: return@mapNotNull null
                                        TextOverlay(idx, n.text, n.normX, n.normY)
                                    }
                                    val specs = pages.map { EditablePageSpec(it.originalIndex, it.rotationDeg) }
                                    val outFile = filesViewModel.repository.uniquePdfFile(outputName)
                                    PdfEngine.saveEditedPdf(
                                        context = context,
                                        inputFile = src.file,
                                        sourcePassword = sourcePassword,
                                        pageSpecs = specs,
                                        signatures = sigOverlays,
                                        textAnnotations = txtOverlays,
                                        outputFile = outFile,
                                        newPassword = if (protectEnabled) newPassword else null
                                    )
                                }.onSuccess { saved ->
                                    resultFile = saved
                                    filesViewModel.refreshFiles()
                                }.onFailure { err ->
                                    errorMsg = err.localizedMessage ?: "Failed to save edited PDF."
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("save_edited_pdf_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Edited PDF")
                    }

                    resultFile?.let { file ->
                        ResultSuccessBanner(
                            file = file,
                            subtitle = "${pages.size} pages • Saved",
                            onOpen = { showPreviewModal = true },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = busyMsg)
        }
    }

    if (showSourcePicker) {
        SelectPdfSourceDialog(
            title = "Select PDF to Edit",
            files = files,
            onSelectLocalFile = { item ->
                selectedFile = item
                sourcePassword = null
                showSourcePicker = false
                loadPdfPages(item, null)
            },
            onBrowseDevice = { openDocLauncher.launch(arrayOf("application/pdf")) },
            onDismiss = { showSourcePicker = false }
        )
    }

    if (needsUnlock && selectedFile != null) {
        PasswordUnlockDialog(
            fileName = selectedFile!!.name,
            errorText = unlockError,
            onUnlock = { pw ->
                unlockError = null
                sourcePassword = pw
                loadPdfPages(selectedFile!!, pw)
            },
            onDismiss = {
                needsUnlock = false
                if (pages.isEmpty()) showSourcePicker = true
            }
        )
    }

    if (showSignaturePad) {
        val targetPage = pages.getOrNull(activeIndex)
        SignaturePadDialog(
            onConfirmSignature = { bmp ->
                if (targetPage != null) {
                    signatures.add(DraftSignature("sig_${System.nanoTime()}", targetPage.uid, bmp))
                }
                showSignaturePad = false
            },
            onDismiss = { showSignaturePad = false }
        )
    }

    if (showAddTextDialog) {
        val targetPage = pages.getOrNull(activeIndex)
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("Add Text Note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Stamp / Annotation Text") },
                    placeholder = { Text("e.g. APPROVED") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (targetPage != null && noteText.isNotBlank()) {
                            textNotes.add(DraftTextNote("txt_${System.nanoTime()}", targetPage.uid, noteText.trim()))
                        }
                        showAddTextDialog = false
                    },
                    enabled = noteText.isNotBlank()
                ) {
                    Text("Place Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAiConsent && selectedFile != null) {
        AiConsentDialog(
            featureTitle = "AI Document Insights",
            dataDescription = "Extracted text from \"${selectedFile!!.name}\" will be sent to Google Gemini AI to generate a concise summary and action items.",
            onConfirm = {
                showAiConsent = false
                scope.launch {
                    isBusy = true
                    busyMsg = "Summarizing PDF with Gemini AI..."
                    errorMsg = null
                    runCatching {
                        val text = PdfEngine.extractPdfText(context, selectedFile!!.file, sourcePassword)
                        aiService.summarizePdfContent(text)
                    }.onSuccess { result ->
                        result.onSuccess { aiSummary = it }
                            .onFailure { errorMsg = it.localizedMessage ?: "AI summary failed." }
                    }.onFailure { err ->
                        errorMsg = err.localizedMessage ?: "Failed to read PDF text."
                    }
                    isBusy = false
                }
            },
            onDismiss = { showAiConsent = false }
        )
    }

    if (showPreviewModal && resultFile != null) {
        FilePreviewModal(
            file = resultFile!!,
            initialPassword = if (protectEnabled) newPassword else null,
            onDismiss = { showPreviewModal = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(resultFile!!)) }
        )
    }
}
