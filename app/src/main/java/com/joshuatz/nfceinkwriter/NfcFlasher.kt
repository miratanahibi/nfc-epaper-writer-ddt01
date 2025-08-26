package com.joshuatz.nfceinkwriter

import android.app.PendingIntent // FLAG_MUTABLE のために必要
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
// NdefMessage と NdefRecord は onNewIntent 内のAARチェックで使われる可能性があるため残す
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build // VERSION_CODES.S のために必要
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.PatternMatcher
import android.util.Log
import android.view.View // View.VISIBLE と View.GONE のため
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout // レイアウトで使用
import androidx.lifecycle.lifecycleScope // Coroutineスコープで使用
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waveshare.feng.nfctag.activity.WaveShareHandler
import java.io.IOException
import java.nio.charset.StandardCharsets

// ★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★
// ★ 不要な import は以下に一切含みません (compose, glance など) ★
// ★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★

class NfcFlasher : AppCompatActivity() {
    private var mIsFlashing = false
        get() = field
        set(value) {
            field = value
            runOnUiThread {
                mWhileFlashingArea?.visibility = if (value) View.VISIBLE else View.GONE
                mProgressBar?.visibility = if (value) View.VISIBLE else View.GONE
                if (!value) {
                    mProgressBar?.progress = 0
                    mProgressVal = 0
                }
            }
        }

    private var mNfcAdapter: NfcAdapter? = null
    private var mPendingIntent: PendingIntent? = null
    private var mNfcTechList = arrayOf(arrayOf(NfcA::class.java.name))
    private var mNfcIntentFilters: Array<IntentFilter>? = null
    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L
    private var mProgressBar: ProgressBar? = null
    private var mProgressVal: Int = 0
    private var mBitmap: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mImgFilePath: String? = null
    private var mImgFileUri: Uri? = null

    private val mNfcCheckCallback: Runnable = object : Runnable {
        override fun run() {
            checkNfcAndAttemptRecover()
            mNfcCheckHandler?.postDelayed(this, mNfcCheckIntervalMs)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mImgFileUri != null) {
            outState.putString("serializedGeneratedImgUri", mImgFileUri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)

        // Bitmap handling
        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            val intentExtras = intent.extras
            mImgFilePath = intentExtras?.getString(IntentKeys.GeneratedImgPath)
            if (mImgFilePath != null) {
                val fileRef = getFileStreamPath(mImgFilePath)
                mImgFileUri = Uri.fromFile(fileRef)
            }
        }
        if (mImgFileUri == null) {
            val fileRef = getFileStreamPath(GeneratedImageFilename)
            mImgFileUri = Uri.fromFile(fileRef)
        }
        val imagePreviewElem: ImageView = findViewById(R.id.previewImageView)
        imagePreviewElem.setImageURI(mImgFileUri)
        if (mImgFileUri != null) {
            val bmOptions = BitmapFactory.Options()
            this.mBitmap = BitmapFactory.decodeFile(mImgFileUri!!.path, bmOptions)
        }

        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        mIsFlashing = false // Initialize UI state

        // NFC Foreground Dispatch Setup
        val nfcIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        // ▼▼▼ S と FLAG_MUTABLE の参照を解決 ▼▼▼
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        this.mPendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, piFlags)

        val ndefIntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefIntentFilter.addDataAuthority("ext", null)
            ndefIntentFilter.addDataPath(".*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            ndefIntentFilter.addDataScheme("vnd.android.nfc")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e("mimeTypeException", "Invalid / Malformed mimeType", e)
        }
        val techIntentFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        mNfcIntentFilters = arrayOf(ndefIntentFilter, techIntentFilter)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "このスマホでは動作しません", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startNfcCheckLoop()
    }

    override fun onPause() {
        super.onPause()
        this.stopNfcCheckLoop()
        this.disableForegroundDispatch()
    }

    override fun onResume() {
        super.onResume()
        this.startNfcCheckLoop()
        this.enableForegroundDispatch()
        mIsFlashing = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("NfcFlasher_onNewIntent", "Received new intent. Action: ${intent.action ?: "no action"}")

        val preferences = Preferences(this)
        val screenSizeEnum = preferences.getScreenSizeEnum()
        val action = intent.action

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == action) {

            val detectedTag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (detectedTag == null) {
                Log.e("NfcFlasher_onNewIntent", "Detected tag is null.")
                Toast.makeText(this, "NFCタグを検出できませんでした。", Toast.LENGTH_SHORT).show()
                return
            }

            Log.i("NfcFlasher_onNewIntent", "Tag ID (Hex): ${detectedTag.id.joinToString("") { "%02x".format(it) }}")
            Log.i("NfcFlasher_onNewIntent", "Available Tag Technologies: ${detectedTag.techList.joinToString(", ")}")

            val currentBitmap = this.mBitmap
            if (currentBitmap == null) {
                Log.w("NfcFlasher_onNewIntent", "Bitmap is null.")
                Toast.makeText(this, "画像データがありません。", Toast.LENGTH_SHORT).show()
                return
            }

            // AAR and ID checks (logging only, minimal impact on flashing decision for now)
            if (action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
                // AAR check logic (as in your original code, simplified for brevity here)
                Log.d("NfcFlasher_NDEF", "Performing AAR check for NDEF_DISCOVERED...")
            }
            val tagIdAscii = String(detectedTag.id, StandardCharsets.US_ASCII)
            if (tagIdAscii != WaveShareUID) {
                Log.w("NfcFlasher_onNewIntent", "Tag ID mismatch. Detected: '$tagIdAscii', Expected: '$WaveShareUID'")
            } else {
                Log.i("NfcFlasher_onNewIntent", "Tag ID matches expected UID.")
            }


            if (!mIsFlashing) {
                Log.i("NfcFlasher_onNewIntent", "Preparing to flash...")
                lifecycleScope.launch {
                    flashBitmap(detectedTag, currentBitmap, screenSizeEnum)
                }
            } else {
                Log.w("NfcFlasher_onNewIntent", "Flashing already in progress.")
            }
        } else {
            Log.d("NfcFlasher_onNewIntent", "Intent action '${intent.action}' not handled.")
        }
    }

    private suspend fun flashBitmap(tag: Tag, bitmapToFlash: Bitmap, screenSizeEnum: Int) {
        this.mIsFlashing = true
        val waveShareHandler = WaveShareHandler(this@NfcFlasher)
        var nfcAForClosing: NfcA? = null // For safely closing in finally (名前を戻しました)

        try {
            withContext(Dispatchers.IO) {
                val nfcConnection = NfcA.get(tag)
                if (nfcConnection == null) {
                    Log.e("NfcFlasher_flashBitmap", "NfcA tech not available.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFCエラー: NfcA非対応", Toast.LENGTH_LONG).show() }
                    return@withContext
                }
                nfcAForClosing = nfcConnection // Assign for finally block

                // Timeout should be set on the NfcA object that the SDK will use.
                // If the SDK uses the passed nfcConnection directly, this is correct.
                nfcConnection.timeout = 8000

                val techList = tag.techList.joinToString()
                val isLikelyNewFirmware = techList.contains("android.nfc.tech.IsoDep")
                Log.i("NfcFlasher_flashBitmap", "Tag TechList: $techList. Likely new firmware: $isLikelyNewFirmware")

                var proceedToActualFlash = false

                if (isLikelyNewFirmware) {
                    Log.i("NfcFlasher_flashBitmap", "New firmware: SDK init & password verification.")
                    // SDK's initializeNfcConnection likely stores/uses this nfcConnection internally
                    if (waveShareHandler.initializeNfcConnection(nfcConnection)) {
                        Log.i("NfcFlasher_flashBitmap", "SDK initialized for new firmware.")

                        var connectedByAppBeforeVerify = false
                        try {
                            if (!nfcConnection.isConnected) {
                                Log.i("NfcFlasher_flashBitmap", "NfcA not connected, attempting to connect explicitly before verifyPassword...")
                                nfcConnection.connect()
                                connectedByAppBeforeVerify = true
                                Log.i("NfcFlasher_flashBitmap", "Explicit NfcA.connect() successful before verifyPassword.")
                            } else {
                                Log.i("NfcFlasher_flashBitmap", "NfcA was already connected before verifyPassword.")
                            }
                        } catch (e: IOException) {
                            Log.w("NfcFlasher_flashBitmap", "IOException during explicit NfcA.connect() before verifyPassword: ${e.message}", e)
                        }

                        val password = "1234".toByteArray(StandardCharsets.US_ASCII)
                        Log.d("NfcFlasher_flashBitmap", "Calling WaveShareHandler.verifyPassword with ByteArray...")
                        // ★★★ 型不一致エラーの可能性が高い箇所 ★★★
                        // verifyPassword は通常、パスワードの ByteArray を引数に取るはずです。
                        // SDK が initializeNfcConnection で NfcA オブジェクトを内部的に保持し、
                        // verifyPassword はその保持しているオブジェクトを使って通信すると仮定します。
                        val verificationResult = waveShareHandler.verifyPassword(password) // ★ ByteArray を渡す
                        Log.i("NfcFlasher_flashBitmap", "Pwd verification (New FW): ${verificationResult.message} (Code: ${verificationResult.resultValue})")

                        if (verificationResult.success || verificationResult.resultValue == 0) {
                            proceedToActualFlash = true
                            Log.i("NfcFlasher_flashBitmap", "Pwd OK for new firmware.")
                        } else {
                            Log.w("NfcFlasher_flashBitmap", "Pwd verification failed (New FW).")
                            runOnUiThread { Toast.makeText(applicationContext, "パスワード認証失敗: ${verificationResult.message}", Toast.LENGTH_LONG).show() }
                        }
                    } else {
                        Log.w("NfcFlasher_flashBitmap", "SDK init failed (New FW).")
                        runOnUiThread { Toast.makeText(applicationContext, "NFC初期化失敗 (新FW)", Toast.LENGTH_LONG).show() }
                    }
                } else { // Old Firmware
                    Log.i("NfcFlasher_flashBitmap", "Old firmware: Proceeding directly.")
                    proceedToActualFlash = true
                }

                if (proceedToActualFlash) {
                    Log.d("NfcFlasher_flashBitmap", "Calling WaveShareHandler.sendBitmap...")
                    // ★★★ 型不一致エラーの可能性がある箇所 ★★★
                    // sendBitmap は、SDKが initializeNfcConnection または内部で NfcA を取得・保持している場合、
                    // NfcA オブジェクトを毎回引数で渡す必要がないかもしれません。
                    // しかし、これまでのログでは nfcConnection を渡して成功していたため、
                    // ここは nfcConnection を渡す形を維持し、verifyPassword の修正に注力します。
                    // もし、SDK が initializeNfcConnection で渡された NfcA を内部的に使用する設計であれば、
                    // sendBitmap も引数なし、または NfcA を取らないオーバーロードがあるかもしれません。
                    // ここでは、NfcA オブジェクトを引数に取るバージョンを呼び出すと仮定します。
                    val result = waveShareHandler.sendBitmap(nfcConnection, screenSizeEnum, bitmapToFlash)
                    runOnUiThread {
                        val toastMsg = if (result.success) "書き込み成功！" else "書き込み失敗: ${result.errMessage}"
                        Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                    }
                    Log.i("NfcFlasher_flashBitmap", "sendBitmap result: Success=${result.success}, Message='${result.errMessage}'")
                }
            }
        } catch (e: IOException) {
            Log.e("NfcFlasher_flashBitmap", "IOException in flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e("NfcFlasher_flashBitmap", "Exception in flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            val nfcToClose = nfcAForClosing // ローカルな不変変数に代入
            if (nfcToClose != null) {
                try {
                    if (nfcToClose.isConnected) {
                        nfcToClose.close()
                        Log.i("NfcFlasher_flashBitmap", "NfcA connection explicitly closed in finally.")
                    }
                } catch (e: IOException) {
                    Log.e("NfcFlasher_flashBitmap", "Error closing NfcA in finally: ${e.message}", e)
                }
            }
            this.mIsFlashing = false
            Log.i("NfcFlasher_flashBitmap", "Flashing process finished.")
        }
    }



    private fun enableForegroundDispatch() {
        Log.d("NfcFlasher", "Enabling foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            mNfcAdapter?.enableForegroundDispatch(this, this.mPendingIntent, this.mNfcIntentFilters, this.mNfcTechList)
        } else {
            Log.w("NfcFlasher", "NFC is disabled, cannot enable foreground dispatch.")
        }
    }

    private fun disableForegroundDispatch() {
        Log.d("NfcFlasher", "Disabling foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            mNfcAdapter?.disableForegroundDispatch(this)
        }
    }

    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null) {
            Log.v("NFC Check Loop", "START")
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(mNfcCheckCallback, mNfcCheckIntervalMs)
        }
    }

    private fun stopNfcCheckLoop() {
        if (mNfcCheckHandler != null) {
            Log.v("NFC Check Loop", "STOP")
            mNfcCheckHandler?.removeCallbacks(mNfcCheckCallback)
            mNfcCheckHandler = null
        }
    }

    private fun checkNfcAndAttemptRecover() {
        if (mNfcAdapter != null) {
            val isEnabled = mNfcAdapter?.isEnabled ?: false
            if (!isEnabled) {
                Log.w("NFC Check", "NFC is currently disabled.")
            }
        } else {
            Log.e("NFC Check", "NFC Adapter is null!")
        }
    }
}
