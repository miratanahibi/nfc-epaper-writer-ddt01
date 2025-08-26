/**
 * @file This is a wrapper around the Waveshare JAR, since the main class
 * is marked as package-private, and with slightly obfuscated methods
 * See: https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e_Paper
 */

package waveshare.feng.nfctag.activity

import android.app.Activity
import android.graphics.Bitmap
import android.nfc.Tag // Tagオブジェクトを受け取るために追加
import android.nfc.tech.IsoDep // IsoDep を利用するために追加
import android.nfc.tech.NfcA
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets // パスワードのバイト配列変換用

interface FlashResult {
    val success: Boolean
    val errMessage: String
}

// パスワード検証結果を分かりやすくするためのインターフェース (任意)
interface PasswordVerificationResult {
    val resultValue: Int // SDKからの生の戻り値
    val success: Boolean
    val message: String
}

class WaveShareHandler(private val activity: Activity) { // mActivity をコンストラクタパラメータに変更 (慣習的)
    private val mInstance: a = a() // 初期化をコンストラクタインラインに

    init {
        // this.mInstance.a() // 引数なしの a() が本当に必要か、ドキュメントからは不明確。
        // もし不要なら削除、または特定のタイミングで呼ぶか検討。
        // Initialize (NfcAを渡す方) の前処理かもしれない。
        // 今回は一旦コメントアウトして、影響を見る。
    }

    /** Props with getters */
    val progress get() = this.mInstance.c // おそらく進捗 (0-100) を返す

    /**
     * Initializes the NFC connection with the tag using the SDK.
     * This might be a prerequisite for password verification.
     */
    fun initializeNfcConnection(nfcTagTech: NfcA): Boolean { // メソッド名は initializeNfcConnection のまま
        try {
            Log.d("WaveShareHandler", "Attempting to initialize NFC connection with SDK (NfcA)...")
            val connectionSuccessInt = this.mInstance.a(nfcTagTech) // SDK: public int a(NfcA paramNfcA)
            Log.i("WaveShareHandler", "NFC SDK Connection Initialization result: $connectionSuccessInt")
            return connectionSuccessInt == 1
        } catch (e: Exception) {
            Log.e("WaveShareHandler", "Exception during NFC SDK connection initialization: ${e.message}", e)
            return false
        }
    }

    /**
     * Verifies the password with the NFC tag.
     * Assumes that initializeNfcConnection() has been successfully called
     * BEFORE this method if the SDK requires it for password commands.
     *
     * @param passwordBytes The password as a byte array.
     * @return PasswordVerificationResult containing the result and a message.
     */
    fun verifyPassword(passwordBytes: ByteArray): PasswordVerificationResult {
        var sdkResult = -100 // Default to an unhandled value
        var success = false
        var message = "Verification not attempted."

        try {
            Log.d("WaveShareHandler", "Attempting to verify password with SDK...")
            // このSDKの a(byte[]) が、事前に a(NfcA) で初期化された接続を使うことを期待
            sdkResult = this.mInstance.a(passwordBytes) // SDK: public int a(byte[] paramArrayOfbyte)
            Log.i("WaveShareHandler", "Password verification SDK result: $sdkResult")

            when (sdkResult) {
                1 -> {
                    success = true
                    message = "Password verified successfully."
                }
                0 -> message = "Device doesn't set password. (SDK result: 0)"
                2 -> message = "Password verification failed. (SDK result: 2)"
                3 -> message = "Firmware is not password version or Unknown device. (SDK result: 3)"
                -1 -> message = "IO error during password verification. (SDK result: -1)"
                else -> message = "Unknown password verification result: $sdkResult"
            }
        } catch (e: Exception) {
            Log.e("WaveShareHandler", "Exception during password verification: ${e.message}", e)
            message = "Exception: ${e.message}"
            sdkResult = -101 // Indicate exception
        }

        return object : PasswordVerificationResult {
            override val resultValue = sdkResult
            override val success = success
            override val message = message
        }
    }


    /**
     * Main sending function for bitmap data.
     * This method will NOW handle the SDK initialization with the provided NfcA tag internally.
     *
     * @param nfcTag The NfcA tech object (SDK currently seems to expect this).
     * @param ePaperSize The type/size of the e-Paper.
     * @param bitmap The image to send.
     * @return FlashResult indicating success or failure.
     */
    fun sendBitmap(nfcTag: NfcA, ePaperSize: Int, bitmap: Bitmap): FlashResult {
        var failMsg = ""
        var success = false

        try {
            Log.d("WaveShareHandler", "Attempting to send bitmap...")

            // ▼▼▼ SDK初期化を sendBitmap の最初に行うようにする ▼▼▼
            Log.i("WaveShareHandler", "Initializing SDK with NfcA tag within sendBitmap...")
            val connectionSuccessInt = this.mInstance.a(nfcTag) // SDK: public int a(NfcA paramNfcA)
            Log.i("WaveShareHandler", "SDK Initialization result in sendBitmap: $connectionSuccessInt")

            if (connectionSuccessInt != 1) {
                failMsg = "SDK initialization failed in sendBitmap (Result: $connectionSuccessInt)"
            } else {
                // 初期化が成功した場合のみ画像送信を試みる
                Log.d("WaveShareHandler", "SDK initialized successfully, attempting to send image data...")
                val flashSuccessInt = this.mInstance.a(ePaperSize, bitmap) // SDK: public int a(int paramInt, Bitmap paramBitmap)
                Log.i("WaveShareHandler", "Send bitmap data SDK result: $flashSuccessInt")

                when (flashSuccessInt) {
                    1 -> {
                        success = true
                    }
                    2 -> failMsg = "Incorrect image resolution (SDK result: $flashSuccessInt)"
                    0 -> failMsg = "Failed to write over NFC (SDK result: $flashSuccessInt)" // ドキュメントの0は失敗
                    -1 -> failMsg = "IO error during sending bitmap (SDK result: $flashSuccessInt)" // ドキュメントの-1はIOエラー
                    else -> failMsg = "Failed to write, unknown reason (SDK result: $flashSuccessInt)"
                }
            }
        } catch (e: IOException) {
            failMsg = "IOException during sendBitmap: ${e.message}"
            Log.e("WaveShareHandler", "IOException in sendBitmap", e) // スタックトレースも出力
        } catch (e: Exception) {
            failMsg = "Generic exception during sendBitmap: ${e.message}"
            Log.e("WaveShareHandler", "Generic exception in sendBitmap", e) // スタックトレースも出力
        }

        return object : FlashResult {
            override val success = success
            override val errMessage = failMsg
        }
    }
}
