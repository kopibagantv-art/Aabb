package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.core.firebase.configureAppCheck
import com.example.core.model.LocalFileItem
import com.example.core.model.PdfTool
import com.example.core.permissions.AccessGateHost
import com.example.core.permissions.LocalAccessGate
import com.example.core.permissions.rememberAccessGateController
import com.example.ui.screens.*
import com.example.ui.theme.PdfSuperTheme
import com.example.ui.viewmodel.FilesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check before Compose UI, ViewModels, or Firebase AI calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            PdfSuperTheme {
                PdfSuperNavApp()
            }
        }
    }
}

@Composable
private fun PdfSuperNavApp() {
    val navController = rememberNavController()
    val filesViewModel: FilesViewModel = viewModel()
    val accessGate = rememberAccessGateController(filesViewModel.repository)
    var pendingPdfToEdit by remember { mutableStateOf<LocalFileItem?>(null) }

    CompositionLocalProvider(LocalAccessGate provides accessGate) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(240))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(180))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(200)) +
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220))
            }
        ) {
        composable("home") {
            HomeScreen(
                filesViewModel = filesViewModel,
                onNavigateToTool = { tool ->
                    if (tool == PdfTool.PDF_EDITOR) pendingPdfToEdit = null
                    navController.navigate(tool.route)
                },
                onEditPdfFile = { fileItem ->
                    pendingPdfToEdit = fileItem
                    navController.navigate(PdfTool.PDF_EDITOR.route)
                }
            )
        }

        composable(PdfTool.IMAGE_TO_PDF.route) {
            ImageToPdfScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.TEXT_TO_PDF.route) {
            TextToPdfScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.DOCUMENT_SCANNER.route) {
            DocumentScannerScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.PHOTO_EDITOR.route) {
            PhotoEditorScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.PDF_EDITOR.route) {
            PdfEditorScreen(
                filesViewModel = filesViewModel,
                initialFile = pendingPdfToEdit,
                onBack = {
                    pendingPdfToEdit = null
                    navController.popBackStack()
                }
            )
        }

        composable(PdfTool.MERGE_PDF.route) {
            MergePdfScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.SPLIT_PDF.route) {
            SplitPdfScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.COMPRESS_PDF.route) {
            CompressPdfScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.PDF_TO_IMAGE.route) {
            PdfToImageScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(PdfTool.MY_FILES.route) {
            MyFilesScreen(
                filesViewModel = filesViewModel,
                onBack = { navController.popBackStack() },
                onEditPdf = { fileItem ->
                    pendingPdfToEdit = fileItem
                    navController.navigate(PdfTool.PDF_EDITOR.route)
                }
            )
        }
        }
        AccessGateHost(controller = accessGate)
    }
}
