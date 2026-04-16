package com.madhu.bikeintercom

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson

class TripRepository(geminiApiKey: String) {

    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = geminiApiKey
    )

    private val gson = Gson()

    private val basePrompt = """
        Parse this trip information into JSON with fields 'start', 'stops' (list), and 'destination'.
        Ordered as mentioned. Return ONLY raw JSON.
    """.trimIndent()

    suspend fun parseTripWithAI(input: String): ParsedTrip {
        val prompt = "$basePrompt\nInput: \"$input\""
        return generateAndParse(prompt)
    }

    suspend fun parseTripWithImage(bitmap: Bitmap): ParsedTrip {
        val inputContent = content {
            image(bitmap)
            text(basePrompt)
        }
        val response = generativeModel.generateContent(inputContent)
        return parseResponse(response.text)
    }

    suspend fun parseTripWithDocument(mimeType: String, bytes: ByteArray): ParsedTrip {
        val inputContent = content {
            blob(mimeType, bytes)
            text(basePrompt)
        }
        val response = generativeModel.generateContent(inputContent)
        return parseResponse(response.text)
    }

    private suspend fun generateAndParse(prompt: String): ParsedTrip {
        val response = generativeModel.generateContent(prompt)
        return parseResponse(response.text)
    }

    private fun parseResponse(text: String?): ParsedTrip {
        val jsonString = text?.replace("```json", "")?.replace("```", "")?.trim() ?: ""
        return try {
            gson.fromJson(jsonString, ParsedTrip::class.java)
        } catch (e: Exception) {
            ParsedTrip()
        }
    }
}
