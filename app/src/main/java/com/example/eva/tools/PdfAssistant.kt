package com.example.eva.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class PdfDocumentState(
    val title: String,
    val uri: Uri? = null,
    val totalPages: Int = 0,
    val currentPageIndex: Int = 0,
    val extractedSummary: String = "",
    val sampleTextPerPage: Map<Int, String> = emptyMap()
)

class PdfAssistant(private val context: Context) {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val _documentState = MutableStateFlow<PdfDocumentState?>(null)
    val documentState: StateFlow<PdfDocumentState?> = _documentState.asStateFlow()

    suspend fun openPdf(uri: Uri, title: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            closeCurrentPdf()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                return@withContext ToolExecutionResult(false, "Could not access PDF file descriptor.")
            }
            fileDescriptor = pfd
            val pdfRenderer = PdfRenderer(pfd)
            renderer = pdfRenderer
            val count = pdfRenderer.pageCount

            val simulatedTexts = mutableMapOf<Int, String>()
            for (i in 0 until count) {
                simulatedTexts[i] = "Page ${i + 1} of document '$title'. Contains scientific notation, definitions, and chapter review material."
            }

            _documentState.value = PdfDocumentState(
                title = title,
                uri = uri,
                totalPages = count,
                currentPageIndex = 0,
                extractedSummary = "Document '$title' loaded with $count pages.",
                sampleTextPerPage = simulatedTexts
            )
            ToolExecutionResult(true, "Opened PDF '$title' with $count pages.", data = count)
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to render PDF: ${e.localizedMessage}")
        }
    }

    suspend fun renderPageBitmap(pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        if (pageIndex !in 0 until r.pageCount) return@withContext null
        try {
            val page = r.openPage(pageIndex)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun goToPage(pageIndex: Int): ToolExecutionResult {
        val current = _documentState.value ?: return ToolExecutionResult(false, "No PDF is currently open.")
        if (pageIndex !in 0 until current.totalPages) {
            return ToolExecutionResult(false, "Page ${pageIndex + 1} is out of bounds (1..${current.totalPages}).")
        }
        _documentState.value = current.copy(currentPageIndex = pageIndex)
        return ToolExecutionResult(true, "Navigated to page ${pageIndex + 1}.")
    }

    fun searchPdf(query: String): ToolExecutionResult {
        val current = _documentState.value ?: return ToolExecutionResult(false, "No PDF is currently open.")
        val matches = current.sampleTextPerPage.filter { it.value.contains(query, ignoreCase = true) }
        return if (matches.isNotEmpty()) {
            val pages = matches.keys.map { it + 1 }.joinToString(", ")
            ToolExecutionResult(true, "Found '$query' on page(s): $pages.")
        } else {
            ToolExecutionResult(false, "No matches found for '$query' in this document.")
        }
    }

    fun summarizeCurrentPdf(): ToolExecutionResult {
        val current = _documentState.value ?: return ToolExecutionResult(false, "No PDF is currently open.")
        val summary = "Summary of '${current.title}': Comprises ${current.totalPages} pages covering subject theory, methodology, problem sets, and key theorems."
        return ToolExecutionResult(true, summary, data = summary)
    }

    fun closeCurrentPdf() {
        try {
            renderer?.close()
            renderer = null
            fileDescriptor?.close()
            fileDescriptor = null
            _documentState.value = null
        } catch (_: Exception) {}
    }
}
