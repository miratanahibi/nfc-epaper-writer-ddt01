package waveshare.feng.nfctag.activity

import android.graphics.Bitmap
import android.nfc.tech.NfcA
import android.util.Log
import java.io.IOException

// Assuming 'a' is the obfuscated class from the SDK JAR
typealias SDKInstance = waveshare.feng.nfctag.activity.a

class WaveShareHandler(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context?
) { // Context might be needed for future SDK versions or other initializations
    private val mInstance: SDKInstance = SDKInstance() // Instantiating the SDK class

    // Data class to hold the result of a flash operation
    data class FlashResult(val success: Boolean, val errMessage: String?)

    // Data class to hold password verification results
    private data class PasswordVerificationResult(
        val message: String,
        val allowsProceed: Boolean,
        // val code: Int, // General purpose code, can be same as sdkResultCode or mapped - can be simplified
        val sdkResultCode: Int // Direct result from SDK
    )

    // Helper to initialize NFC connection via SDK
    // (ドキュメント: public int a(NfcA paramNfcA) - Initialize)
    // Returns 1 (success) or -1 (failed)
    private fun initializeNfcConnection(nfcTagTech: NfcA): Boolean {
        try {
            Log.i("WaveShareHandler_Init", "Attempting to initialize NFC Connection with SDK...")
            val connectionSuccessInt = this.mInstance.a(nfcTagTech)
            Log.i("WaveShareHandler_Init", "NFC SDK Connection Initialization result: $connectionSuccessInt (1 means success, -1 means failed)")
            return connectionSuccessInt == 1
        } catch (e: Exception) {
            Log.e("WaveShareHandler_Init", "Exception during NFC SDK connection initialization: ${e.message}", e)
            return false
        }
    }

    // Helper for password verification using SDK
    // (ドキュメント: public int a(byte[] paramArrayOfbyte) - Verify password)
    private fun internalVerifyPassword(password: ByteArray): PasswordVerificationResult {
        Log.i("WaveShareHandler_Verify", "Attempting internalVerifyPassword with SDK (password length: ${password.size})...")
        // SDK Method: public int a(byte[] paramArrayOfbyte)
        // Return:
        // -1: IO error
        //  0: The device doesn’t set password
        //  1: Verified successfully
        //  2: Verified failed
        //  3: The firmware is not password version or Unknown devices.
        val sdkResult = try {
            this.mInstance.a(password)
        } catch (e: Exception) {
            Log.e("WaveShareHandler_Verify", "Exception calling SDK password verification: ${e.message}", e)
            -1 // Treat exceptions during call as IO Error for simplicity
        }
        Log.i("WaveShareHandler_Verify", "SDK password verification result: $sdkResult")

        val message: String
        var allowsProceed = false

        when (sdkResult) {
            0 -> {
                message = "Device doesn’t set password (or password check not applicable)."
                allowsProceed = true // If no password set, we can proceed
            }
            1 -> {
                message = "Password verified successfully."
                allowsProceed = true
            }
            -1 -> {
                message = "IO error during password verification."
                allowsProceed = false // Cannot proceed on IO Error
            }
            2 -> {
                message = "Password verification failed (wrong password)."
                allowsProceed = false
            }
            3 -> {
                message = "Firmware is not password-enabled or unknown device for password."
                // Depending on app logic, you might allow proceeding if the operation doesn't strictly need a password
                // but for writing, this is likely a failure state if a password was expected.
                allowsProceed = false // Default to not proceeding
            }
            else -> {
                message = "Unknown password verification result from SDK: $sdkResult"
                allowsProceed = false
            }
        }
        return PasswordVerificationResult(message, allowsProceed, sdkResult)
    }


    fun sendBitmap(
        nfcTagTech: NfcA,
        ePaperSize: Int, // screenTypeEnum, should be 6 for 2.7inch
        bitmap: Bitmap,
        passwordBytes: ByteArray? // Can be null (no password) or empty byte array etc.
    ): FlashResult {
        // 1. SDK経由でNFC接続を初期化
        if (!initializeNfcConnection(nfcTagTech)) {
            return FlashResult(false, "SDK NFC Connection Initialization failed.")
        }

        // 2. (ドキュメント: public int a() - Query the process) の呼び出し
        var queryProcessResult = -99 // Initialize with a distinct value
        try {
            Log.i("WaveShareHandler_QueryProc", "Attempting to call mInstance.a() (Query the process)...")
            queryProcessResult = this.mInstance.a() // Calls the parameterless a()
            Log.i("WaveShareHandler_QueryProc", "Result of mInstance.a() (Query the process): $queryProcessResult")
            // Documented Return values for queryProcessResult:
            // -1: Error
            // 0-99: Process data
            // 100: Update successfully
            // For now, we are just logging this. If it returns -1 (Error), subsequent steps might fail.
            if (queryProcessResult == -1) {
                Log.w("WaveShareHandler_QueryProc", "mInstance.a() (Query Process) returned an ERROR (-1). Subsequent operations might fail.")
                // Depending on the tag/SDK behavior, an error here might mean we shouldn't proceed.
                // For this test, we'll log and continue to see the behavior of password/send.
            }
        } catch (e: Exception) {
            Log.e("WaveShareHandler_QueryProc", "Exception calling mInstance.a() (Query the process): ${e.message}", e)
            // If this critical pre-step fails, it's safer to not proceed.
            // return FlashResult(false, "Failed during SDK Query Process step: ${e.message}") // Option: uncomment to fail fast
        }

        var proceedWithSendingImage = false
        var finalMessage: String

        // 3. パスワード処理
        if (passwordBytes != null) {
            if (passwordBytes.isEmpty()) {
                Log.i("WaveShareHandler", "Password provided is EMPTY. Attempting verification...")
            } else {
                Log.i("WaveShareHandler", "Password provided (non-empty). Attempting verification...")
            }
            val verifyResult = internalVerifyPassword(passwordBytes)

            if (verifyResult.allowsProceed) {
                Log.i("WaveShareHandler", "Password check allows proceeding. Message: ${verifyResult.message} (SDK Code: ${verifyResult.sdkResultCode})")
                proceedWithSendingImage = true
                finalMessage = "Password check passed or not required. ${verifyResult.message}"
            } else {
                Log.w("WaveShareHandler", "Password check FAILED or does not allow proceeding. Message: ${verifyResult.message} (SDK Code: ${verifyResult.sdkResultCode})")
                proceedWithSendingImage = false
                finalMessage = "Password check failed: ${verifyResult.message} (SDK Code: ${verifyResult.sdkResultCode})"
                return FlashResult(false, finalMessage) // Fail early if password check doesn't allow proceeding
            }
        } else {
            Log.i("WaveShareHandler", "No password (null) provided. Assuming unprotected tag or operation.")
            proceedWithSendingImage = true // If no password is given by caller, assume it's okay to try sending.
            finalMessage = "No password (null) provided by caller."
        }

        // 4. 画像データ送信
        if (proceedWithSendingImage) {
            Log.i("WaveShareHandler_Send", "Proceeding to send image data to SDK (ePaperSize: $ePaperSize)...")
            // (ドキュメント: public int a(int paramInt, Bitmap paramBitmap) - Send image data)
            // Return: -1 (IO error), 0 (failed), 1 (successfully), 2 (resolution wrong)
            val sendDataResult = try {
                this.mInstance.a(ePaperSize, bitmap)
            } catch (e: Exception) {
                Log.e("WaveShareHandler_Send", "Exception calling SDK send image data: ${e.message}", e)
                -1 // Treat exceptions during call as IO Error
            }
            Log.i("WaveShareHandler_Send", "SDK send image data result: $sendDataResult")

            return when (sendDataResult) {
                1 -> FlashResult(true, "Successfully wrote image via NFC. $finalMessage")
                0 -> FlashResult(false, "Failed to write image (SDK send result: 0). $finalMessage")
                -1 -> FlashResult(false, "IO error during image send (SDK send result: -1). $finalMessage")
                2 -> FlashResult(false, "Image resolution is wrong for ePaper type (SDK send result: 2). $finalMessage")
                else -> FlashResult(false, "Unknown error during image send (SDK send result: $sendDataResult). $finalMessage")
            }
        } else {
            // This case should ideally be caught by the return in the password block.
            return FlashResult(false, "Did not proceed to send image. $finalMessage")
        }
    }
}
