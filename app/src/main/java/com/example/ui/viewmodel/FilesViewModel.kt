package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.LocalFileItem
import com.example.core.storage.LocalFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    val repository = LocalFileRepository(application)

    private val _files = MutableStateFlow<List<LocalFileItem>>(emptyList())
    val files: StateFlow<List<LocalFileItem>> = _files.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refreshFiles()
    }

    fun refreshFiles() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _files.value = repository.listAllFiles()
            _isRefreshing.value = false
        }
    }

    suspend fun importExternalPdf(uri: Uri): LocalFileItem? {
        val imported = repository.importExternalPdf(uri)
        refreshFiles()
        return imported
    }

    fun deleteItem(item: LocalFileItem) {
        viewModelScope.launch {
            repository.deleteFile(item.file)
            refreshFiles()
        }
    }

    fun renameItem(item: LocalFileItem, newName: String): Boolean {
        val renamed = repository.renameFile(item, newName)
        if (renamed != null) {
            refreshFiles()
            return true
        }
        return false
    }

    suspend fun exportToUri(file: File, targetUri: Uri): Boolean =
        repository.exportFileToUri(file, targetUri)
}
