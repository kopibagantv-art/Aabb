package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.LocalFileItem
import com.example.core.permissions.AccessSpec
import com.example.core.permissions.LocalAccessGate
import com.example.core.permissions.PermissionsOverviewDialog
import com.example.core.storage.formatFileSize
import com.example.ui.components.FilePreviewModal
import com.example.ui.components.PdfSuperTopBar
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FileFilterTab(val label: String) {
    ALL("All"),
    PDFS("PDFs"),
    IMAGES("Images"),
    PROTECTED("Protected")
}

@Composable
fun MyFilesScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit,
    onEditPdf: (LocalFileItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessGate = LocalAccessGate.current
    val files by filesViewModel.files.collectAsState()
    var showPermissions by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(FileFilterTab.ALL) }
    var previewItem by remember { mutableStateOf<LocalFileItem?>(null) }
    var renameTarget by remember { mutableStateOf<LocalFileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalFileItem?>(null) }
    var exportSource by remember { mutableStateOf<LocalFileItem?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    val importPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = filesViewModel.importExternalPdf(uri)
                feedbackMsg = if (imported != null) "Imported ${imported.name}" else "Unable to import PDF."
            }
        }
    }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        val src = exportSource
        if (targetUri != null && src != null) {
            scope.launch {
                val ok = filesViewModel.exportToUri(src.file, targetUri)
                feedbackMsg = if (ok) "Saved ${src.name} to device storage." else "Export failed."
                exportSource = null
            }
        }
    }

    val filteredFiles = remember(files, searchQuery, activeTab) {
        files.filter { item ->
            val matchesQuery = searchQuery.isBlank() || item.name.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (activeTab) {
                FileFilterTab.ALL -> true
                FileFilterTab.PDFS -> item.isPdf
                FileFilterTab.IMAGES -> !item.isPdf
                FileFilterTab.PROTECTED -> item.isEncrypted
            }
            matchesQuery && matchesTab
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "My Files",
                subtitle = "${files.size} local file(s)",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showPermissions = true }) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = "Permissions and privacy")
                    }
                    IconButton(
                        onClick = {
                            val launchImport = { importPdfLauncher.launch(arrayOf("application/pdf")) }
                            if (accessGate != null) {
                                accessGate.requestStorageAccess(
                                    AccessSpec.ScopedStorage.DocumentPicker,
                                    onProceed = launchImport
                                )
                            } else {
                                launchImport()
                            }
                        },
                        modifier = Modifier.testTag("import_pdf_button")
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "Import PDF")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search PDFs and images...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileFilterTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = { Text(tab.label) }
                    )
                }
            }

            feedbackMsg?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { feedbackMsg = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Text("No files here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Create PDFs with any tool or tap Import above to bring existing PDFs into your local vault.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = {
                                val launchImport = { importPdfLauncher.launch(arrayOf("application/pdf")) }
                                if (accessGate != null) {
                                    accessGate.requestStorageAccess(
                                        AccessSpec.ScopedStorage.DocumentPicker,
                                        onProceed = launchImport
                                    )
                                } else {
                                    launchImport()
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import External PDF")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredFiles, key = { it.id }) { item ->
                        LocalFileRow(
                            item = item,
                            onPreview = { previewItem = item },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(item.file)) },
                            onEdit = if (item.isPdf) { { onEditPdf(item) } } else null,
                            onExport = {
                                exportSource = item
                                exportDocLauncher.launch(item.name)
                            },
                            onRename = { renameTarget = item },
                            onDelete = { deleteTarget = item }
                        )
                    }
                }
            }
        }
    }

    previewItem?.let { item ->
        FilePreviewModal(
            file = item.file,
            onDismiss = { previewItem = null },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(item.file)) },
            onExport = {
                exportSource = item
                exportDocLauncher.launch(item.name)
            },
            onEditPdf = if (item.isPdf) {
                {
                    previewItem = null
                    onEditPdf(item)
                }
            } else null
        )
    }

    renameTarget?.let { item ->
        var newName by remember(item) { mutableStateOf(item.name.substringBeforeLast(".")) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val ok = filesViewModel.renameItem(item, newName)
                    feedbackMsg = if (ok) "File renamed." else "Invalid name or file already exists."
                    renameTarget = null
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete File?") },
            text = { Text("Permanently remove \"${item.name}\" from local storage?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        filesViewModel.deleteItem(item)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showPermissions && accessGate != null) {
        PermissionsOverviewDialog(
            controller = accessGate,
            onDismiss = { showPermissions = false }
        )
    }
}

@Composable
private fun LocalFileRow(
    item: LocalFileItem,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onEdit: (() -> Unit)?,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateStr = remember(item.lastModified) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(item.lastModified))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPreview)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when {
                    item.isEncrypted -> Icons.Rounded.Lock
                    item.isPdf -> Icons.Rounded.PictureAsPdf
                    else -> Icons.Rounded.Image
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    if (item.isPdf) {
                        append(if (item.isEncrypted) "Protected" else "${item.pageCount}p")
                        append(" • ")
                    }
                    append(formatFileSize(item.sizeBytes))
                    append(" • ")
                    append(dateStr)
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More file options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onEdit != null) {
                        DropdownMenuItem(
                            text = { Text("Edit in PDF Editor") },
                            leadingIcon = { Icon(Icons.Rounded.EditNote, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Save to Device Storage") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}
