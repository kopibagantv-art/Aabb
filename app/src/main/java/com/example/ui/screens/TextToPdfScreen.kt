package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.firebase.AiEnhancementService
import com.example.core.firebase.TextAiAction
import com.example.core.model.PageSizeOption
import com.example.core.model.PdfTextFont
import com.example.core.pdf.PdfEngine
import com.example.ui.components.*
import com.example.ui.viewmodel.FilesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun TextToPdfScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiService = remember(context) { AiEnhancementService(context) }

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(PageSizeOption.A4) }
    var fontStyle by remember { mutableStateOf(PdfTextFont.SANS) }
    var fontSize by remember { mutableFloatStateOf(12f) }
    var passwordEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    var pendingAiAction by remember { mutableStateOf<TextAiAction?>(null) }
    var isAiStreaming by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            PdfSuperTopBar(
                title = "Text to PDF",
                subtitle = "Create formatted documents offline",
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Document Title") },
                    placeholder = { Text("e.g. Project Proposal") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text_pdf_title_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Document Content") },
                    placeholder = { Text("Write or paste your text here...") },
                    minLines = 7,
                    maxLines = 14,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text_pdf_body_input"),
                    shape = RoundedCornerShape(16.dp)
                )

                // Opt-in Online AI Enhancement Bar
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Online AI Writing Polish (Opt-In)",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextAiAction.entries.forEach { action ->
                                AssistChip(
                                    onClick = {
                                        if (body.isBlank()) {
                                            errorMessage = "Enter document text first before using AI polish."
                                        } else {
                                            errorMessage = null
                                            pendingAiAction = action
                                        }
                                    },
                                    enabled = !isAiStreaming,
                                    label = { Text(action.label, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        AnimatedVisibility(visible = isAiStreaming) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Enhancing with Gemini AI...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Text("Page Size", style = MaterialTheme.typography.titleSmall)
                PageSizeSelector(selected = pageSize, onSelect = { pageSize = it })

                Text("Font Family", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PdfTextFont.entries.forEach { font ->
                        FilterChip(
                            selected = fontStyle == font,
                            onClick = { fontStyle = font },
                            label = { Text(font.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Font Size: ${fontSize.toInt()} pt", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 9f..20f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
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
                            if (title.isBlank() && body.isBlank()) {
                                errorMessage = "Please enter a title or body text."
                                return@launch
                            }
                            if (passwordEnabled && password.isBlank()) {
                                errorMessage = "Enter a password or disable password protection."
                                return@launch
                            }
                            isBusy = true
                            errorMessage = null
                            runCatching {
                                val outName = title.trim().ifBlank { "Text_Document" }
                                val outFile = filesViewModel.repository.uniquePdfFile(outName)
                                PdfEngine.createPdfFromText(
                                    context = context,
                                    title = title,
                                    body = body,
                                    outputFile = outFile,
                                    pageSize = pageSize,
                                    fontSizePt = fontSize,
                                    fontStyle = fontStyle,
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
                        .testTag("export_text_pdf_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate PDF")
                }

                resultFile?.let { file ->
                    ResultSuccessBanner(
                        file = file,
                        subtitle = "${pageSize.label} • ${fontStyle.label}",
                        onOpen = { showPreview = true },
                        onShare = { context.startActivity(filesViewModel.repository.createShareIntent(file)) }
                    )
                }
            }

            BusyOverlay(visible = isBusy, message = "Generating formatted PDF...")
        }
    }

    pendingAiAction?.let { action ->
        AiConsentDialog(
            featureTitle = action.label,
            dataDescription = "Your current text (${body.length} characters) will be sent to Google Gemini AI to perform \"${action.label}\".",
            onConfirm = {
                pendingAiAction = null
                scope.launch {
                    isAiStreaming = true
                    errorMessage = null
                    try {
                        aiService.streamEnhancedText(body, action).collect { partial ->
                            body = partial
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage ?: "Online AI enhancement failed."
                    } finally {
                        isAiStreaming = false
                    }
                }
            },
            onDismiss = { pendingAiAction = null }
        )
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
