package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.CompressionLevel
import com.example.core.model.LocalFileItem
import com.example.core.pdf.CompressionStats
import com.example.core.pdf.PdfEngine
import com.example.core.storage.formatFileSize
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch

@Composable
fun CompressPdfScreen(
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

    var level by remember { mutableStateOf(CompressionLevel.BALANCED) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<CompressionStats?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = filesViewModel.importExternalPdf(uri)
                if (imported != null) {
                    selectedFile = imported
                    sourcePassword = null
                    stats = null
                    showPicker = false
                    if (imported.isEncrypted) needsUnlock = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "Compress PDF",
                subtitle = selectedFile?.name ?: "Choose a PDF to optimize",
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
            if (selectedFile == null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = { showPicker = true }, shape = RoundedCornerShape(14.dp)) {
                        Text("Select PDF to Compress")
                    }
                }
            } else {
                val fileItem = selectedFile!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(fileItem.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Original Size: ${formatFileSize(fileItem.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { showPicker = true }) { Text("Change") }
                        }
                    }

                    Text("Compression Profile", style = MaterialTheme.typography.titleSmall)
                    CompressionLevel.entries.forEach { option ->
                        val selected = option == level
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { level = option }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = selected, onClick = { level = option })
                                Column {
                                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                                    Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Button(
                        onClick = {
                            scope.launch {
                                if (fileItem.isEncrypted && sourcePassword == null) {
                                    needsUnlock = true
                                    return@launch
                                }
                                isBusy = true
                                errorMsg = null
                                runCatching {
                                    val outFile = filesViewModel.repository.uniquePdfFile("${fileItem.name.substringBeforeLast(".")}_compressed")
                                    PdfEngine.compressPdf(
                                        context = context,
                                        inputFile = fileItem.file,
                                        password = sourcePassword,
                                        level = level,
                                        outputFile = outFile
                                    )
                                }.onSuccess { res ->
                                    stats = res
                                    filesViewModel.refreshFiles()
                                }.onFailure { err ->
                                    errorMsg = err.localizedMessage ?: "Compression failed."
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("compress_execute_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Compress, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Compress PDF")
                    }

                    stats?.let { st ->
                        ResultSuccessBanner(
                            file = st.outputFile,
                            subtitle = "${formatFileSize(st.originalBytes)} -> ${formatFileSize(st.compressedBytes)} (-${st.savedPercent}%)",
                            onOpen = { showPreview = true },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(st.outputFile)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = "Optimizing PDF size...")
        }
    }

    if (showPicker) {
        SelectPdfSourceDialog(
            title = "Select PDF to Compress",
            files = files,
            onSelectLocalFile = { item ->
                selectedFile = item
                sourcePassword = null
                stats = null
                showPicker = false
                if (item.isEncrypted) needsUnlock = true
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
                scope.launch {
                    val ok = PdfEngine.verifyPassword(context, selectedFile!!.file, pw)
                    if (ok) {
                        sourcePassword = pw
                        unlockError = null
                        needsUnlock = false
                    } else {
                        unlockError = "Incorrect password."
                    }
                }
            },
            onDismiss = { needsUnlock = false }
        )
    }

    if (showPreview && stats != null) {
        FilePreviewModal(
            file = stats!!.outputFile,
            onDismiss = { showPreview = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(stats!!.outputFile)) }
        )
    }
}
