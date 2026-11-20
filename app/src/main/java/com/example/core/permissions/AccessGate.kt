package com.example.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.storage.LocalFileRepository
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.launch

sealed interface AccessSpec {
    val title: String
    val description: String
    val privacyNote: String
    val icon: ImageVector

    data class RuntimeCamera(
        override val title: String = "Camera Access for Scanner",
        override val description: String = "Allow PDF SUPER to use your camera to capture pages, detect document edges, and apply perspective correction.",
        override val privacyNote: String = "Camera frames are processed strictly on-device and never uploaded without your explicit consent.",
        override val icon: ImageVector = Icons.Rounded.CameraAlt
    ) : AccessSpec

    data class ScopedStorage(
        val kindLabel: String,
        override val title: String,
        override val description: String,
        override val privacyNote: String = "PDF SUPER uses Android's zero-permission Scoped Picker. Only the exact files you choose are accessible.",
        override val icon: ImageVector = Icons.Rounded.FolderOpen
    ) : AccessSpec {
        companion object {
            val PhotoPicker = ScopedStorage(
                kindLabel = "Photo Picker",
                title = "Storage Access for Photos",
                description = "Choose photos from your gallery to scan, enhance, or convert into PDFs.",
                icon = Icons.Rounded.PhotoLibrary
            )

            val DocumentPicker = ScopedStorage(
                kindLabel = "Document Picker",
                title = "Storage Access for PDFs",
                description = "Browse and select PDF files from your device storage to edit, merge, split, or compress.",
                icon = Icons.Rounded.FolderOpen
            )
        }
    }
}

val LocalAccessGate = staticCompositionLocalOf<AccessGateController?> { null }

sealed interface PendingAccessPrompt {
    data class Camera(
        val spec: AccessSpec.RuntimeCamera,
        val onGranted: () -> Unit,
        val onFallbackGallery: (() -> Unit)?
    ) : PendingAccessPrompt

    data class Storage(
        val spec: AccessSpec.ScopedStorage,
        val onProceed: () -> Unit
    ) : PendingAccessPrompt
}

@OptIn(ExperimentalPermissionsApi::class)
class AccessGateController internal constructor(
    val cameraPermissionState: PermissionState,
    private val repository: LocalFileRepository
) {
    internal var pendingPrompt by mutableStateOf<PendingAccessPrompt?>(null)
    internal var awaitingCameraSystemResult by mutableStateOf(false)
    internal var pickerNoticeAcknowledgedSnapshot by mutableStateOf(false)
    internal var cameraRequestedOnceSnapshot by mutableStateOf(false)

    val isCameraGranted: Boolean
        get() = cameraPermissionState.status.isGranted

    val isCameraPermanentlyDenied: Boolean
        get() = !cameraPermissionState.status.isGranted &&
            cameraRequestedOnceSnapshot &&
            !cameraPermissionState.status.shouldShowRationale

    fun requestCameraAccess(
        spec: AccessSpec.RuntimeCamera = AccessSpec.RuntimeCamera(),
        onFallbackGallery: (() -> Unit)? = null,
        onGranted: () -> Unit
    ) {
        if (cameraPermissionState.status.isGranted) {
            onGranted()
        } else {
            pendingPrompt = PendingAccessPrompt.Camera(
                spec = spec,
                onGranted = onGranted,
                onFallbackGallery = onFallbackGallery
            )
        }
    }

    fun requestStorageAccess(
        spec: AccessSpec.ScopedStorage,
        forcePrompt: Boolean = false,
        onProceed: () -> Unit
    ) {
        if (!forcePrompt && pickerNoticeAcknowledgedSnapshot) {
            onProceed()
        } else {
            pendingPrompt = PendingAccessPrompt.Storage(
                spec = spec,
                onProceed = onProceed
            )
        }
    }

    internal fun dismiss() {
        pendingPrompt = null
        awaitingCameraSystemResult = false
    }

    internal suspend fun markCameraRequested() {
        repository.markCameraRequestedOnce()
        cameraRequestedOnceSnapshot = true
    }

    internal suspend fun setPickerNoticeAcknowledged(acknowledged: Boolean) {
        repository.setPickerNoticeAcknowledged(acknowledged)
        pickerNoticeAcknowledgedSnapshot = acknowledged
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberAccessGateController(
    repository: LocalFileRepository
): AccessGateController {
    val cameraState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val controller = remember(cameraState, repository) {
        AccessGateController(cameraState, repository)
    }

    val pickerAck by repository.pickerNoticeAcknowledged.collectAsState(initial = false)
    val cameraRequested by repository.cameraRequestedOnce.collectAsState(initial = false)

    LaunchedEffect(pickerAck) {
        controller.pickerNoticeAcknowledgedSnapshot = pickerAck
    }
    LaunchedEffect(cameraRequested) {
        controller.cameraRequestedOnceSnapshot = cameraRequested
    }

    // Automatically invoke onGranted once Accompanist updates status after system prompt
    val activePrompt = controller.pendingPrompt
    LaunchedEffect(cameraState.status, controller.awaitingCameraSystemResult) {
        if (controller.awaitingCameraSystemResult &&
            cameraState.status.isGranted &&
            activePrompt is PendingAccessPrompt.Camera
        ) {
            controller.awaitingCameraSystemResult = false
            controller.pendingPrompt = null
            activePrompt.onGranted()
        }
    }

    return controller
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AccessGateHost(
    controller: AccessGateController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt = controller.pendingPrompt ?: return

    when (prompt) {
        is PendingAccessPrompt.Camera -> {
            val permanentlyDenied = controller.isCameraPermanentlyDenied
            val shouldShowRationale = controller.cameraPermissionState.status.shouldShowRationale

            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = prompt.spec.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = prompt.spec.title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = when {
                                permanentlyDenied ->
                                    "Camera permission was previously denied. Enable Camera access in Android App Settings to use live document scanning."
                                shouldShowRationale ->
                                    "Camera access is needed only while you scan documents. ${prompt.spec.description}"
                                else -> prompt.spec.description
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )

                        PrivacyNoticeBadge(text = prompt.spec.privacyNote)

                        if (prompt.onFallbackGallery != null) {
                            OutlinedButton(
                                onClick = {
                                    controller.dismiss()
                                    prompt.onFallbackGallery.invoke()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("camera_gate_gallery_fallback"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan from Gallery Instead")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (permanentlyDenied) {
                                controller.dismiss()
                                openAppPermissionSettings(context)
                            } else {
                                scope.launch {
                                    controller.markCameraRequested()
                                    controller.awaitingCameraSystemResult = true
                                    controller.cameraPermissionState.launchPermissionRequest()
                                }
                            }
                        },
                        modifier = Modifier.testTag("camera_gate_allow_button")
                    ) {
                        Text(if (permanentlyDenied) "Open Settings" else "Allow Camera")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { controller.dismiss() },
                        modifier = Modifier.testTag("camera_gate_cancel_button")
                    ) {
                        Text("Not Now")
                    }
                }
            )
        }

        is PendingAccessPrompt.Storage -> {
            var dontAskAgain by remember(prompt) { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = prompt.spec.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = prompt.spec.title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = prompt.spec.description,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        PrivacyNoticeBadge(text = prompt.spec.privacyNote)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = dontAskAgain,
                                onCheckedChange = { dontAskAgain = it },
                                modifier = Modifier.testTag("storage_gate_remember_checkbox")
                            )
                            Text(
                                text = "Don't show this notice again",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                if (dontAskAgain) {
                                    controller.setPickerNoticeAcknowledged(true)
                                }
                                controller.dismiss()
                                prompt.onProceed()
                            }
                        },
                        modifier = Modifier.testTag("storage_gate_continue_button")
                    ) {
                        Text("Open ${prompt.spec.kindLabel}")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { controller.dismiss() },
                        modifier = Modifier.testTag("storage_gate_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsOverviewDialog(
    controller: AccessGateController,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraGranted = controller.isCameraGranted
    val pickerAck = controller.pickerNoticeAcknowledgedSnapshot

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text("Permissions & Privacy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PDF SUPER requests permissions only when needed and uses Android's scoped picker for zero-permission storage access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Camera status card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Camera (Scanner)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (cameraGranted) "Granted" else "Ask when scanning",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!cameraGranted) {
                            TextButton(
                                onClick = {
                                    onDismiss()
                                    controller.requestCameraAccess(onGranted = {})
                                }
                            ) {
                                Text("Grant")
                            }
                        } else {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Granted", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Scoped storage status card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Storage (Scoped Picker)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (pickerAck) "Zero-permission • Auto-open" else "Zero-permission • Prompt active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (pickerAck) {
                            TextButton(
                                onClick = {
                                    scope.launch { controller.setPickerNoticeAcknowledged(false) }
                                }
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { openAppPermissionSettings(context) }) {
                Text("App Settings")
            }
        }
    )
}

@Composable
private fun PrivacyNoticeBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
