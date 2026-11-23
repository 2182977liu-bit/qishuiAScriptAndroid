package com.qingsong.qishuiautomation

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

class OcrEngine {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    fun recognize(bitmap: Bitmap): List<OcrHit> {
        val input = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(input), 10, TimeUnit.SECONDS)
        val hits = mutableListOf<OcrHit>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                val bounds = line.boundingBox ?: continue
                if (text.isNotEmpty()) {
                    hits.add(OcrHit(text, bounds.centerX(), bounds.centerY()))
                }
            }
        }
        return hits
    }

    fun close() {
        recognizer.close()
    }
}
