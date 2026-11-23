package com.qingsong.qishuiautomation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper

class ScreenCapture(
    context: Context,
    resultCode: Int,
    consentData: Intent,
) {
    private val projection: MediaProjection
    private val reader: ImageReader
    private val virtualDisplay: VirtualDisplay

    val width: Int
    val height: Int

    init {
        val metrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, consentData)
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    AutomationState.log("系统停止了屏幕采集")
                }
            },
            Handler(Looper.getMainLooper()),
        )

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "QishuiAutomation",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    @Synchronized
    fun capture(): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return null

            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (bitmapWidth == width) {
                full
            } else {
                val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                full.recycle()
                cropped
            }
        } finally {
            image.close()
        }
    }

    @Synchronized
    fun close() {
        runCatching { virtualDisplay.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }
    }
}
