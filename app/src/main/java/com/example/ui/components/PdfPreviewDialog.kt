package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.pdf.PdfEngine
import com.example.core.storage.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewModal(
    file: File,
    initialPassword: String? = null,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onExport: (() -> Unit)? = null,
    onEditPdf: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isPdf = file.extension.equals("pdf", ignoreCase = true)

    var password by remember(file) { mutableStateOf(initialPassword) }
    var needsPassword by remember(file) { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    var pages by remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var errorMsg by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file, password) {
        loading = true
        errorMsg = null
        if (!isPdf) {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
            if (bmp != null) {
                pages = listOf(bmp)
            } else {
                errorMsg = "Could not decode image file."
            }
            loading = false
        } else {
            val info = PdfEngine.inspectPdf(context, file)
            if (info.isEncrypted && password == null) {
                needsPassword = true
                loading = false
            } else {
                try {
                    val rendered = PdfEngine.renderPdfPages(context, file, password, targetDpiWidth = 900)
                    pages = rendered
                    needsPassword = false
                } catch (e: Exception) {
                    if (info.isEncrypted) {
                        passwordError = "Incorrect password. Please try again."
                        needsPassword = true
                    } else {
                        errorMsg = e.localizedMessage ?: "Failed to render PDF."
                    }
                }
                loading = false
            }
        }
    }

    if (needsPassword) {
        PasswordUnlockDialog(
            fileName = file.name,
            errorText = passwordError,
            onUnlock = { entered ->
                passwordError = null
                password = entered
                needsPassword = false
            },
            onDismiss = onDismiss
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(file.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${if (isPdf) "${pages.size} pages • " else ""}${formatFileSize(file.length())}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close preview")
                        }
                    },
                    actions = {
                        if (isPdf && onEditPdf != null) {
                            IconButton(onClick = onEditPdf) {
                                Icon(Icons.Rounded.EditNote, contentDescription = "Edit in PDF Editor")
                            }
                        }
                        if (onExport != null) {
                            IconButton(onClick = onExport) {
                                Icon(Icons.Rounded.Download, contentDescription = "Save to device storage")
                            }
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share file")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
            ) {
                when {
                    loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    errorMsg != null -> {
                        Text(
                            text = errorMsg ?: "Unable to preview",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(pages) { index, bmp ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    shadowElevation = 3.dp,
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Box {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                        )
                                        if (isPdf) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(10.dp)
                                            ) {
                                                Text(
                                                    text = "${index + 1} / ${pages.size}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
