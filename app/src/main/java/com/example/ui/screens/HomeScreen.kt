package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.LocalFileItem
import com.example.core.model.PdfTool
import com.example.core.permissions.LocalAccessGate
import com.example.core.permissions.PermissionsOverviewDialog
import com.example.core.storage.formatFileSize
import com.example.ui.components.FilePreviewModal
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.SapphireDeep
import com.example.ui.theme.SapphirePrimary
import com.example.ui.theme.SkyHighlight
import com.example.ui.viewmodel.FilesViewModel

@Composable
fun HomeScreen(
    filesViewModel: FilesViewModel,
    onNavigateToTool: (PdfTool) -> Unit,
    onEditPdfFile: (LocalFileItem) -> Unit
) {
    val context = LocalContext.current
    val accessGate = LocalAccessGate.current
    val files by filesViewModel.files.collectAsState()
    val pdfCount = remember(files) { files.count { it.isPdf } }
    val imageCount = remember(files) { files.count { !it.isPdf } }
    var previewFile by remember { mutableStateOf<LocalFileItem?>(null) }
    var showPermissionsDialog by remember { mutableStateOf(false) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Branded Header Bar
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(listOf(SapphireDeep, CobaltAccent))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureAsPdf,
                                contentDescription = "PDF SUPER icon",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "PDF SUPER",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Offline Studio & Smart Suite",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { showPermissionsDialog = true }
                            .testTag("home_permissions_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Local First",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Hero Action Banner
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn() + slideInVertically { -20 }
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(SapphireDeep, SapphirePrimary, CobaltAccent)
                                    )
                                )
                                .drawBehind {
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.08f),
                                        radius = size.height * 0.85f,
                                        center = Offset(size.width * 0.92f, size.height * 0.15f)
                                    )
                                    drawCircle(
                                        color = SkyHighlight.copy(alpha = 0.18f),
                                        radius = size.height * 0.45f,
                                        center = Offset(size.width * 0.78f, size.height * 0.92f)
                                    )
                                }
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Create, Scan & Sign",
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = Color.White
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Fast offline PDF creation, HD perspective scanning, and opt-in AI polish.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.86f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { onNavigateToTool(PdfTool.DOCUMENT_SCANNER) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = SapphireDeep
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.testTag("hero_scan_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DocumentScanner,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Scan Now", fontWeight = FontWeight.Bold)
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.16f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onNavigateToTool(PdfTool.MY_FILES) }
                                    ) {
                                        Text(
                                            text = "$pdfCount PDFs • $imageCount Images",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Tools Section Title
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "All PDF & Studio Tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "10 tools ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 10 Tool Grid Cards
            items(PdfTool.entries, key = { it.route }) { tool ->
                ToolGridCard(
                    tool = tool,
                    onClick = { onNavigateToTool(tool) }
                )
            }

            // Recent Files Section
            if (files.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Files",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { onNavigateToTool(PdfTool.MY_FILES) }) {
                            Text("View All")
                        }
                    }
                }

                items(files.take(4), key = { "recent_${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { previewFile = item }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        item.isEncrypted -> Icons.Rounded.Lock
                                        item.isPdf -> Icons.Rounded.PictureAsPdf
                                        else -> Icons.Rounded.Image
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (item.isPdf) {
                                        val pg = if (item.isEncrypted) "Protected" else "${item.pageCount} pages"
                                        "$pg • ${formatFileSize(item.sizeBytes)}"
                                    } else {
                                        "Image • ${formatFileSize(item.sizeBytes)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    context.startActivity(filesViewModel.repository.createShareIntent(item.file))
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "Share ${item.name}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    previewFile?.let { item ->
        FilePreviewModal(
            file = item.file,
            onDismiss = { previewFile = null },
            onShare = { context.startActivity(filesViewModel.repository.createShareIntent(item.file)) },
            onEditPdf = if (item.isPdf) {
                {
                    previewFile = null
                    onEditPdfFile(item)
                }
            } else null
        )
    }

    if (showPermissionsDialog && accessGate != null) {
        PermissionsOverviewDialog(
            controller = accessGate,
            onDismiss = { showPermissionsDialog = false }
        )
    }
}

@Composable
private fun ToolGridCard(
    tool: PdfTool,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "cardScale"
    )

    val (icon, tintColor, badgeBg) = toolVisuals(tool)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            )
            .testTag("tool_${tool.route}"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .defaultMinSize(minHeight = 104.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tool.title,
                        tint = tintColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowOutward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tool.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tool.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun toolVisuals(tool: PdfTool): Triple<ImageVector, Color, Color> {
    return when (tool) {
        PdfTool.IMAGE_TO_PDF -> Triple(Icons.Rounded.AddPhotoAlternate, Color(0xFF1565C0), Color(0xFFDBEAFE))
        PdfTool.TEXT_TO_PDF -> Triple(Icons.AutoMirrored.Rounded.TextSnippet, Color(0xFF0284C7), Color(0xFFE0F2FE))
        PdfTool.DOCUMENT_SCANNER -> Triple(Icons.Rounded.DocumentScanner, Color(0xFF1D4ED8), Color(0xFFDBEAFE))
        PdfTool.PHOTO_EDITOR -> Triple(Icons.Rounded.AutoFixHigh, Color(0xFF2563EB), Color(0xFFE0E7FF))
        PdfTool.PDF_EDITOR -> Triple(Icons.Rounded.EditNote, Color(0xFF0F766E), Color(0xFFCCFBF1))
        PdfTool.MERGE_PDF -> Triple(Icons.AutoMirrored.Rounded.CallMerge, Color(0xFF1E40AF), Color(0xFFDBEAFE))
        PdfTool.SPLIT_PDF -> Triple(Icons.AutoMirrored.Rounded.CallSplit, Color(0xFF0369A1), Color(0xFFE0F2FE))
        PdfTool.COMPRESS_PDF -> Triple(Icons.Rounded.Compress, Color(0xFF059669), Color(0xFFD1FAE5))
        PdfTool.PDF_TO_IMAGE -> Triple(Icons.Rounded.BurstMode, Color(0xFF4338CA), Color(0xFFE0E7FF))
        PdfTool.MY_FILES -> Triple(Icons.Rounded.FolderOpen, Color(0xFF1565C0), Color(0xFFEFF6FF))
    }
}
