package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.widget.Switch
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
import android.widget.Button // 追加
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
// import androidx.core.text.color // 未使用の可能性
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
import kotlin.math.roundToInt
// import kotlin.text.toFloat // 未使用の可能性
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.core.text.color
import java.io.File
import java.io.FileOutputStream

// 定数など
private const val TAG_NFL = "NfcFlasher" // こちらを使用
// private const val TAG = "NfcFlasher" // 削除 (TAG_NFLと重複)

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
    private var mNfcIntentFilters: Array<IntentFilter>? = null
    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L
    private var mProgressBar: ProgressBar? = null

    // --- Bitmaps ---
    private var mOriginalBitmap: Bitmap? = null
    private var mPreparedBitmapForEpd: Bitmap? = null

    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mImgFileUri: Uri? = null

    // --- Filter State ---
    private lateinit var switchDitheringFilter: Switch
    private var isDitheringFilterEnabled: Boolean = false

    private lateinit var switchDotPatternFilter: Switch
    private var isDotPatternFilterEnabled: Boolean = false

    // EPDの物理的なサイズ
    private val EPD_PHYSICAL_WIDTH = 264
    private val EPD_PHYSICAL_HEIGHT = 176

    // EPDの画面データレイアウトの目標サイズ (回転「後」)
    private val EPD_LAYOUT_WIDTH = 176
    private val EPD_LAYOUT_HEIGHT = 264


    private val mNfcCheckCallback: Runnable = object : Runnable {
        override fun run() {
            checkNfcAndAttemptRecover()
            mNfcCheckHandler?.postDelayed(this, mNfcCheckIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)

        // UI要素の取得
        // val imagePreviewElem: ImageView = findViewById(R.id.previewImageView) // コメントアウト済み
        switchDitheringFilter = findViewById(R.id.switchDitheringFilter)
        switchDotPatternFilter = findViewById(R.id.switchDotPatternFilter)
        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)

        // フィルタ状態の復元
        if (savedInstanceState != null) {
            isDitheringFilterEnabled = savedInstanceState.getBoolean("isDitheringFilterEnabled", false)
            isDotPatternFilterEnabled = savedInstanceState.getBoolean("isDotPatternFilterEnabled", false)
        }
        switchDitheringFilter.isChecked = isDitheringFilterEnabled
        switchDotPatternFilter.isChecked = isDotPatternFilterEnabled

        // Bitmapのロード
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
            Log.w(TAG_NFL, "mImgFileUri was null, falling back to GeneratedImageFilename.")
        }

        mImgFileUri?.let { uri ->
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val decodedBitmap = BitmapFactory.decodeStream(inputStream)
                    if (decodedBitmap != null) {
                        this.mOriginalBitmap = decodedBitmap
                        updatePreviewAndPrepareBitmap() // 初期プレビュー更新
                    } else {
                        Log.e(TAG_NFL, "Failed to decode bitmap from URI: $uri")
                        Toast.makeText(this, "画像のデコードに失敗しました。", Toast.LENGTH_LONG).show()
                        finish(); return // 修正
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG_NFL, "Error loading bitmap from URI: $uri", e)
                Toast.makeText(this, "画像の読み込みに失敗しました。", Toast.LENGTH_LONG).show()
                finish(); return // 修正
            } catch (e: OutOfMemoryError) {
                Log.e(TAG_NFL, "OutOfMemoryError loading bitmap: $uri", e)
                Toast.makeText(this, "画像のメモリ不足エラー。", Toast.LENGTH_LONG).show()
                finish(); return // 修正
            }
        } ?: run {
            Log.e(TAG_NFL, "Image URI is null. Cannot load image.")
            Toast.makeText(this, "画像ファイルが見つかりません。", Toast.LENGTH_LONG).show()
            finish(); return // 修正
        }

        mIsFlashing = false

        // スイッチのリスナー設定
        switchDitheringFilter.setOnCheckedChangeListener { _, isChecked ->
            isDitheringFilterEnabled = isChecked
            if (isChecked && isDotPatternFilterEnabled) {
                isDotPatternFilterEnabled = false
                switchDotPatternFilter.isChecked = false
            }
            Log.d(TAG_NFL, "Dithering filter: $isDitheringFilterEnabled, Dot pattern filter: $isDotPatternFilterEnabled")
            updatePreviewAndPrepareBitmap()
        }

        switchDotPatternFilter.setOnCheckedChangeListener { _, isChecked ->
            isDotPatternFilterEnabled = isChecked
            if (isChecked && isDitheringFilterEnabled) {
                isDitheringFilterEnabled = false
                switchDitheringFilter.isChecked = false
            }
            Log.d(TAG_NFL, "Dithering filter: $isDitheringFilterEnabled, Dot pattern filter: $isDotPatternFilterEnabled")
            updatePreviewAndPrepareBitmap()
        }

        // ▼▼▼ ここからボタン機能の追加 ▼▼▼
        val buttonEffect1: Button = findViewById(R.id.buttonEffect1)
        val buttonEffect2: Button = findViewById(R.id.buttonEffect2)
        val buttonEffect3: Button = findViewById(R.id.buttonEffect3)
        val buttonEffect4: Button = findViewById(R.id.buttonEffect4)
        val previewImageView: ImageView = findViewById(R.id.previewImageView)

        val imageFileNames = listOf(
            "slot1_image.png",
            "slot2_image.png",
            "slot3_image.png",
            "slot4_image.png"
        )

        val buttonToFileMap = mapOf(
            R.id.buttonEffect1 to imageFileNames[0],
            R.id.buttonEffect2 to imageFileNames[1],
            R.id.buttonEffect3 to imageFileNames[2],
            R.id.buttonEffect4 to imageFileNames[3]
        )

        buttonToFileMap.forEach { (buttonId, fileName) ->
            val button: Button = findViewById(buttonId)

            button.setOnLongClickListener {
                val bitmapToSave = mPreparedBitmapForEpd ?: getBitmapFromImageView(previewImageView)

                if (bitmapToSave != null) {
                    val success = saveBitmapToInternalStorage(this, bitmapToSave, fileName)
                    if (success) {
                        Toast.makeText(this, "${button.text} の画像を保存しました", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "画像の保存に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "保存する画像がありません", Toast.LENGTH_SHORT).show()
                }
                true
            }

            button.setOnClickListener {
                val loadedBitmap = loadBitmapFromInternalStorage(this, fileName)
                if (loadedBitmap != null) {
                    this.mOriginalBitmap = loadedBitmap
                    updatePreviewAndPrepareBitmap()
                    Toast.makeText(this, "${button.text} の画像を表示し、準備しました", Toast.LENGTH_SHORT).show()
                    Log.d(TAG_NFL, "Loaded bitmap for ${button.text}, set as mOriginalBitmap, and updated preview.")
                } else {
                    Toast.makeText(this, "${button.text} に保存された画像はありません", Toast.LENGTH_SHORT).show()
                    Log.d(TAG_NFL, "No saved bitmap found for ${button.text}.")
                }
            }
        }
        // ▲▲▲ ここまでボタン機能の追加 ▲▲▲

        // NFC関連の初期化
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
            Log.e(TAG_NFL, "Malformed MimeType for IntentFilter", e)
            throw RuntimeException("Failed to add MIME type.", e)
        }
        mNfcIntentFilters = arrayOf(techIntentFilter)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "このスマホではNFC機能が利用できません。", Toast.LENGTH_LONG).show()
            finish()
            return // 修正
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mImgFileUri?.toString()?.let {
            outState.putString("serializedGeneratedImgUri", it)
        }
        outState.putBoolean("isDitheringFilterEnabled", isDitheringFilterEnabled)
        outState.putBoolean("isDotPatternFilterEnabled", isDotPatternFilterEnabled)
    }

    private fun updatePreviewAndPrepareBitmap() {
        if (mOriginalBitmap == null) {
            Log.w(TAG_NFL, "Original bitmap is null, cannot update preview.")
            findViewById<ImageView>(R.id.previewImageView).setImageResource(android.R.drawable.stat_sys_warning)
            mPreparedBitmapForEpd = null
            return
        }

        val bitmapToProcess = mOriginalBitmap!!

        if (isDitheringFilterEnabled) {
            Log.d(TAG_NFL, "Applying Floyd-Steinberg dithering filter...")
            val filteredBitmap = applyHalftoneDitheringFilter(bitmapToProcess)
            mPreparedBitmapForEpd = ensureEpdPhysicalSizeAndMonochrome(filteredBitmap)
        } else if (isDotPatternFilterEnabled) {
            Log.d(TAG_NFL, "Applying halftone dot pattern filter...")
            val filteredBitmap = applyHalftoneDotPatternFilter(bitmapToProcess, cellSize = 2, dotLevels = 8)
            mPreparedBitmapForEpd = ensureEpdPhysicalSizeAndMonochrome(filteredBitmap)
        } else {
            Log.d(TAG_NFL, "No filter. Preparing bitmap for EPD.")
            mPreparedBitmapForEpd = prepareBitmapForEpd(bitmapToProcess)
        }

        findViewById<ImageView>(R.id.previewImageView).setImageBitmap(mPreparedBitmapForEpd)
        Log.d(TAG_NFL, "Preview updated. Dithering: $isDitheringFilterEnabled, DotPattern: $isDotPatternFilterEnabled")
    }

    private fun applyHalftoneDitheringFilter(originalBitmap: Bitmap, threshold: Float = 128.0f): Bitmap {
        Log.d(TAG_NFL, "applyHalftoneDitheringFilter called (Jarvis, Judice, Ninke, Threshold: $threshold)")

        val width = originalBitmap.width
        val height = originalBitmap.height

        val ditheredBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val grayPixels = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = originalBitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                grayPixels[y * width + x] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        val divisor = 48.0f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val currentIndex = y * width + x
                val oldPixelGray = grayPixels[currentIndex]

                val newPixelGray = if (oldPixelGray > threshold) 255f else 0f
                val newPixelColor = if (newPixelGray == 255f) Color.WHITE else Color.BLACK
                ditheredBitmap.setPixel(x, y, newPixelColor)

                val quantError = oldPixelGray - newPixelGray

                if (x + 1 < width) grayPixels[currentIndex + 1] += quantError * 7.0f / divisor
                if (x + 2 < width) grayPixels[currentIndex + 2] += quantError * 5.0f / divisor

                if (y + 1 < height) {
                    if (x - 2 >= 0) grayPixels[(y + 1) * width + (x - 2)] += quantError * 3.0f / divisor
                    if (x - 1 >= 0) grayPixels[(y + 1) * width + (x - 1)] += quantError * 5.0f / divisor
                    grayPixels[(y + 1) * width + x] += quantError * 7.0f / divisor
                    if (x + 1 < width) grayPixels[(y + 1) * width + (x + 1)] += quantError * 5.0f / divisor
                    if (x + 2 < width) grayPixels[(y + 1) * width + (x + 2)] += quantError * 3.0f / divisor
                }

                if (y + 2 < height) {
                    if (x - 2 >= 0) grayPixels[(y + 2) * width + (x - 2)] += quantError * 1.0f / divisor
                    if (x - 1 >= 0) grayPixels[(y + 2) * width + (x - 1)] += quantError * 3.0f / divisor
                    grayPixels[(y + 2) * width + x] += quantError * 5.0f / divisor
                    if (x + 1 < width) grayPixels[(y + 2) * width + (x + 1)] += quantError * 3.0f / divisor
                    if (x + 2 < width) grayPixels[(y + 2) * width + (x + 2)] += quantError * 1.0f / divisor
                }
            }
        }
        Log.d(TAG_NFL, "Jarvis, Judice, Ninke dithering complete.")
        return ditheredBitmap
    }

    private fun applyHalftoneDotPatternFilter(originalBitmap: Bitmap, cellSize: Int = 8, dotLevels: Int = 5): Bitmap {
        Log.d(TAG_NFL, "applyHalftoneDotPatternFilter called (cellSize: $cellSize, levels: $dotLevels)")

        val width = originalBitmap.width
        val height = originalBitmap.height

        val halftoneBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        halftoneBitmap.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(halftoneBitmap)
        val paint = android.graphics.Paint()
        paint.color = Color.BLACK
        paint.style = android.graphics.Paint.Style.FILL

        val grayPixels = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = originalBitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                grayPixels[y * width + x] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        for (cellY in 0 until height step cellSize) {
            for (cellX in 0 until width step cellSize) {
                var sumGray = 0f
                var count = 0

                for (y in cellY until (cellY + cellSize).coerceAtMost(height)) {
                    for (x in cellX until (cellX + cellSize).coerceAtMost(width)) {
                        sumGray += grayPixels[y * width + x]
                        count++
                    }
                }
                if (count == 0) continue
                val avgGrayInCell = sumGray / count

                val normalizedIntensity = avgGrayInCell / 255f
                val blackness = 1.0f - normalizedIntensity
                var dotSizeFactor = 0f

                if (dotLevels > 1) {
                    val levelIndex = (blackness * (dotLevels -1)).coerceIn(0f, (dotLevels -1).toFloat()).roundToInt()
                    dotSizeFactor = levelIndex.toFloat() / (dotLevels -1).toFloat()
                } else if (dotLevels == 1) {
                    dotSizeFactor = if (blackness >= 0.5f) 1.0f else 0.0f
                }

                if (dotSizeFactor > 0) {
                    val dotSideLength = cellSize * dotSizeFactor
                    val offset = (cellSize - dotSideLength) / 2f
                    val rectLeft = cellX + offset
                    val rectTop = cellY + offset
                    val rectRight = cellX + offset + dotSideLength
                    val rectBottom = cellY + offset + dotSideLength
                    if (rectLeft < rectRight && rectTop < rectBottom) {
                        canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, paint)
                    }
                }
            }
        }
        Log.d(TAG_NFL, "Halftone dot pattern filter applied.")
        return halftoneBitmap
    }

    private fun ensureEpdPhysicalSizeAndMonochrome(inputBitmap: Bitmap): Bitmap {
        var processedBitmap = inputBitmap
        if (processedBitmap.config != Bitmap.Config.ARGB_8888) {
            processedBitmap = processedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (processedBitmap.width != EPD_PHYSICAL_WIDTH || processedBitmap.height != EPD_PHYSICAL_HEIGHT) {
            val scaled = Bitmap.createScaledBitmap(processedBitmap, EPD_PHYSICAL_WIDTH, EPD_PHYSICAL_HEIGHT, true)
            if (processedBitmap !== inputBitmap && processedBitmap !== scaled) {
                processedBitmap.recycle()
            }
            processedBitmap = scaled
        }
        return convertToMonochrome(processedBitmap)
    }

    private fun prepareBitmapForEpd(sourceBitmap: Bitmap): Bitmap {
        val targetWidth = EPD_PHYSICAL_WIDTH
        val targetHeight = EPD_PHYSICAL_HEIGHT
        val workingBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
            Log.d(TAG_NFL, "prepareBitmapForEpd: Converting source to ARGB_8888.")
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            sourceBitmap
        }

        val scaledBitmap: Bitmap
        if (workingBitmap.width == targetWidth && workingBitmap.height == targetHeight) {
            Log.d(TAG_NFL, "prepareBitmapForEpd: Source is already target physical size.")
            scaledBitmap = workingBitmap
        } else {
            Log.d(TAG_NFL, "prepareBitmapForEpd: Resizing from ${workingBitmap.width}x${workingBitmap.height} to ${targetWidth}x${targetHeight}")
            scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, targetWidth, targetHeight, true)
        }
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
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            pixels[i] = if (gray > 128) Color.WHITE else Color.BLACK
        }
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
        if (mOriginalBitmap != null) {
            updatePreviewAndPrepareBitmap()
        }
        this.startNfcCheckLoop()
        this.enableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG_NFL, "Received new intent. Action: ${intent.action ?: "no action"}")

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
                Log.e(TAG_NFL, "Detected tag is null.")
                Toast.makeText(this, "NFCタグを検出できませんでした。", Toast.LENGTH_SHORT).show()
                return
            }

            val tagIdHex = detectedTag.id.joinToString("") { "%02x".format(it) }
            Log.i(TAG_NFL, "Tag ID (Hex): $tagIdHex")
            val techListString = detectedTag.techList.joinToString(", ")
            Log.i(TAG_NFL, "Available Tag Technologies: $techListString")

            val currentBitmapToFlash = this.mPreparedBitmapForEpd
            if (currentBitmapToFlash == null) {
                Log.w(TAG_NFL, "Bitmap to flash (mPreparedBitmapForEpd) is null. Cannot flash.")
                Toast.makeText(this, "画像データが準備されていません。", Toast.LENGTH_SHORT).show()
                return
            }

            if (!mIsFlashing) {
                Log.i(TAG_NFL, "Preparing to flash image...")
                lifecycleScope.launch {
                    if (techListString.contains(IsoDep::class.java.name)) {
                        Log.i(TAG_NFL, "IsoDep detected. Attempting new firmware flashing logic.")
                        flashBitmapWithIsoDep(detectedTag, currentBitmapToFlash, targetEpdModel)
                    } else if (techListString.contains(NfcA::class.java.name)) {
                        Log.i(TAG_NFL, "NfcA detected. Attempting old firmware flashing logic.")
                        flashBitmapWithNfcA(detectedTag, currentBitmapToFlash, screenSizeEnumForWaveShareHandler)
                    } else {
                        Log.w(TAG_NFL, "No supported NFC technology found. Supported: $techListString")
                        runOnUiThread { Toast.makeText(applicationContext, "対応していないNFCタグタイプです。", Toast.LENGTH_LONG).show() }
                    }
                }
            } else {
                Log.w(TAG_NFL, "Flashing operation already in progress. Ignoring new tag.")
            }
        } else {
            Log.d(TAG_NFL, "Intent action '${intent.action}' not handled for flashing.")
        }
    }

    private suspend fun flashBitmapWithIsoDep(tag: Tag, bitmapToFlash: Bitmap, epdModel: Int) {
        if (mIsFlashing) {
            Log.w(TAG_NFL, "Attempted to start flash while already flashing. Aborting.")
            return
        }
        this.mIsFlashing = true
        var isoDep: IsoDep? = null
        var success = false

        try {
            withContext(Dispatchers.IO) {
                isoDep = IsoDep.get(tag)
                if (isoDep == null) {
                    Log.e(TAG_NFL, "IsoDep technology not available for this tag.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFCエラー: IsoDep非対応のタグです。", Toast.LENGTH_LONG).show() }
                    return@withContext
                }

                isoDep?.connect()
                if (isoDep?.isConnected != true) {
                    Log.e(TAG_NFL, "Failed to connect to IsoDep.")
                    runOnUiThread { Toast.makeText(applicationContext, "NFC接続エラー (IsoDep)", Toast.LENGTH_LONG).show() }
                    return@withContext
                }
                isoDep?.timeout = 7000

                Log.i(TAG_NFL, "Starting IsoDep flashing for EPD=$epdModel (2.7 inch)")

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
                        Log.e(TAG_NFL, "Init CMD failed: ${cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                        throw IOException("初期化コマンド失敗: ${cmd.toHexString()}")
                    }
                    if (data != null) {
                        response = isoDep?.transceive(data)
                        updateInitProgress()
                        if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                            Log.e(TAG_NFL, "Init DATA failed for CMD ${cmd.toHexString()}: ${data.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                            throw IOException("初期化データ失敗: ${data.toHexString()}")
                        }
                    }
                    if (cmd.contentEquals(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20))) {
                        SystemClock.sleep(100)
                    }
                }
                Log.i(TAG_NFL, "Initialization sequence complete.")
                runOnUiThread { mProgressBar?.progress = 15 }

                val (picSend, _) = Utils.convertBitmapToEpdData(
                    bitmapToFlash,
                    EPD_LAYOUT_WIDTH,
                    EPD_LAYOUT_HEIGHT,
                    rotate270 = true,
                    invertPackedBits = true
                )
                val expectedPicSendSize = EPD_LAYOUT_WIDTH * EPD_LAYOUT_HEIGHT / 8
                Log.i(TAG_NFL, "Image prepared by Utils.convertBitmapToEpdData. picSend size: ${picSend.size} bytes. Expected: $expectedPicSendSize bytes.")
                if (picSend.size != expectedPicSendSize) {
                    Log.w(TAG_NFL, "Warning: picSend size (${picSend.size}) does not match expected size ($expectedPicSendSize).")
                }
                runOnUiThread { mProgressBar?.progress = 20 }

                val dtm1Cmd = byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x24)
                var response = isoDep?.transceive(dtm1Cmd)
                if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                    Log.e(TAG_NFL, "DTM1 CMD (0x24) failed: ${dtm1Cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                    throw IOException("データ送信開始コマンド(0x24)失敗")
                }
                Log.i(TAG_NFL, "DTM1 (0x24) sent.")
                runOnUiThread { mProgressBar?.progress = 22 }

                val dataToSend = picSend
                Log.i(TAG_NFL, "Sending raw data. Size: ${dataToSend.size}")

                val sendDataCmdHeaderWithP3 = byteArrayOf(
                    0x74.toByte(), 0x9E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                val maxChunkSize = 1016
                var sentBytes = 0
                while (sentBytes < dataToSend.size) {
                    val remainingBytes = dataToSend.size - sentBytes
                    val chunkSize = remainingBytes.coerceAtMost(maxChunkSize)
                    val chunk = dataToSend.copyOfRange(sentBytes, sentBytes + chunkSize)

                    val fullCmd = ByteArray(sendDataCmdHeaderWithP3.size + 2 + chunk.size)
                    System.arraycopy(sendDataCmdHeaderWithP3, 0, fullCmd, 0, sendDataCmdHeaderWithP3.size)
                    fullCmd[sendDataCmdHeaderWithP3.size] = (chunkSize shr 8 and 0xFF).toByte()
                    fullCmd[sendDataCmdHeaderWithP3.size + 1] = (chunkSize and 0xFF).toByte()
                    System.arraycopy(chunk, 0, fullCmd, sendDataCmdHeaderWithP3.size + 2, chunk.size)

                    response = isoDep?.transceive(fullCmd)
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e(TAG_NFL, "Send Data chunk failed. Offset: $sentBytes. Cmd (${fullCmd.size}B): ${fullCmd.toHexString(fullCmd.size)}. Response (${response?.size ?: 0}B): ${response?.toHexString() ?: "null"}")
                        throw IOException("データ送信失敗 (オフセット: $sentBytes)")
                    }
                    sentBytes += chunkSize
                    val progressPercentage = 22 + (sentBytes * 68 / dataToSend.size).coerceIn(0,68)
                    runOnUiThread { mProgressBar?.progress = progressPercentage }
                    Log.d(TAG_NFL, "Sent $chunkSize bytes. Total sent: $sentBytes / ${dataToSend.size}. Progress: $progressPercentage%")
                }
                Log.i(TAG_NFL, "All image data sent.")
                runOnUiThread { mProgressBar?.progress = 90 }

                val refreshCmds = listOf(
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x22), byteArrayOf(0x74, 0x9a.toByte(), 0x00, 0x0E.toByte(), 0x01, 0xC7.toByte())),
                    Pair(byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D.toByte(), 0x01, 0x20), null)
                )
                for ((cmd, data) in refreshCmds) {
                    response = isoDep?.transceive(cmd)
                    if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                        Log.e(TAG_NFL, "Refresh CMD failed: ${cmd.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                        throw IOException("リフレッシュコマンド失敗: ${cmd.toHexString()}")
                    }
                    if (data != null) {
                        response = isoDep?.transceive(data)
                        if (response == null || response.size < 2 || response[0] != 0x90.toByte() || response[1] != 0x00.toByte()) {
                            Log.e(TAG_NFL, "Refresh DATA failed: ${data.toHexString()}. Response: ${response?.toHexString() ?: "null"}")
                            throw IOException("リフレッシュデータ失敗: ${data.toHexString()}")
                        }
                    }
                }
                Log.i(TAG_NFL, "Refresh sequence sent.")
                runOnUiThread { mProgressBar?.progress = 95 }

                SystemClock.sleep(1000)
                val busyCmd = byteArrayOf(0x74, 0x9B.toByte(), 0x00, 0x0F.toByte(), 0x01)
                var busyRetries = 0
                val maxBusyRetries = 100
                while (busyRetries < maxBusyRetries) {
                    response = isoDep?.transceive(busyCmd)
                    if (response != null && response.isNotEmpty() && response[0] == 0x00.toByte()) {
                        Log.i(TAG_NFL, "Device no longer busy. Response: ${response.toHexString()}")
                        break
                    }
                    Log.d(TAG_NFL, "Still busy or unexpected response: ${response?.toHexString() ?: "null"}. Retry: ${busyRetries + 1}/$maxBusyRetries")
                    busyRetries++
                    if (busyRetries >= maxBusyRetries) {
                        Log.w(TAG_NFL, "Busy wait timeout after $maxBusyRetries retries.")
                    }
                    SystemClock.sleep(250)
                }
                success = true
                runOnUiThread { mProgressBar?.progress = 100 }
            }
        } catch (e: IOException) {
            Log.e(TAG_NFL, "IOException: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー(I): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e(TAG_NFL, "Generic exception: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー(I): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            try {
                isoDep?.close()
                Log.i(TAG_NFL, "IsoDep connection closed.")
            } catch (e: IOException) {
                Log.e(TAG_NFL, "Error closing IsoDep: ${e.message}", e)
            }
            this.mIsFlashing = false
            runOnUiThread {
                val toastMsg = if (success) "書き込み成功！ (IsoDep)" else "書き込み失敗 (IsoDep)"
                Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                if (success) {
                    Log.i(TAG_NFL, "Flashing successful via IsoDep.")
                } else {
                    Log.e(TAG_NFL, "Flashing failed via IsoDep.")
                }
            }
            Log.i(TAG_NFL, "IsoDep Flashing process finished (in finally block).")
        }
    }

    private suspend fun flashBitmapWithNfcA(tag: Tag, bitmapToFlash: Bitmap, screenSizeEnumForWaveShare: Int) {
        if (mIsFlashing) {
            Log.w(TAG_NFL, "Attempted to start flash while already flashing. Aborting.")
            return
        }
        this.mIsFlashing = true
        val waveShareHandler = WaveShareHandler(this@NfcFlasher)
        var progressAnimatorJob: Job? = null

        try {
            withContext(Dispatchers.IO) {
                val nfcConnection = NfcA.get(tag)
                if (nfcConnection == null) {
                    Log.e(TAG_NFL, "NfcA technology not available for this tag.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "NFCエラー: NfcA非対応のタグです。", Toast.LENGTH_LONG).show()
                    }
                    this@NfcFlasher.mIsFlashing = false
                    return@withContext
                }

                val passwordForOldSdk = "1234".toByteArray(StandardCharsets.US_ASCII)
                Log.i(TAG_NFL, "Old firmware (NfcA). Using password '1234' for WaveShareHandler.sendBitmap.")

                withContext(Dispatchers.Main) {
                    mProgressBar?.progress = 10
                }

                progressAnimatorJob = CoroutineScope(Dispatchers.Main + Job()).launch {
                    var currentSimulatedProgress = 10
                    val estimatedDurationMs = 15000L
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
                    bitmapToFlash,
                    passwordForOldSdk
                )

                progressAnimatorJob?.cancelAndJoin()

                withContext(Dispatchers.Main) {
                    mProgressBar?.progress = if (result.success) 100 else (mProgressBar?.progress ?: 50)
                    val toastMsg = if (result.success) "書き込み成功！ (NfcA)" else "書き込み失敗 (NfcA): ${result.errMessage}"
                    Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                    if (result.success) {
                        Log.i(TAG_NFL, "WaveShareHandler.sendBitmap successful.")
                    } else {
                        Log.e(TAG_NFL, "WaveShareHandler.sendBitmap failed: ${result.errMessage}")
                    }
                }
            }
        } catch (e: IOException) {
            progressAnimatorJob?.cancel()
            Log.e(TAG_NFL, "IOException during NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "NFC通信エラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            progressAnimatorJob?.cancel()
            Log.e(TAG_NFL, "Generic exception in NfcA flashBitmap: ${e.message}", e)
            runOnUiThread { Toast.makeText(applicationContext, "予期せぬエラー(A): ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        } finally {
            progressAnimatorJob?.cancel()
            this.mIsFlashing = false
            Log.i(TAG_NFL, "NfcA Flashing process finished (in finally block).")
        }
    }

    private fun enableForegroundDispatch() {
        Log.d(TAG_NFL, "Attempting to enable foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true) {
            try {
                val techListsArray = arrayOf(
                    arrayOf(NfcA::class.java.name),
                    arrayOf(IsoDep::class.java.name)
                )
                mNfcAdapter?.enableForegroundDispatch(this, this.mPendingIntent, this.mNfcIntentFilters, techListsArray)
                Log.i(TAG_NFL, "Foreground dispatch enabled for NfcA and IsoDep.")
            } catch (ex: IllegalStateException) {
                Log.e(TAG_NFL, "Error enabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w(TAG_NFL, "NFC is disabled, cannot enable foreground dispatch.")
        }
    }

    private fun disableForegroundDispatch() {
        Log.d(TAG_NFL, "Attempting to disable foreground dispatch.")
        if (mNfcAdapter?.isEnabled == true && !isFinishing && !isDestroyed) {
            try {
                mNfcAdapter?.disableForegroundDispatch(this)
                Log.i(TAG_NFL, "Foreground dispatch disabled.")
            } catch (ex: IllegalStateException) {
                Log.e(TAG_NFL, "Error disabling foreground dispatch: ${ex.message}", ex)
            }
        } else {
            Log.w(TAG_NFL, "NFC adapter not available/disabled or activity finishing, cannot disable dispatch.")
        }
    }

    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null && mNfcAdapter?.isEnabled == true) {
            Log.v(TAG_NFL, "NFC Check Loop STARTED")
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(mNfcCheckCallback, mNfcCheckIntervalMs)
        } else if (mNfcCheckHandler != null) {
            Log.v(TAG_NFL, "NFC Check Loop already running.")
        } else {
            Log.w(TAG_NFL, "NFC adapter disabled, not starting check loop.")
        }
    }

    private fun stopNfcCheckLoop() {
        if (mNfcCheckHandler != null) {
            Log.v(TAG_NFL, "NFC Check Loop STOPPED")
            mNfcCheckHandler?.removeCallbacks(mNfcCheckCallback)
            mNfcCheckHandler = null
        }
    }

    private fun checkNfcAndAttemptRecover() {
        if (mNfcAdapter == null) {
            Log.e(TAG_NFL, "NFC Adapter is null! Re-initializing.")
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (mNfcAdapter == null) {
                Log.e(TAG_NFL, "Failed to re-initialize NFC Adapter. NFC might not be supported.")
                Toast.makeText(this, "NFCが利用できません。", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (mNfcAdapter?.isEnabled == false) {
            Log.w(TAG_NFL, "NFC is currently disabled by user.")
        }
    }

    private fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val file = File(context.filesDir, fileName)
        var success = false
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                success = true
                Log.d(TAG_NFL, "Bitmap saved to: ${file.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e(TAG_NFL, "Error saving bitmap to $fileName", e)
        }
        return success
    }

    private fun loadBitmapFromInternalStorage(context: Context, fileName: String): Bitmap? {
        val file = File(context.filesDir, fileName)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG_NFL, "Error loading bitmap from $fileName", e)
                null
            }
        } else {
            Log.d(TAG_NFL, "Bitmap file not found: $fileName")
            null
        }
    }

    private fun getBitmapFromImageView(imageView: ImageView): Bitmap? {
        return (imageView.drawable as? BitmapDrawable)?.bitmap
    }
}

internal fun ByteArray.toHexString(length: Int = this.size): String {
    val effectiveLength = length.coerceAtMost(this.size)
    return this.take(effectiveLength).joinToString(separator = " ") { "%02X".format(it) }
}
