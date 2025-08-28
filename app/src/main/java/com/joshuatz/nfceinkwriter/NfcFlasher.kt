package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
//import androidx.glance.visibility
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waveshare.feng.nfctag.activity.WaveShareHandler
import java.io.IOException
import java.nio.charset.StandardCharsets

class NfcFlasher : AppCompatActivity() {
    private var mIsFlashing = false
        set(value) {
            field = value
            runOnUiThread {
                mWhileFlashingArea?.visibility = if (value) View.VISIBLE else View.GONE
                mProgressBar?.visibility = if (value) View.VISIBLE else View.GONE
                if (!value) {
                    mProgressBar?.progress = 0
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
    private var mBitmap: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mImgFileUri: Uri? = null

    private val mNfcCheckCallback: Runnable = object : Runnable {
        override fun run() {
            checkNfcAndAttemptRecover()
            mNfcCheckHandler?.postDelayed(this, mNfcCheckIntervalMs)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mImgFileUri?.toString()?.let {
            outState.putString("serializedGeneratedImgUri", it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)

        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            intent.extras?.getString(IntentKeys.GeneratedImgPath)?.let { filePath ->
                mImgFileUri = Uri.fromFile(getFileStreamPath(filePath))
            }
        }
        if (mImgFileUri == null) {
            mImgFileUri = Uri.fromFile(getFileStreamPath(GeneratedImageFilename))
        }

        val imagePreviewElem: ImageView = findViewById(R.id.previewImageView)
        mImgFileUri?.let { uri ->
            imagePreviewElem.setImageURI(uri)
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    this.mBitmap = BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: IOException) {
                Log.e("NfcFlasher_onCreate", "Error loading bitmap from URI: $uri", e)
                Toast.makeText(this, "画像の読み込みに失敗しました。", Toast.LENGTH_LONG).show()
            }
        }

        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        mIsFlashing = false

        val nfcIntent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        this.mPendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, piFlags)

        val techIntentFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        mNfcIntentFilters = arrayOf(techIntentFilter)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "このスマホではNFC機能が利用できません。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
    }

    override fun onPause() {
        super.onPause()
        this.stopNfcCheckLoop()
        this.disableForegroundDispatch()
    }

    override fun onResume() {
        super.onResume()
        if (mNfcAdapter?.isEnabled == false) {
            Toast.makeText(this, "NFCを有効にしてください。", Toast.LENGTH_LONG).show()
        }
        this.startNfcCheckLoop()
        this.enableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("NfcFlasher_onNewIntent", "Received new intent. Action: ${intent.action ?: "no action"}")

        val preferences = Preferences(this)
        val screenSizeEnum = preferences.getScreenSizeEnum() // This should correspond to EInkSizeType in iOS
        val action = intent.action

        if (NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == action) {

            val detectedTag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            if (detectedTag == null) {
                Log.e("NfcFlasher_onNewIntent", "Detected tag is null.")
                Toast.makeText(this, "NFCタグを検出できませんでした。", Toast.LENGTH_SHORT).show()
                return
            }

            val tagIdHex = detectedTag.id.joinToString("") { "%02x".format(it) }
            Log.i("NfcFlasher_onNewIntent", "Tag ID (Hex): $tagIdHex")
            Log.i("NfcFlasher_onNewIntent", "Available Tag Technologies: ${detectedTag.techList.joinToString(", ")}")

            val currentBitmap = this.mBitmap
            if (currentBitmap == null) {
                Log.w("NfcFlasher_onNewIntent", "Bitmap is null. Cannot flash.")
                Toast.makeText(this, "画像データがありません。", Toast.LENGTH_SHORT).show()
                return
            }

            if (!mIsFlashing) {
                Log.i("NfcFlasher_onNewIntent", "Preparing to flash image...")
                lifecycleScope.launch {
                    flashBitmap(detectedTag, currentBitmap, screenSizeEnum)
                }
            } else {
                Log.w("NfcFlasher_onNewIntent", "Flashing operation already in progress. Ignoring new tag.")
            }
        } else {
            Log.d("NfcFlasher_onNewIntent", "Intent action '${intent.action}' not handled for flashing.")
        }
    }

    private suspend fun flashBitmap(tag: Tag, bitmapToFlash: Bitmap, screenSizeEnum: Int) {
        if (mIsFlashing) {
            Log.w("NfcFlasher_flashBitmap", "Attempted to start flashBitmap while already flashing. Aborting.")
            return
        }
        this.mIsFlashing = true
        val waveShareHandler = WaveShareHandler(this@NfcFlasher)
        var nfcAForClosing: NfcA? = null

        try {
            withContext(Dispatchers.IO) {
                val nfcConnection = NfcA.get(tag)
                if (nfcConnection == null) {
                    Log.e("NfcFlasher_flashBitmap", "NfcA technology not available for this tag.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFCエラー: NfcA非対応のタグです。", Toast.LENGTH_LONG).show() }
                    this@NfcFlasher.mIsFlashing = false
                    return@withContext
                }
                nfcAForClosing = nfcConnection

                val techList = tag.techList.joinToString()
                // Consider a more robust way to determine if it's new firmware or specific model
                // For now, using IsoDep check as a proxy for "new firmware" which might include 2.7inch
                val isLikelyNewFirmwareOr27Inch = techList.contains("android.nfc.tech.IsoDep")
                Log.i("NfcFlasher_flashBitmap", "Tag TechList: $techList. Is new firmware/2.7inch? $isLikelyNewFirmwareOr27Inch")

                val passwordToUseForSdk: ByteArray?

                // Based on iOS demo: EInkSizeType270 (which is our target new firmware device)
                // seems to be called with [AccountModel shared].password, which might be nil or empty if not set.
                // Other "new" looking models in iOS also used [AccountModel shared].password
                // EINK_154_B and EINK_154_Y used `nil` explicitly in iOS.

                if (isLikelyNewFirmwareOr27Inch) {
                    // ★★★ MODIFIED PART START ★★★
                    Log.i("NfcFlasher_flashBitmap", "New firmware/2.7inch detected. Attempting with EMPTY STRING password for SDK sendBitmap.")
                    passwordToUseForSdk = "".toByteArray(StandardCharsets.US_ASCII)
                    // ★★★ MODIFIED PART END ★★★
                } else {
                    // This branch would be for older firmware types that are NOT IsoDep based.
                    // For example, if EINK_154_B/Y were not IsoDep and needed nil.
                    // Or if some other old type needed "1234".
                    // For now, let's assume non-IsoDep tags might be older ones that worked with "1234" or null.
                    // Adjust this based on which exact "old firmware" you are targeting with this else block.
                    // If your "old successful firmware" was, for instance, 1.54inch which used `nil` in iOS:
                    // passwordToUseForSdk = null
                    // Log.i("NfcFlasher_flashBitmap", "Old firmware (e.g. 1.54 non-IsoDep). Attempting with NULL password.")
                    // If it was another type that previously worked with "1234":
                    Log.i("NfcFlasher_flashBitmap", "Old firmware (non-IsoDep). Using default password '1234' for SDK sendBitmap.")
                    passwordToUseForSdk = "1234".toByteArray(StandardCharsets.US_ASCII)
                }

                val passwordLogOutput = when {
                    passwordToUseForSdk == null -> "null"
                    passwordToUseForSdk.isEmpty() -> "empty string"
                    else -> "********"
                }
                Log.d("NfcFlasher_flashBitmap", "Calling WaveShareHandler.sendBitmap (password: $passwordLogOutput)")

                val result = waveShareHandler.sendBitmap(
                    nfcConnection,
                    screenSizeEnum,
                    bitmapToFlash,
                    passwordToUseForSdk
                )

                runOnUiThread {
                    val toastMsg = if (result.success) "書き込み成功！" else "書き込み失敗: ${result.errMessage}"
                    Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                    if (result.success) {
                        Log.i("NfcFlasher_flashBitmap", "sendBitmap successful.")
                    } else {
                        Log.e("NfcFlasher_flashBitmap", "sendBitmap failed: ${result.errMessage}")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("NfcFlasher_flashBitmap", "IOException during flashBitmap's IO context: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e("NfcFlasher_flashBitmap", "Generic exception in flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            val nfcToClose = nfcAForClosing
            try {
                if (nfcToClose?.isConnected == true) {
                    nfcToClose.close()
                    Log.i("NfcFlasher_flashBitmap", "NfcA connection explicitly closed in finally (if app was owner).")
                }
            } catch (e: IOException) {
                Log.e("NfcFlasher_flashBitmap", "Error closing NfcA in finally: ${e.message}", e)
            }
            this.mIsFlashing = false
            Log.i("NfcFlasher_flashBitmap", "Flashing process finished (in finally block).")
        }
    }

    private fun enableForegroundDispatch() {
        Log.d("NfcFlasher", "Attempting to enable foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            try {
                mNfcAdapter?.enableForegroundDispatch(this, this.mPendingIntent, this.mNfcIntentFilters, this.mNfcTechList)
                Log.i("NfcFlasher", "Foreground dispatch enabled.")
            } catch (ex: IllegalStateException) {
                Log.e("NfcFlasher", "Error enabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w("NfcFlasher", "NFC is disabled, cannot enable foreground dispatch.")
        }
    }

    private fun disableForegroundDispatch() {
        Log.d("NfcFlasher", "Attempting to disable foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            try {
                mNfcAdapter?.disableForegroundDispatch(this)
                Log.i("NfcFlasher", "Foreground dispatch disabled.")
            } catch (ex: IllegalStateException) {
                Log.e("NfcFlasher", "Error disabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w("NfcFlasher", "NFC adapter not available or disabled, cannot disable dispatch.")
        }
    }

    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null && mNfcAdapter?.isEnabled == true) {
            Log.v("NFC_Check_Loop", "NFC Check Loop STARTED")
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(mNfcCheckCallback, mNfcCheckIntervalMs)
        } else if (mNfcCheckHandler != null) {
            Log.v("NFC_Check_Loop", "NFC Check Loop already running.")
        } else {
            Log.w("NFC_Check_Loop", "NFC adapter disabled, not starting check loop.")
        }
    }

    private fun stopNfcCheckLoop() {
        if (mNfcCheckHandler != null) {
            Log.v("NFC_Check_Loop", "NFC Check Loop STOPPED")
            mNfcCheckHandler?.removeCallbacks(mNfcCheckCallback)
            mNfcCheckHandler = null
        }
    }

    private fun checkNfcAndAttemptRecover() {
        if (mNfcAdapter == null) {
            Log.e("NFC_Check", "NFC Adapter is null! Re-initializing.")
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (mNfcAdapter == null) {
                Log.e("NFC_Check", "Failed to re-initialize NFC Adapter. NFC might not be supported.")
                Toast.makeText(this, "NFCが利用できません。", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (mNfcAdapter?.isEnabled == false) {
            Log.w("NFC_Check", "NFC is currently disabled by user.")
        }
    }
}
