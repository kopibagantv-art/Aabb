package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.PageSizeOption
import com.example.core.pdf.PdfEngine
import com.example.core.permissions.AccessSpec
import com.example.core.permissions.LocalAccessGate
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.launch
import java.io.File

private data class PickedImageEntry(
    val id: String,
    val bitmap: Bitmap
)

@Composable
fun ImageToPdfScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessGate = LocalAccessGate.current

    val images = remember { mutableStateListOf<PickedImageEntry>() }
    var pageSize by remember { mutableStateOf(PageSizeOption.A4) }
    var fileName by remember { mutableStateOf("Photos_PDF") }
    var addMargins by remember { mutableStateOf(true) }
    var passwordEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    var isBusy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("Creating PDF...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val multiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isBusy = true
                busyMessage = "Loading ${uris.size} photo(s)..."
                for (uri in uris) {
                    val bmp = filesViewModel.repository.decodeBitmapFromUri(uri, maxDim = 1800)
                    if (bmp != null) {
                        images.add(PickedImageEntry("${System.nanoTime()}", bmp))
                    }
                }
                isBusy = false
            }
        }
    }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "Image to PDF",
                subtitle = "${images.size} photo(s) queued",
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.TopCenter)
            ) {
                item {
                    FilledTonalButton(
                        onClick = {
                            val launchPicker = {
                                multiPicker.launch(
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
                            .height(54.dp)
                            .testTag("pick_images_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (images.isEmpty()) "Select Photos from Gallery" else "Add More Photos")
                    }
                }

                if (images.isEmpty()) {
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
                                Icon(
                                    imageVector = Icons.Rounded.Collections,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text("Combine Multiple Images", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Pick one or many photos, reorder pages, choose A4 / A3 / Letter, and lock with a password.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(images, key = { _, item -> item.id }) { index, item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    bitmap = item.bitmap.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Page ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${item.bitmap.width} × ${item.bitmap.height} px",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val moved = images.removeAt(index)
                                            images.add(index - 1, moved)
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = {
                                        if (index < images.lastIndex) {
                                            val moved = images.removeAt(index)
                                            images.add(index + 1, moved)
                                        }
                                    },
                                    enabled = index < images.lastIndex
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                                }
                                IconButton(onClick = { images.removeAt(index) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remove image",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = fileName,
                                onValueChange = { fileName = it },
                                label = { Text("Output PDF Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            Text("Page Size", style = MaterialTheme.typography.titleSmall)
                            PageSizeSelector(selected = pageSize, onSelect = { pageSize = it })

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Add Clean Page Margins", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = addMargins, onCheckedChange = { addMargins = it })
                            }

                            PasswordProtectCard(
                                enabled = passwordEnabled,
                                password = password,
                                onEnabledChange = { passwordEnabled = it },
                                onPasswordChange = { password = it }
                            )

                            if (errorMessage != null) {
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        if (passwordEnabled && password.isBlank()) {
                                            errorMessage = "Enter a password or disable password protection."
                                            return@launch
                                        }
                                        isBusy = true
                                        busyMessage = "Building ${images.size}-page PDF..."
                                        errorMessage = null
                                        runCatching {
                                            val outFile = filesViewModel.repository.uniquePdfFile(fileName)
                                            PdfEngine.createPdfFromImages(
                                                context = context,
                                                images = images.map { it.bitmap },
                                                outputFile = outFile,
                                                pageSize = pageSize,
                                                marginPt = if (addMargins) 24f else 0f,
                                                password = if (passwordEnabled) password else null
                                            )
                                        }.onSuccess { out ->
                                            resultFile = out
                                            filesViewModel.refreshFiles()
                                        }.onFailure { err ->
                                            errorMessage = err.localizedMessage ?: "Failed to create PDF."
                                        }
                                        isBusy = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("create_image_pdf_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Create PDF (${images.size} Pages)")
                            }
                        }
                    }
                }

                resultFile?.let { file ->
                    item {
                        ResultSuccessBanner(
                            file = file,
                            subtitle = "${pageSize.label} • ${images.size} pages",
                            onOpen = { showPreview = true },
                            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                        )
                    }
                }
            }

            BusyOverlay(visible = isBusy, message = busyMessage)
        }
    }

    if (showPreview && resultFile != null) {
        FilePreviewModal(
            file = resultFile!!,
            initialPassword = if (passwordEnabled) password else null,
            onDismiss = { showPreview = false },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(resultFile!!)) }
        )
    }
}
