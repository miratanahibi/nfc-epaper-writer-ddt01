package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
//import androidx.glance.visibility
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job // Jobをインポート
import kotlinx.coroutines.delay // delayをインポート
import kotlinx.coroutines.isActive // isActiveをインポート
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin // cancelAndJoinをインポート
import waveshare.feng.nfctag.activity.WaveShareHandler
import java.io.IOException
import java.nio.charset.StandardCharsets
// import kotlin.collections.copyOfRange // 必要であればコメント解除
// import kotlin.ranges.coerceAtMost // 必要であればコメント解除
// import kotlin.ranges.coerceIn // 必要であればコメント解除
// import kotlin.text.toByte // 必要であればコメント解除

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
    private var mNfcTechList = arrayOf(
        arrayOf(NfcA::class.java.name),
        arrayOf(IsoDep::class.java.name)
    )
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
        val screenSizeEnumForWaveShareHandler = preferences.getScreenSizeEnum()
        val targetEpdModel = 6

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
            val techListString = detectedTag.techList.joinToString(", ")
            Log.i("NfcFlasher_onNewIntent", "Available Tag Technologies: $techListString")

            val currentBitmap = this.mBitmap
            if (currentBitmap == null) {
                Log.w("NfcFlasher_onNewIntent", "Bitmap is null. Cannot flash.")
                Toast.makeText(this, "画像データがありません。", Toast.LENGTH_SHORT).show()
                return
            }

            if (!mIsFlashing) {
                Log.i("NfcFlasher_onNewIntent", "Preparing to flash image...")
                lifecycleScope.launch {
                    if (techListString.contains("android.nfc.tech.IsoDep")) {
                        Log.i("NfcFlasher_onNewIntent", "IsoDep detected. Attempting new firmware flashing logic.")
                        flashBitmapWithIsoDep(detectedTag, currentBitmap, targetEpdModel)
                    } else if (techListString.contains("android.nfc.tech.NfcA")) {
                        Log.i("NfcFlasher_onNewIntent", "NfcA detected (and not IsoDep). Attempting old firmware flashing logic.")
                        flashBitmapWithNfcA(detectedTag, currentBitmap, screenSizeEnumForWaveShareHandler)
                    } else {
                        Log.w("NfcFlasher_onNewIntent", "No supported NFC technology found for flashing (IsoDep or NfcA).")
                        runOnUiThread { Toast.makeText(applicationContext, "対応していないNFCタグタイプです。", Toast.LENGTH_LONG).show() }
                    }
                }
            } else {
                Log.w("NfcFlasher_onNewIntent", "Flashing operation already in progress. Ignoring new tag.")
            }
        } else {
            Log.d("NfcFlasher_onNewIntent", "Intent action '${intent.action}' not handled for flashing.")
        }
    }

    private suspend fun flashBitmapWithIsoDep(tag: Tag, bitmapToFlash: Bitmap, epdModel: Int) {
        if (mIsFlashing) {
            Log.w("NfcFlasher_flashBitmapWithIsoDep", "Attempted to start flash while already flashing. Aborting.")
            return
        }
        this.mIsFlashing = true
        var isoDep: IsoDep? = null
        var success = false

        try {
            withContext(Dispatchers.IO) {
                isoDep = IsoDep.get(tag)
                if (isoDep == null) {
                    Log.e("NfcFlasher_flashBitmapWithIsoDep", "IsoDep technology not available for this tag.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFCエラー: IsoDep非対応のタグです。", Toast.LENGTH_LONG).show() }
                    return@withContext
                }

                isoDep?.connect()
                if (isoDep?.isConnected != true) {
                    Log.e("NfcFlasher_flashBitmapWithIsoDep", "Failed to connect to IsoDep.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFC接続エラー (IsoDep)", Toast.LENGTH_LONG).show() }
                    return@withContext
                }
                isoDep?.timeout = 5000

                Log.i("NfcFlasher_IsoDep", "Starting IsoDep flashing for EPD=$epdModel (2.7 inch)")

                val initCommands = listOf(
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x21), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x02, 0x48, 0x00)),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x18), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0x80.toByte())),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x22), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0xB1.toByte())),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20), null),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x1A), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x02, 0x64,0x00)),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x45), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x04, 0x00, 0x00, 0x07, 0x01)),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x4F.toByte()), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x02, 0x00, 0x00)),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x11), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0x03)),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x22), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0x91.toByte())),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20), null)
                )

                var currentProgress = 0
                val totalInitSteps = initCommands.size * 2
                fun updateInitProgress() {
                    currentProgress++
                    val progressPercentage = (currentProgress * 100 / totalInitSteps).coerceIn(0,15)
                    runOnUiThread { mProgressBar?.progress = progressPercentage }
                }

                for ((cmd, data) in initCommands) {
                    var response = isoDep?.transceive(cmd)
                    updateInitProgress()
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e("NfcFlasher_IsoDep", "Init CMD failed: ${cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                        throw IOException("初期化コマンド失敗: ${cmd.toHexString()}")
                    }
                    if (data != null) {
                        response = isoDep?.transceive(data)
                        updateInitProgress()
                        if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                            Log.e("NfcFlasher_IsoDep", "Init DATA failed for CMD ${cmd.toHexString()}: ${data.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                            throw IOException("初期化データ失敗: ${data.toHexString()}")
                        }
                    }
                    if (cmd.contentEquals(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20))) {
                        SystemClock.sleep(100)
                    }
                }
                Log.i("NfcFlasher_IsoDep", "Initialization sequence complete.")
                runOnUiThread { mProgressBar?.progress = 15 }

                val epdNativeWidth = 176
                val epdNativeHeight = 264
                val (picSend, _) = Utils.convertBitmapToEpdData(bitmapToFlash, epdNativeWidth, epdNativeHeight, rotate270 = true, invertPackedBits = true)
                Log.i("NfcFlasher_IsoDep", "Image prepared. Size: ${picSend.size} bytes.")
                runOnUiThread { mProgressBar?.progress = 20 }

                val dtm1Cmd = byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x24)
                var response = isoDep?.transceive(dtm1Cmd)
                if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                    Log.e("NfcFlasher_IsoDep", "DTM1 CMD (0x24) failed: ${dtm1Cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                    throw IOException("データ送信開始コマンド(0x24)失敗")
                }
                Log.i("NfcFlasher_IsoDep", "DTM1 (0x24) sent.")
                runOnUiThread { mProgressBar?.progress = 22 }

                val compressedData = Utils.compressEpdData(picSend, 5796)
                Log.i("NfcFlasher_IsoDep", "Image data compressed. Original: ${picSend.size}, Compressed: ${compressedData.size}")
                runOnUiThread { mProgressBar?.progress = 25 }

                val sendDataCmdHeaderWithP3 = byteArrayOf(
                    0x74.toByte(), 0x9E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                val maxChunkSize = 1016
                var sentBytes = 0
                while (sentBytes < compressedData.size) {
                    val remainingBytes = compressedData.size - sentBytes
                    val chunkSize = remainingBytes.coerceAtMost(maxChunkSize)
                    val chunk = compressedData.copyOfRange(sentBytes, sentBytes + chunkSize)
                    val fullCmd = ByteArray(sendDataCmdHeaderWithP3.size + 2 + chunk.size)
                    System.arraycopy(sendDataCmdHeaderWithP3, 0, fullCmd, 0, sendDataCmdHeaderWithP3.size)
                    fullCmd[sendDataCmdHeaderWithP3.size] = (chunkSize shr 8 and 0xFF).toByte()
                    fullCmd[sendDataCmdHeaderWithP3.size + 1] = (chunkSize and 0xFF).toByte()
                    System.arraycopy(chunk, 0, fullCmd, sendDataCmdHeaderWithP3.size + 2, chunk.size)

                    response = isoDep?.transceive(fullCmd)
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e("NfcFlasher_IsoDep", "Send Data chunk failed. Offset: $sentBytes. Cmd: ${fullCmd.toHexString(chunkSize + sendDataCmdHeaderWithP3.size + 2)}. Response: ${response?.toHexString() ?: "null"}")
                        throw IOException("データ送信失敗 (オフセット: $sentBytes)")
                    }
                    sentBytes += chunkSize
                    val progressPercentage = 25 + (sentBytes * 65 / compressedData.size).coerceIn(0,65)
                    runOnUiThread { mProgressBar?.progress = progressPercentage }
                    Log.d("NfcFlasher_IsoDep", "Sent $chunkSize bytes. Total sent: $sentBytes / ${compressedData.size}. Progress: $progressPercentage%")
                }
                Log.i("NfcFlasher_IsoDep", "All image data sent.")
                runOnUiThread { mProgressBar?.progress = 90 }

                val refreshCmds = listOf(
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x22), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0xC7.toByte())),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20), null)
                )
                for ((cmd, data) in refreshCmds) {
                    response = isoDep?.transceive(cmd)
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e("NfcFlasher_IsoDep", "Refresh CMD failed: ${cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                        throw IOException("リフレッシュコマンド失敗: ${cmd.toHexString()}")
                    }
                    if (data != null) {
                        response = isoDep?.transceive(data)
                        if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                            Log.e("NfcFlasher_IsoDep", "Refresh DATA failed: ${data.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                            throw IOException("リフレッシュデータ失敗: ${data.toHexString()}")
                        }
                    }
                }
                Log.i("NfcFlasher_IsoDep", "Refresh sequence sent.")
                runOnUiThread { mProgressBar?.progress = 95 }

                SystemClock.sleep(1000)
                val busyCmd = byteArrayOf(0x74, 0x9B.toByte(), 0x00, 0x0F.toByte(), 0x01)
                var busyRetries = 0
                val maxBusyRetries = 100
                while (busyRetries < maxBusyRetries) {
                    response = isoDep?.transceive(busyCmd)
                    if (response != null && response.isNotEmpty() && response[0] == 0x00.toByte()) {
                        Log.i("NfcFlasher_IsoDep", "Device no longer busy. Response: ${response.toHexString()}")
                        break
                    }
                    Log.d("NfcFlasher_IsoDep", "Still busy or unexpected response: ${response?.toHexString() ?: "null"}. Retry: $busyRetries")
                    busyRetries++
                    if (busyRetries >= maxBusyRetries) {
                        Log.w("NfcFlasher_IsoDep", "Busy wait timeout after $maxBusyRetries retries.")
                    }
                    SystemClock.sleep(250)
                }
                success = true
                runOnUiThread { mProgressBar?.progress = 100 }
            }
        } catch (e: IOException) {
            Log.e("NfcFlasher_flashBitmapWithIsoDep", "IOException: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー(I): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e("NfcFlasher_flashBitmapWithIsoDep", "Generic exception: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー(I): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            try {
                isoDep?.close()
                Log.i("NfcFlasher_flashBitmapWithIsoDep", "IsoDep connection closed.")
            } catch (e: IOException) {
                Log.e("NfcFlasher_flashBitmapWithIsoDep", "Error closing IsoDep: ${e.message}", e)
            }
            this.mIsFlashing = false
            runOnUiThread {
                val toastMsg = if (success) "書き込み成功！ (IsoDep)" else "書き込み失敗 (IsoDep)"
                Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                if (success) {
                    Log.i("NfcFlasher_flashBitmapWithIsoDep", "Flashing successful via IsoDep.")
                } else {
                    Log.e("NfcFlasher_flashBitmapWithIsoDep", "Flashing failed via IsoDep.")
                }
            }
            Log.i("NfcFlasher_flashBitmapWithIsoDep", "IsoDep Flashing process finished (in finally block).")
        }
    }

    private suspend fun flashBitmapWithNfcA(tag: Tag, bitmapToFlash: Bitmap, screenSizeEnumForWaveShare: Int) {
        if (mIsFlashing) {
            Log.w("NfcFlasher_flashBitmapWithNfcA", "Attempted to start flash while already flashing. Aborting.")
            return
        }
        this.mIsFlashing = true
        val waveShareHandler = WaveShareHandler(this@NfcFlasher)
        var nfcAForClosing: NfcA? = null
        var progressAnimatorJob: Job? = null // Jobを保持する変数を追加

        try {
            // NfcAの処理はIOスレッドで行い、UI更新はメインスレッドで行う
            withContext(Dispatchers.IO) {
                val nfcConnection = NfcA.get(tag)
                if (nfcConnection == null) {
                    Log.e("NfcFlasher_flashBitmapWithNfcA", "NfcA technology not available for this tag.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "NFCエラー: NfcA非対応のタグです。", Toast.LENGTH_LONG).show()
                    }
                    this@NfcFlasher.mIsFlashing = false // Reset flashing state early
                    return@withContext
                }
                nfcAForClosing = nfcConnection

                val passwordForOldSdk = "1234".toByteArray(StandardCharsets.US_ASCII)
                Log.i("NfcFlasher_flashBitmapWithNfcA", "Old firmware (NfcA). Using password '1234' for WaveShareHandler.sendBitmap.")

                // UIスレッドで初期プログレスを設定
                withContext(Dispatchers.Main) {
                    mProgressBar?.progress = 10
                }

                // プログレスバーを擬似的に動かすコルーチンを開始
                progressAnimatorJob = CoroutineScope(Dispatchers.Main + Job()).launch { // 新しいJobで起動
                    var currentSimulatedProgress = 10
                    val estimatedDurationMs = 5000L // WaveShare SDKのおおよその処理時間 (要調整、例: 5秒)
                    val updateIntervalMs = 200L
                    val progressSteps = (estimatedDurationMs / updateIntervalMs).toInt()
                    val progressIncrement = if (progressSteps > 0) (80 / progressSteps).coerceAtLeast(1) else 1 // 10%から90%の間を分割

                    for (i in 0 until progressSteps) {
                        if (!this@NfcFlasher.mIsFlashing || !isActive) break
                        currentSimulatedProgress += progressIncrement
                        mProgressBar?.progress = currentSimulatedProgress.coerceIn(10, 90)
                        delay(updateIntervalMs)
                    }
                }

                // SDKの処理を実行 (これが時間のかかる処理)
                val result = waveShareHandler.sendBitmap(
                    nfcConnection,
                    screenSizeEnumForWaveShare,
                    bitmapToFlash,
                    passwordForOldSdk
                )

                progressAnimatorJob?.cancelAndJoin() // アニメーションコルーチンを確実に停止し、完了を待つ

                // UIスレッドで最終的なプログレスとトーストを設定
                withContext(Dispatchers.Main) {
                    mProgressBar?.progress = if (result.success) 100 else (mProgressBar?.progress ?: 50)
                    val toastMsg = if (result.success) "書き込み成功！ (NfcA)" else "書き込み失敗 (NfcA): ${result.errMessage}"
                    Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                    if (result.success) {
                        Log.i("NfcFlasher_flashBitmapWithNfcA", "WaveShareHandler.sendBitmap successful.")
                    } else {
                        Log.e("NfcFlasher_flashBitmapWithNfcA", "WaveShareHandler.sendBitmap failed: ${result.errMessage}")
                    }
                }
            }
        } catch (e: IOException) {
            progressAnimatorJob?.cancel() // エラー時もアニメーションを停止
            Log.e("NfcFlasher_flashBitmapWithNfcA", "IOException during NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            progressAnimatorJob?.cancel() // エラー時もアニメーションを停止
            Log.e("NfcFlasher_flashBitmapWithNfcA", "Generic exception in NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            progressAnimatorJob?.cancel() // finallyでも念のため停止
            val nfcToClose = nfcAForClosing
            try {
                if (nfcToClose?.isConnected == true) {
                    withContext(Dispatchers.IO) { // Close on IO thread
                        nfcToClose.close()
                    }
                    Log.i("NfcFlasher_flashBitmapWithNfcA", "NfcA connection explicitly closed in finally.")
                }
            } catch (e: IOException) {
                Log.e("NfcFlasher_flashBitmapWithNfcA", "Error closing NfcA in finally: ${e.message}", e)
            }
            this.mIsFlashing = false // Ensure mIsFlashing is reset
            Log.i("NfcFlasher_flashBitmapWithNfcA", "NfcA Flashing process finished (in finally block).")
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

internal fun ByteArray.toHexString(length: Int = this.size): String {
    val effectiveLength = length.coerceAtMost(this.size)
    return this.take(effectiveLength).joinToString(separator = " ") { "%02X".format(it) }
}

