package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.experimental.inv

class Utils { // Changed to class from object to keep existing structure if needed, but companion object makes methods static-like
    companion object {
        suspend fun evaluateJavascript(webView: WebView, evalStr: String): String {
            return suspendCoroutine { continuation ->
                webView.evaluateJavascript(evalStr) {
                    continuation.resume(it ?: "") // Ensure null safety for resume
                }
            }
        }

        fun injectAssetJs(webView: WebView, assetPath: String) {
            webView.evaluateJavascript("(()=>{const e=\"https://appassets.androidplatform.net/assets$assetPath\";let o=!!document.querySelector(`script[src=\"\${e}\"]`);const t=()=>{const t=document.head||document.body;if(!o&&t){const n=document.createElement(\"script\");n.src=e,t.appendChild(n),o=!0,console.log(\"Editor Common JS injected!\")}};t(),o?console.log(\"Skipping Common JS injection - already injected\"):(window.addEventListener(\"DOMContentLoaded\",t),setTimeout(t,200))})();", null)
        }

        fun injectEditorCommonJs(webView: WebView) {
            this.injectAssetJs(webView, "/editors/common.js")
        }

        // --- New EPD Image Processing Functions ---

        /**
         * Converts a Bitmap to the byte array format suitable for EPD, including rotation and pixel packing.
         * For monochrome, assumes white pixels become 1 after packing, and black pixels become 0,
         * then inverts the bits if invertBitmap is true (common for EPDs expecting 0=white, 1=black).
         *
         * @param originalBitmap The input Bitmap.
         * @param targetDataLayoutWidth The width of the EPD screen's data layout (scanline width for data array).
         *                              This is typically the EPD's native width if no rotation, or height if 90/270 rotation.
         * @param targetDataLayoutHeight The height of the EPD screen's data layout (number of scanlines for data array).
         *                               This is typically the EPD's native height if no rotation, or width if 90/270 rotation.
         * @param rotate270 If true, rotates the bitmap 270 degrees clockwise.
         * @param invertPackedBits If true, inverts the packed bits (e.g. if EPD expects 0 for white).
         * @return Pair of ByteArrays: (pic_send_black_white, pic_send_red). Red part is empty for monochrome.
         */
        fun convertBitmapToEpdData(
            originalBitmap: Bitmap,
            targetDataLayoutWidth: Int,
            targetDataLayoutHeight: Int,
            rotate270: Boolean,
            invertPackedBits: Boolean
        ): Pair<ByteArray, ByteArray> {
            val bitmapToProcess: Bitmap
            val sourceBitmapWidthForIndexing: Int // Width of the bitmap data to be iterated over for packing
            // val sourceBitmapHeightForIndexing: Int // Height of the bitmap data (not directly used in loop bounds)

            if (rotate270) {
                val matrix = Matrix()
                matrix.setRotate(270f)
                // Ensure the source bitmap for createBitmap is the original one
                bitmapToProcess = Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height, matrix, true
                )
                sourceBitmapWidthForIndexing = bitmapToProcess.width // This is originalBitmap.height
                // sourceBitmapHeightForIndexing = bitmapToProcess.height // This is originalBitmap.width
                Log.d("Utils_ConvertEpdData", "Bitmap rotated 270. Rotated dims: ${bitmapToProcess.width}w x ${bitmapToProcess.height}h")
            } else {
                bitmapToProcess = originalBitmap
                sourceBitmapWidthForIndexing = bitmapToProcess.width
                // sourceBitmapHeightForIndexing = bitmapToProcess.height
                Log.d("Utils_ConvertEpdData", "Bitmap not rotated. Dims: ${bitmapToProcess.width}w x ${bitmapToProcess.height}h")
            }

            // Important: The loops below iterate based on targetDataLayoutHeight (number of scanlines)
            // and targetDataLayoutWidth (width of each scanline in the data array).
            // The intArray is indexed using sourceBitmapWidthForIndexing.
            // It's crucial that after rotation (if any), bitmapToProcess.width matches targetDataLayoutWidth
            // and bitmapToProcess.height matches targetDataLayoutHeight if you expect a direct pixel mapping.
            // However, EPD_send.java used EPD_width[EPD] (e.g. 264 for 2.7in) in the inner loop's pixel indexing,
            // which corresponded to the width of the *rotated* bitmap image.

            if (bitmapToProcess.width != targetDataLayoutWidth || bitmapToProcess.height != targetDataLayoutHeight) {
                Log.w("Utils_ConvertEpdData", "Warning: Bitmap dimensions after rotation (${bitmapToProcess.width}x${bitmapToProcess.height}) " +
                        "do not exactly match target data layout (${targetDataLayoutWidth}x$targetDataLayoutHeight). " +
                        "Pixel indexing will use rotated bitmap's width (${sourceBitmapWidthForIndexing}). " +
                        "Ensure this is intended or scale the bitmap if necessary before this function.")
                // If a resize is needed to strictly match targetDataLayout, it should happen before this,
                // or `bitmapToProcess` should be scaled here. The current logic assumes `bitmapToProcess`
                // already has the correct pixel data that maps to `targetDataLayoutHeight` scanlines,
                // each `targetDataLayoutWidth` pixels wide.
            }

            val intArray = IntArray(bitmapToProcess.width * bitmapToProcess.height)
            bitmapToProcess.getPixels(intArray, 0, bitmapToProcess.width, 0, 0, bitmapToProcess.width, bitmapToProcess.height)

            val picSendBw = ByteArray(targetDataLayoutHeight * (targetDataLayoutWidth / 8))
            val picSendR = ByteArray(targetDataLayoutHeight * (targetDataLayoutWidth / 8)) // Placeholder

            Log.d("Utils_ConvertEpdData", "Output BW array size: ${picSendBw.size}. Looping $targetDataLayoutHeight times for height.")

            for (i in 0 until targetDataLayoutHeight) { // For each scanline in the target data layout
                for (j in 0 until targetDataLayoutWidth / 8) { // For each byte in the current scanline
                    var packedPix: Byte = 0
                    for (k in 0 until 8) { // For each bit in the current byte
                        packedPix = (packedPix.toInt() shl 1).toByte()

                        // Calculate index into the source bitmap's pixel array (intArray)
                        // i = current scanline index (0 to targetDataLayoutHeight-1)
                        // j*8+k = horizontal pixel offset within the scanline (0 to targetDataLayoutWidth-1)
                        val pixelIndex = (j * 8) + k + (i * sourceBitmapWidthForIndexing)

                        if (pixelIndex < intArray.size) {
                            val pixelColor = intArray[pixelIndex]
                            // Simple luminance conversion (ITU-R BT.601)
                            // val r = (pixelColor shr 16) and 0xFF
                            // val g = (pixelColor shr 8) and 0xFF
                            // val b = pixelColor and 0xFF
                            // val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                            // From EPD_send.java: if (((int) (intArray[...]) & 0x0ff) > 128)
                            // This suggests it might be looking at the blue channel or a pre-greyscaled value if a simple single channel was used.
                            // Let's use a common greyscale method for robustness, or stick to blue channel if that was specific.
                            // For simplicity and matching the ((int) & 0xff) > 128, let's assume it was looking at a single channel or simple intensity.
                            // Android's Color.luminance() returns float 0..1. Or convert to greyscale and check.
                            // A quick way for B&W from ARGB is to check any channel against a threshold if image is already B&W,
                            // or use a proper luminance calculation for color images.
                            val gray = ((pixelColor shr 16 and 0xFF) * 0.299 + (pixelColor shr 8 and 0xFF) * 0.587 + (pixelColor and 0xFF) * 0.114).toInt()


                            if (gray > 128) { // If lighter than mid-gray, consider it "white" for packing
                                packedPix = (packedPix.toInt() or 0x01).toByte()
                            }
                        } else {
                            // This case should ideally not happen if dimensions are handled correctly
                            Log.w("Utils_ConvertEpdData", "Pixel index $pixelIndex out of bounds for intArray (size ${intArray.size})")
                        }
                    }
                    val byteArrayIndex = i * (targetDataLayoutWidth / 8) + j
                    if (byteArrayIndex < picSendBw.size) {
                        picSendBw[byteArrayIndex] = if (invertPackedBits) packedPix.inv() else packedPix
                        picSendR[byteArrayIndex] = 0x00 // Red data is zero for monochrome
                    } else {
                        Log.w("Utils_ConvertEpdData", "Byte array index $byteArrayIndex out of bounds for picSendBw (size ${picSendBw.size})")
                    }
                }
            }
            Log.d("Utils_ConvertEpdData", "Bitmap conversion to byte array complete.")
            return Pair(picSendBw, picSendR)
        }


        /**
         * Compresses EPD data using a simple run-length encoding scheme.
         * If a byte repeats more than 5 times, it's compressed to [0xAB, 0xCD, 0xEF, count, byte_value].
         * This matches the EPD_send.java logic.
         * @param originalData The raw EPD data (e.g., pic_send).
         * @param expectedUncompressedSize The total number of bytes expected in originalData (e.g., width*height/8).
         *                                 Used to prevent overruns in loops.
         * @return The compressed byte array (Cdata).
         */
        fun compressEpdData(originalData: ByteArray, expectedUncompressedSize: Int): ByteArray {
            if (originalData.isEmpty()) return ByteArray(0)

            val compressedList = ArrayList<Byte>()
            var ccnt = 0 // Current count in originalData

            while (ccnt < expectedUncompressedSize && ccnt < originalData.size) {
                // Check for safety: make sure ccnt + 1 is within bounds for the initial comparison
                if (ccnt + 1 < originalData.size && ccnt + 1 < expectedUncompressedSize &&
                    originalData[ccnt] == originalData[ccnt + 1]) {
                    var scnt = 1 // Number of consecutive identical bytes (starts at 1 for the first byte)
                    // Count consecutive identical bytes
                    while (ccnt + scnt < originalData.size && ccnt + scnt < expectedUncompressedSize &&
                        scnt < 0xFF && // Max count for one byte
                        originalData[ccnt] == originalData[ccnt + scnt]) {
                        scnt++
                    }

                    if (scnt > 5) { // If repetition is long enough for compression
                        compressedList.add(0xAB.toByte())
                        compressedList.add(0xCD.toByte())
                        compressedList.add(0xEF.toByte())
                        compressedList.add(scnt.toByte()) // Count
                        compressedList.add(originalData[ccnt]) // Repeated byte
                        ccnt += scnt
                    } else { // Not long enough, or different bytes, write them uncompressed
                        for (j in 0 until scnt) {
                            if (ccnt + j < originalData.size) { // Ensure we don't read past originalData due to scnt calculation
                                compressedList.add(originalData[ccnt + j])
                            }
                        }
                        ccnt += scnt
                    }
                } else { // Single byte or end of data
                    compressedList.add(originalData[ccnt])
                    ccnt++
                }
            }
            return compressedList.toByteArray()
        }
    }
}
