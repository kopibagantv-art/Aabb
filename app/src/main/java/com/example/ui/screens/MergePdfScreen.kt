package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.LocalFileItem
import com.example.core.pdf.PdfEngine
import com.example.core.storage.formatFileSize
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MergePdfScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by filesViewModel.files.collectAsState()

    val mergeQueue = remember { mutableStateListOf<LocalFileItem>() }
    var showPicker by remember { mutableStateOf(false) }
    var outputName by remember { mutableStateOf("Merged_Document") }
    var protectEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isBusy = true
                for (uri in uris) {
                    val imported = filesViewModel.importExternalPdf(uri)
                    if (imported != null) mergeQueue.add(imported)
                }
                isBusy = false
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "Merge PDF",
                subtitle = "${mergeQueue.size} PDF(s) selected",
                onBack = onBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.TopCenter)
            ) {
                item {
                    FilledTonalButton(
                        onClick = { showPicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("add_pdf_to_merge_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add PDF to Merge Queue")
                    }
                }

                if (mergeQueue.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.CallMerge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                                Text("Combine 2 or More PDFs", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Add PDFs from My Files or device storage, drag/reorder them, and merge losslessly offline.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(mergeQueue, key = { idx, item -> "${item.id}_$idx" }) { index, item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${item.pageCount} pages • ${formatFileSize(item.sizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val moved = mergeQueue.removeAt(index)
                                            mergeQueue.add(index - 1, moved)
                                        }
                                    },
                                    enabled = index > 0
                                ) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up") }
                                IconButton(
                                    onClick = {
                                        if (index < mergeQueue.lastIndex) {
                                            val moved = mergeQueue.removeAt(index)
                                            mergeQueue.add(index + 1, moved)
                                        }
                                    },
                                    enabled = index < mergeQueue.lastIndex
                                ) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down") }
                                IconButton(onClick = { mergeQueue.removeAt(index) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = outputName,
                                onValueChange = { outputName = it },
                                label = { Text("Merged PDF Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            PasswordProtectCard(
                                enabled = protectEnabled,
                                password = password,
                                onEnabledChange = { protectEnabled = it },
                                onPasswordChange = { password = it }
                            )

                            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                            Button(
                                onClick = {
                                    scope.launch {
                                        if (mergeQueue.size < 2) {
                                            errorMsg = "Select at least 2 PDF files to merge."
                                            return@launch
                                        }
                                        if (protectEnabled && password.isBlank()) {
                                            errorMsg = "Enter a password or disable password protection."
                                            return@launch
                                        }
                                        isBusy = true
                                        errorMsg = null
                                        runCatching {
                                            val outFile = filesViewModel.repository.uniquePdfFile(outputName)
                                            PdfEngine.mergePdfs(
                                                context = context,
                                                inputFiles = mergeQueue.map { it.file },
                                                outputFile = outFile,
                                                outputPassword = if (protectEnabled) password else null
                                            )
                                        }.onSuccess { out ->
                                            resultFile = out
                                            filesViewModel.refreshFiles()
                                        }.onFailure { err ->
                                            errorMsg = err.localizedMessage ?: "Merge failed."
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = mergeQueue.size >= 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("merge_execute_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.CallMerge, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Merge ${mergeQueue.size} PDFs")
                            }
                        }
                    }
                }

                resultFile?.let { file ->
                    item {
                        ResultSuccessBanner(
                            file = file,
                            subtitle = "Merged ${mergeQueue.size} PDFs",
                            onOpen = { showPreview = true },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = "Merging PDFs...")
        }
    }

    if (showPicker) {
        SelectPdfSourceDialog(
            title = "Add PDF to Merge",
            files = files,
            onSelectLocalFile = { item ->
                mergeQueue.add(item)
                showPicker = false
            },
            onBrowseDevice = {
                showPicker = false
                openDocLauncher.launch(arrayOf("application/pdf"))
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showPreview && resultFile != null) {
        FilePreviewModal(
            file = resultFile!!,
            initialPassword = if (protectEnabled) password else null,
            onDismiss = { showPreview = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(resultFile!!)) }
        )
    }
}
