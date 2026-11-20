package com.example.core.firebase

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AiEnhancementService"

enum class TextAiAction(val label: String, val promptInstruction: String) {
    PROOFREAD("Proofread & Polish", "Fix grammar, spelling, and punctuation while preserving the original meaning and tone. Return only the improved text."),
    FORMAT_REPORT("Format as Clean Document", "Restructure this text with clear section headings, concise paragraphs, and clean bullet points suitable for a professional PDF report."),
    SUMMARIZE("Executive Summary", "Create a concise executive summary with key takeaways and action items from this text.")
}

data class ScannedOcrResult(
    val suggestedTitle: String,
    val extractedText: String,
    val summary: String
)

class AiEnhancementService(private val context: Context) {

    private val textModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    private val ocrStructuredModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "suggestedTitle" to Schema.string(description = "Short 2-5 word clean filename title without extension"),
                "extractedText" to Schema.string(description = "All readable text extracted accurately from the document image"),
                "summary" to Schema.string(description = "1-2 sentence summary of what this document is about")
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun streamEnhancedText(inputText: String, action: TextAiAction): Flow<String> = flow {
        if (inputText.isBlank()) throw IllegalArgumentException("Please enter some text first.")
        if (!isNetworkAvailable()) throw IllegalStateException("Internet connection required for online AI enhancement.")

        val prompt = "${action.promptInstruction}\n\nText:\n$inputText"
        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            textModel.generateContentStream(prompt).collect { chunk ->
                val part = chunk.text.orEmpty()
                if (part.isNotEmpty()) {
                    accumulated.append(part)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("AI model returned an empty response.")
        }
    }

    suspend fun extractDocumentOcr(bitmap: Bitmap): Result<ScannedOcrResult> {
        if (!isNetworkAvailable()) {
            return Result.failure(IllegalStateException("Internet connection required for online AI OCR."))
        }
        return try {
            val scaled = downscaleForPayload(bitmap, maxDim = 1600)
            val response = withTimeout(60_000L) {
                ocrStructuredModel.generateContent(
                    content {
                        image(scaled)
                        text(
                            "Analyze this scanned document page. Extract all text accurately, suggest a short clean title, and write a brief summary."
                        )
                    }
                )
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Empty AI OCR response."))
            val json = JSONObject(rawJson)
            Result.success(
                ScannedOcrResult(
                    suggestedTitle = json.optString("suggestedTitle", "Scanned Document")
                        .replace(Regex("[^A-Za-z0-9 _-]"), "")
                        .trim()
                        .ifBlank { "Scanned Document" },
                    extractedText = json.optString("extractedText", ""),
                    summary = json.optString("summary", "")
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI OCR timed out", e)
            Result.failure(Exception("AI request timed out after 60 seconds. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI OCR failed", e)
            Result.failure(e)
        }
    }

    suspend fun summarizePdfContent(extractedText: String): Result<String> {
        if (extractedText.isBlank()) {
            return Result.failure(IllegalArgumentException("No selectable text found in this PDF to summarize."))
        }
        if (!isNetworkAvailable()) {
            return Result.failure(IllegalStateException("Internet connection required for online AI insights."))
        }
        return try {
            val clipped = extractedText.take(12_000)
            val response = withTimeout(60_000L) {
                textModel.generateContent(
                    "Provide a clear, structured summary with Key Points and Action Items for the following PDF document text:\n\n$clipped"
                )
            }
            val text = response.text
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("AI returned an empty summary."))
            } else {
                Result.success(text)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI summary timed out", e)
            Result.failure(Exception("AI summary timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI summary failed", e)
            Result.failure(e)
        }
    }

    private fun downscaleForPayload(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
