package com.joshuatz.nfceinkwriter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Button
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.Continuation // suspendCoroutine で使用
// import java.util.concurrent.TimeoutException // タイムアウトを実装する場合
import android.widget.Toast // エラー表示用 (getAndFlashGraphic)

class WebAppInterface(
    private val onImageReady: (ByteArray) -> Unit
) {
    @JavascriptInterface
    fun processImageData(base64Data: String?) {
        if (base64Data != null) {
            try {
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                onImageReady(imageBytes)
            } catch (e: IllegalArgumentException) {
                onImageReady(ByteArray(0)) // デコード失敗時は空の配列
                Log.e("WebAppInterface", "Failed to decode Base64 string", e)
            }
        } else {
            onImageReady(ByteArray(0)) // nullデータ受信時は空の配列
            Log.e("WebAppInterface", "Received null base64Data")
        }
    }
}

abstract class GraphicEditorBase: AppCompatActivity() {
    @get:LayoutRes abstract val layoutId: Int
    @get:IdRes abstract val flashButtonId: Int
    @get:IdRes abstract val webViewId: Int
    abstract val webViewUrl: String
    protected var mWebView: WebView? = null
    private var imageContinuation: Continuation<ByteArray>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(this.layoutId)

        val webView: WebView = findViewById(this.webViewId)
        this.mWebView = webView

        // Setup asset loader to handle local asset paths
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Override WebView client
        webView.webViewClient = object : WebViewClient() {
            // If request is for local file, intercept and serve
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onWebViewPageStarted()
            }

            // Listen for page load finished, before evaluating JS
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onWebViewPageFinished()
            }
        }

        // WebView - Enable JS
        webView.settings.javaScriptEnabled = true

        // --- JavaScript Interface の登録 ---
        webView.addJavascriptInterface(
            WebAppInterface { imageBytes -> // WebAppInterface をインスタンス化
                imageContinuation?.resume(imageBytes)
                imageContinuation = null
            },
            "AndroidInterface" // JavaScript側で呼び出す名前
        )

        // WebView - set Chrome client
        webView.webChromeClient = WebChromeClient()


        webView.loadUrl(this.webViewUrl)

        val flashButton: Button = findViewById(this.flashButtonId)
        flashButton.setOnClickListener {
            lifecycleScope.launch {
                getAndFlashGraphic()
            }
        }
    }

    private suspend fun getAndFlashGraphic() {
        val mContext = this
        val imageBytes = this.getBitmapFromWebView(this.mWebView!!)

        if (imageBytes.isNotEmpty()) { // <<< 追加: imageBytesが空でないかチェック
            // Decode binary to bitmap (この行は実際にbitmapオブジェクトが必要なければ不要)
            // @Suppress("UNUSED_VARIABLE")
            // val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Save bitmap to file
            withContext(Dispatchers.IO) {
                openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                    fileOutStream.write(imageBytes)
                    // fileOutStream.close() // .use が自動でクローズします
                }
                val navIntent = Intent(mContext, NfcFlasher::class.java)
                val bundle = Bundle()
                bundle.putString(IntentKeys.GeneratedImgPath, GeneratedImageFilename)
                navIntent.putExtras(bundle)
                startActivity(navIntent)
            }
        } else { // <<< 追加: imageBytesが空だった場合の処理
            withContext(Dispatchers.Main) { // UIスレッドでToastを表示
                Toast.makeText(mContext, "Failed to get image from editor", Toast.LENGTH_SHORT).show()
            }
            Log.e("GraphicEditorBase", "getAndFlashGraphic: imageBytes is empty.")
        }
    }

    /*
    private suspend fun getAndFlashGraphic() {
        val mContext = this
        val imageBytes = this.getBitmapFromWebView(this.mWebView!!)
        // Decode binary to bitmap
        @Suppress("UNUSED_VARIABLE")
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        // Save bitmap to file
        withContext(Dispatchers.IO) {
            openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                fileOutStream.write(imageBytes)
                fileOutStream.close()
                val navIntent = Intent(mContext, NfcFlasher::class.java)
                val bundle = Bundle()
                bundle.putString(IntentKeys.GeneratedImgPath, GeneratedImageFilename)
                navIntent.putExtras(bundle)
                startActivity(navIntent)
            }
        }
    }*/

    protected fun updateCanvasSize() {
        val preferences = Preferences(this)
        val pixelSize = ScreenSizesInPixels[preferences.getScreenSize()]
        // Pass display size to WebView
        this.mWebView?.evaluateJavascript("setDisplaySize(${pixelSize!!.first}, ${pixelSize.second});", null)
    }

    // Put any JS eval calls here that need the page loaded first
    open fun onWebViewPageFinished() {
        // Available to subclass
    }

    open fun onWebViewPageStarted() {
        // Available to subclass
    }

    open suspend fun getBitmapFromWebView(webView: WebView): ByteArray {
        return suspendCoroutine { continuation ->
            imageContinuation = continuation

            val script = """
                if (typeof getImgSerializedFromCanvas === 'function') {
                    getImgSerializedFromCanvas(undefined, undefined, function(base64) {
                        if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') {
                            window.AndroidInterface.processImageData(base64);
                        } else {
                            console.error('AndroidInterface.processImageData is not available.');
                            // AndroidInterface が見つからず、画像データを渡せない場合
                            // nullを渡してエラーとして処理させる
                            if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') {
                                window.AndroidInterface.processImageData(null);
                            }
                        }
                    });
                } else {
                    console.error('getImgSerializedFromCanvas is not defined.');
                    // getImgSerializedFromCanvas が見つからない場合
                    if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') {
                        window.AndroidInterface.processImageData(null);
                    }
                }
            """.trimIndent()
            webView.evaluateJavascript(script, null)

            // --- オプション: タイムアウト処理の例 ---
            // lifecycleScope.launch {
            //     delay(5000L) // 5秒のタイムアウト
            //     if (imageContinuation == continuation) { // imageContinuationがクリアされていなければタイムアウトと判断
            //         Log.w("GraphicEditorBase", "getBitmapFromWebView timed out")
            //         imageContinuation?.resumeWithException(TimeoutException("Image retrieval timed out"))
            //         imageContinuation = null
            //     }
            // }
            // --- タイムアウト処理ここまで ---
        }
    }

    /*
    open suspend fun getBitmapFromWebView(webView: WebView): ByteArray {
        // Dump bitmap data from Canvas
        // Cheating by using delay + global. @TODO - rewrite to addJavascriptInterface (careful)
        webView.evaluateJavascript(
            "getImgSerializedFromCanvas(undefined, undefined, (output) => window.imgStr = output);",
            null
        )
        delay(1000L)

        return suspendCoroutine<ByteArray> { continuation ->
            webView.evaluateJavascript("window.imgStr;") { bitmapStr ->
                val imageBytes = Base64.decode(bitmapStr, Base64.DEFAULT)
                continuation.resume(imageBytes)
            }
        }
    }*/
}