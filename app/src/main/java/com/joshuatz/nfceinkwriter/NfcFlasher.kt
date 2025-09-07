package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color // Colorクラスをインポート
import android.graphics.Matrix
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
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
    // mNfcTechList は enableForegroundDispatch で直接定義するためここでは不要
    private var mNfcIntentFilters: Array<IntentFilter>? = null
    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L
    private var mProgressBar: ProgressBar? = null
    private var mOriginalBitmap: Bitmap? = null
    private var mPreparedBitmapForEpd: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mImgFileUri: Uri? = null

    // EPDの物理的なサイズ (2.7インチ WaveShare NFC電子ペーパーを想定)
    // これは回転「前」の、EPD_send.java や Utils.convertBitmapToEpdData の originalBitmap として期待するサイズ
    private val EPD_PHYSICAL_WIDTH = 264
    private val EPD_PHYSICAL_HEIGHT = 176

    // EPDの画面データレイアウトの目標サイズ (回転「後」)
    // Utils.convertBitmapToEpdData の targetDataLayoutWidth/Height や、picSendの実際の寸法
    private val EPD_LAYOUT_WIDTH = 176
    private val EPD_LAYOUT_HEIGHT = 264


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
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val decodedBitmap = BitmapFactory.decodeStream(inputStream)
                    if (decodedBitmap != null) {
                        this.mOriginalBitmap = decodedBitmap
                        this.mPreparedBitmapForEpd = prepareBitmapForEpd(decodedBitmap)
                        imagePreviewElem.setImageBitmap(this.mPreparedBitmapForEpd ?: this.mOriginalBitmap)
                    } else {
                        Log.e("NfcFlasher_onCreate", "Failed to decode bitmap from URI: $uri")
                        Toast.makeText(this, "画像のデコードに失敗しました。", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                Log.e("NfcFlasher_onCreate", "Error loading bitmap from URI: $uri", e)
                Toast.makeText(this, "画像の読み込みに失敗しました。", Toast.LENGTH_LONG).show()
            } catch (e: OutOfMemoryError) {
                Log.e("NfcFlasher_onCreate", "OutOfMemoryError loading bitmap from URI: $uri", e)
                Toast.makeText(this, "画像のメモリ不足エラー。", Toast.LENGTH_LONG).show()
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
        try {
            techIntentFilter.addDataType("*/*")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e("NfcFlasher", "Malformed MimeType for IntentFilter", e)
            throw RuntimeException("Failed to add MIME type.", e)
        }
        mNfcIntentFilters = arrayOf(techIntentFilter)


        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "このスマホではNFC機能が利用できません。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
    }

    private fun prepareBitmapForEpd(sourceBitmap: Bitmap): Bitmap {
        val targetWidth = EPD_PHYSICAL_WIDTH
        val targetHeight = EPD_PHYSICAL_HEIGHT

        // ソースビットマップが既にターゲットの物理サイズで、かつアルファチャンネルが全て不透明か、
        // あるいはARGB_8888でない場合は、一度ARGB_8888に変換して処理する。
        // これは白黒2値化の品質を上げるため。
        val workingBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
            Log.d("NfcFlasher_prepare", "Converting source bitmap to ARGB_8888 for processing.")
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            sourceBitmap
        }

        val scaledBitmap: Bitmap
        if (workingBitmap.width == targetWidth && workingBitmap.height == targetHeight) {
            Log.d("NfcFlasher_prepare", "Source bitmap is already target physical size ($targetWidth x $targetHeight).")
            scaledBitmap = workingBitmap
        } else {
            Log.d("NfcFlasher_prepare", "Resizing bitmap from ${workingBitmap.width}x${workingBitmap.height} to ${targetWidth}x${targetHeight}")
            scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, targetWidth, targetHeight, true)
        }
        // createScaledBitmapで元のBitmapが不要になったらrecycleする (workingBitmapがsourceBitmapと異なる場合)
        if (workingBitmap !== sourceBitmap && workingBitmap !== scaledBitmap) {
            workingBitmap.recycle()
        }

        return convertToMonochrome(scaledBitmap)
    }

    private fun convertToMonochrome(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            // Utils.convertBitmapToEpdData と同じグレースケール変換と閾値処理
            val r = Color.red(p)    // (p shr 16 and 0xff)
            val g = Color.green(p)  // (p shr 8 and 0xff)
            val b = Color.blue(p)   // (p and 0xff)
            // val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt() // Utilsと同じ加重平均
            // よりシンプルな平均や、EPD_send.javaの元々の `(pixel & 0xff) > 128` (青チャネルや単一チャネル輝度) に合わせることも検討
            val gray = (r + g + b) / 3 // Utils.ktでは加重平均だったが、ここでは単純平均の例。統一推奨。
            // Utils.ktの (0.299 * r + 0.587 * g + 0.114 * b) に合わせるなら以下
            // val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()


            pixels[i] = if (gray > 128) Color.WHITE else Color.BLACK
        }
        // ARGB_8888 で白黒ビットマップを作成 (EPDライブラリが期待する形式かもしれない)
        val monochromeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        monochromeBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return monochromeBitmap
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
        val targetEpdModel = 6 // 2.7インチを想定

        val action = intent.action

        if (NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action || // Should not happen with techfilter + mimetype
            NfcAdapter.ACTION_TAG_DISCOVERED == action) { // Should not happen with techfilter

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

            val currentBitmapToFlash = this.mPreparedBitmapForEpd
            if (currentBitmapToFlash == null) {
                Log.w("NfcFlasher_onNewIntent", "Bitmap to flash is null. Cannot flash.")
                Toast.makeText(this, "画像データが準備されていません。", Toast.LENGTH_SHORT).show()
                return
            }

            if (!mIsFlashing) {
                Log.i("NfcFlasher_onNewIntent", "Preparing to flash image...")
                lifecycleScope.launch {
                    if (techListString.contains(IsoDep::class.java.name)) { // クラス名で比較
                        Log.i("NfcFlasher_onNewIntent", "IsoDep detected. Attempting new firmware flashing logic.")
                        flashBitmapWithIsoDep(detectedTag, currentBitmapToFlash, targetEpdModel)
                    } else if (techListString.contains(NfcA::class.java.name)) { // クラス名で比較
                        Log.i("NfcFlasher_onNewIntent", "NfcA detected (and not IsoDep). Attempting old firmware flashing logic.")
                        flashBitmapWithNfcA(detectedTag, currentBitmapToFlash, screenSizeEnumForWaveShareHandler)
                    } else {
                        Log.w("NfcFlasher_onNewIntent", "No supported NFC technology found for flashing (IsoDep or NfcA). Supported: $techListString")
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

        // bitmapToFlash は EPD_PHYSICAL_WIDTH x EPD_PHYSICAL_HEIGHT (264x176) で白黒2値化済み

        try {
            withContext(Dispatchers.IO) {
                isoDep = IsoDep.get(tag)
                // ... (isoDep の接続チェックはそのまま)
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
                isoDep?.timeout = 7000

                Log.i("NfcFlasher_IsoDep", "Starting IsoDep flashing for EPD=$epdModel (2.7 inch)")

                // ... (初期化コマンドはそのまま)
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

                val (picSend, _) = Utils.convertBitmapToEpdData(
                    bitmapToFlash,
                    EPD_LAYOUT_WIDTH,
                    EPD_LAYOUT_HEIGHT,
                    rotate270 = true,
                    invertPackedBits = true
                )
                val expectedPicSendSize = EPD_LAYOUT_WIDTH * EPD_LAYOUT_HEIGHT / 8
                Log.i("NfcFlasher_IsoDep", "Image prepared by Utils.convertBitmapToEpdData. picSend size: ${picSend.size} bytes. Expected: $expectedPicSendSize bytes.")
                if (picSend.size != expectedPicSendSize) {
                    Log.w("NfcFlasher_IsoDep", "Warning: picSend size (${picSend.size}) does not match expected size ($expectedPicSendSize).")
                }
                runOnUiThread { mProgressBar?.progress = 20 }

                val dtm1Cmd = byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x24)
                var response = isoDep?.transceive(dtm1Cmd)
                if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                    Log.e("NfcFlasher_IsoDep", "DTM1 CMD (0x24) failed: ${dtm1Cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                    throw IOException("データ送信開始コマンド(0x24)失敗")
                }
                Log.i("NfcFlasher_IsoDep", "DTM1 (0x24) sent.")
                runOnUiThread { mProgressBar?.progress = 22 } // プログレスは少しだけ進める

                // ★ 変更点: 圧縮処理をバイパス
                // val compressedData = Utils.compressEpdData(picSend, picSend.size)
                // Log.i("NfcFlasher_IsoDep", "Image data compressed. Original: ${picSend.size}, Compressed: ${compressedData.size}")
                val dataToSend = picSend // ★ 変更点: 送信するデータを picSend (非圧縮) にする
                Log.i("NfcFlasher_IsoDep", "Skipping compression. Sending raw data. Size: ${dataToSend.size}")
                // runOnUiThread { mProgressBar?.progress = 25 } // 圧縮ステップがないので25%への更新はスキップしても良い

                val sendDataCmdHeaderWithP3 = byteArrayOf(
                    0x74.toByte(), 0x9E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                val maxChunkSize = 1016
                var sentBytes = 0
                // ★ 変更点: ループの継続条件と対象データを dataToSend (picSend) に変更
                while (sentBytes < dataToSend.size) {
                    val remainingBytes = dataToSend.size - sentBytes
                    val chunkSize = remainingBytes.coerceAtMost(maxChunkSize)
                    // ★ 変更点: チャンクを dataToSend (picSend) からコピー
                    val chunk = dataToSend.copyOfRange(sentBytes, sentBytes + chunkSize)

                    val fullCmd = ByteArray(sendDataCmdHeaderWithP3.size + 2 + chunk.size)
                    System.arraycopy(sendDataCmdHeaderWithP3, 0, fullCmd, 0, sendDataCmdHeaderWithP3.size)
                    fullCmd[sendDataCmdHeaderWithP3.size] = (chunkSize shr 8 and 0xFF).toByte()
                    fullCmd[sendDataCmdHeaderWithP3.size + 1] = (chunkSize and 0xFF).toByte()
                    System.arraycopy(chunk, 0, fullCmd, sendDataCmdHeaderWithP3.size + 2, chunk.size)

                    response = isoDep?.transceive(fullCmd)
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e("NfcFlasher_IsoDep", "Send Data chunk failed. Offset: $sentBytes. Cmd (${fullCmd.size}B): ${fullCmd.toHexString(fullCmd.size)}. Response (${response?.size ?: 0}B): ${response?.toHexString() ?: "null"}")
                        throw IOException("データ送信失敗 (オフセット: $sentBytes)")
                    }
                    sentBytes += chunkSize
                    // ★ 変更点: プログレス計算の分母を dataToSend.size に変更
                    val progressPercentage = 22 + (sentBytes * 68 / dataToSend.size).coerceIn(0,68) // 22%から90%の間
                    runOnUiThread { mProgressBar?.progress = progressPercentage }
                    Log.d("NfcFlasher_IsoDep", "Sent $chunkSize bytes. Total sent: $sentBytes / ${dataToSend.size}. Progress: $progressPercentage%")
                }
                Log.i("NfcFlasher_IsoDep", "All image data sent.")
                runOnUiThread { mProgressBar?.progress = 90 }

                // ... (リフレッシュコマンド、ビジーウェイトはそのまま)
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
                    Log.d("NfcFlasher_IsoDep", "Still busy or unexpected response: ${response?.toHexString() ?: "null"}. Retry: ${busyRetries + 1}/$maxBusyRetries")
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
            // ... (finallyブロックはそのまま)
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
        var nfcAForClosing: NfcA? = null // WaveShareHandlerが内部でクローズすることを期待
        var progressAnimatorJob: Job? = null

        // bitmapToFlash は EPD_PHYSICAL_WIDTH x EPD_PHYSICAL_HEIGHT (264x176) で白黒2値化済み
        // WaveShareHandler (内部の EPD_send.java) で270度回転される想定

        try {
            withContext(Dispatchers.IO) {
                val nfcConnection = NfcA.get(tag)
                if (nfcConnection == null) {
                    Log.e("NfcFlasher_flashBitmapWithNfcA", "NfcA technology not available for this tag.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "NFCエラー: NfcA非対応のタグです。", Toast.LENGTH_LONG).show()
                    }
                    this@NfcFlasher.mIsFlashing = false
                    return@withContext
                }
                // nfcAForClosing = nfcConnection // WaveShareHandlerがクローズするので不要かもしれない

                val passwordForOldSdk = "1234".toByteArray(StandardCharsets.US_ASCII)
                Log.i("NfcFlasher_flashBitmapWithNfcA", "Old firmware (NfcA). Using password '1234' for WaveShareHandler.sendBitmap.")

                withContext(Dispatchers.Main) {
                    mProgressBar?.progress = 10
                }

                progressAnimatorJob = CoroutineScope(Dispatchers.Main + Job()).launch {
                    var currentSimulatedProgress = 10
                    val estimatedDurationMs = 15000L // NfcAは時間がかかるので長めに
                    val updateIntervalMs = 200L
                    val progressSteps = (estimatedDurationMs / updateIntervalMs).toInt()
                    val progressIncrement = if (progressSteps > 0) (80 / progressSteps).coerceAtLeast(1) else 1

                    for (i in 0 until progressSteps) {
                        if (!this@NfcFlasher.mIsFlashing || !isActive) break
                        currentSimulatedProgress += progressIncrement
                        mProgressBar?.progress = currentSimulatedProgress.coerceIn(10, 90)
                        delay(updateIntervalMs)
                    }
                }

                val result = waveShareHandler.sendBitmap(
                    nfcConnection,
                    screenSizeEnumForWaveShare,
                    bitmapToFlash, // 準備済みのBitmap (264x176, 白黒)
                    passwordForOldSdk
                )

                progressAnimatorJob?.cancelAndJoin()

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
            progressAnimatorJob?.cancel()
            Log.e("NfcFlasher_flashBitmapWithNfcA", "IOException during NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            progressAnimatorJob?.cancel()
            Log.e("NfcFlasher_flashBitmapWithNfcA", "Generic exception in NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            progressAnimatorJob?.cancel()
            // WaveShareHandler が nfcConnection をクローズすることを期待するため、ここでは明示的にクローズしない。
            // もし WaveShareHandler がクローズしない場合は、ここで nfcAForClosing を使用してクローズする必要がある。
            this.mIsFlashing = false
            Log.i("NfcFlasher_flashBitmapWithNfcA", "NfcA Flashing process finished (in finally block).")
        }
    }

    private fun enableForegroundDispatch() {
        Log.d("NfcFlasher", "Attempting to enable foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            try {
                val techListsArray = arrayOf( // 両方のテクノロジーをリッスン
                    arrayOf(NfcA::class.java.name),
                    arrayOf(IsoDep::class.java.name)
                )
                mNfcAdapter?.enableForegroundDispatch(this, this.mPendingIntent, this.mNfcIntentFilters, techListsArray)
                Log.i("NfcFlasher", "Foreground dispatch enabled for NfcA and IsoDep.")
            } catch (ex: IllegalStateException) {
                Log.e("NfcFlasher", "Error enabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w("NfcFlasher", "NFC is disabled, cannot enable foreground dispatch.")
        }
    }

    private fun disableForegroundDispatch() {
        Log.d("NfcFlasher", "Attempting to disable foreground dispatch.")
        // Activityが破棄処理中でないことを確認
        if (mNfcAdapter?.isEnabled == true && !isFinishing && !isDestroyed) {
            try {
                mNfcAdapter?.disableForegroundDispatch(this)
                Log.i("NfcFlasher", "Foreground dispatch disabled.")
            } catch (ex: IllegalStateException) {
                Log.e("NfcFlasher", "Error disabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w("NfcFlasher", "NFC adapter not available/disabled or activity finishing, cannot disable dispatch.")
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
            // ここでユーザーにNFCを有効にするよう促すUIを表示することも検討できます
            // 例: Snackbar.make(findViewById(android.R.id.content), "NFCを有効にしてください", Snackbar.LENGTH_INDEFINITE).setAction("設定") { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }.show()
        }
    }
}

// ByteArray.toHexString 拡張関数 (変更なし)
internal fun ByteArray.toHexString(length: Int = this.size): String {
    val effectiveLength = length.coerceAtMost(this.size)
    return this.take(effectiveLength).joinToString(separator = " ") { "%02X".format(it) }
}


