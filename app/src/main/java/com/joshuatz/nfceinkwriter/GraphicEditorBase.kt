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
import android.widget.ImageView // ImageView をインポート
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


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

abstract class GraphicEditorBase : AppCompatActivity() {
    @get:LayoutRes
    abstract val layoutId: Int

    @get:IdRes
    abstract val flashButtonId: Int

    @get:IdRes
    abstract val webViewId: Int

    // ★ 具象クラスでプレビュー用ImageViewのIDを指定させるための抽象プロパティ
    @get:IdRes
    abstract val previewImageViewId: Int

    abstract val webViewUrl: String
    protected lateinit var mWebView: WebView // lateinit に変更 (onCreateで初期化)
    private var imageContinuation: Continuation<ByteArray>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(this.layoutId)

        mWebView = findViewById(this.webViewId) // mWebView の初期化

        // Setup asset loader to handle local asset paths
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Override WebView client
        mWebView.webViewClient = object : WebViewClient() {
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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onWebViewPageFinished() // これが呼ばれた後に初期プレビュー更新を行う
            }
        }

        // WebView - Enable JS
        mWebView.settings.javaScriptEnabled = true
        WebView.setWebContentsDebuggingEnabled(true) // デバッグ用に有効化

        mWebView.addJavascriptInterface(
            WebAppInterface { imageBytes ->
                imageContinuation?.resume(imageBytes)
                imageContinuation = null
            },
            "AndroidInterface"
        )

        mWebView.webChromeClient = WebChromeClient()
        mWebView.loadUrl(this.webViewUrl)

        val flashButton: Button = findViewById(this.flashButtonId)
        flashButton.setOnClickListener {
            lifecycleScope.launch {
                getAndFlashGraphic()
            }
        }
    }

    private suspend fun getAndFlashGraphic() {
        val mContext = this
        val imageBytes = this.getBitmapFromWebView(this.mWebView) // mWebView を使用

        if (imageBytes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                    fileOutStream.write(imageBytes)
                }
                val navIntent = Intent(mContext, NfcFlasher::class.java)
                val bundle = Bundle()
                bundle.putString(IntentKeys.GeneratedImgPath, GeneratedImageFilename)
                navIntent.putExtras(bundle)
                startActivity(navIntent)
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(mContext, "Failed to get image from editor", Toast.LENGTH_SHORT).show()
            }
            Log.e("GraphicEditorBase", "getAndFlashGraphic: imageBytes is empty.")
        }
    }

    protected fun updateCanvasSize() {
        val preferences = Preferences(this)
        val pixelSize = ScreenSizesInPixels[preferences.getScreenSize()]
        mWebView.evaluateJavascript("setDisplaySize(${pixelSize!!.first}, ${pixelSize.second});", null)
    }

    // ★ プレビュー更新用メソッド ★
    protected fun refreshWebViewPreviewBitmap() {
        if (!::mWebView.isInitialized) {
            Log.w("GraphicEditorBase", "WebView not initialized, cannot refresh preview.")
            return
        }
        Log.d("GraphicEditorBase", "refreshWebViewPreviewBitmap called.")
        lifecycleScope.launch {
            try {
                val imageBytes = getBitmapFromWebView(mWebView)
                if (imageBytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val previewImageView = findViewById<ImageView>(previewImageViewId)
                    previewImageView?.setImageBitmap(bitmap)
                    Log.d("GraphicEditorBase", "Preview ImageView updated successfully.")
                } else {
                    Log.w("GraphicEditorBase", "refreshWebViewPreviewBitmap: Got empty imageBytes.")
                }
            } catch (e: Exception) {
                Log.e("GraphicEditorBase", "Error refreshing preview bitmap", e)
            }
        }
    }

    // ★★★ mWebView を安全に取得するためのヘルパーメソッドを追加 ★★★
    protected fun getWebViewInstance(): WebView? {
        if (::mWebView.isInitialized) {
            return mWebView
        }
        Log.w("GraphicEditorBase", "getWebViewInstance: mWebView is not initialized.")
        return null
    }
    // ★★★ 追加ここまで ★★★

    open fun onWebViewPageFinished() {
        // ページロード完了後に初期プレビューを更新
        Log.d("GraphicEditorBase", "onWebViewPageFinished: Page loaded, refreshing initial preview.")
        refreshWebViewPreviewBitmap()
    }

    open fun onWebViewPageStarted() {
        // Available to subclass
    }

    // getBitmapFromWebView は mWebView を引数に取るように変更、またはメンバ変数 mWebView を直接使用
    open suspend fun getBitmapFromWebView(webView: WebView): ByteArray { // 引数 webView は維持
        return suspendCoroutine { continuation ->
            imageContinuation = continuation
            val script = """
                if (typeof getImgSerializedFromCanvas === 'function') {
                    getImgSerializedFromCanvas(undefined, undefined, function(base64) {
                        if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') {
                            window.AndroidInterface.processImageData(base64);
                        } else {
                            console.error('AndroidInterface.processImageData is not available.');
                            if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') { // エラーの場合もnullを通知
                                window.AndroidInterface.processImageData(null);
                            }
                        }
                    });
                } else {
                    console.error('getImgSerializedFromCanvas is not defined.');
                     if (window.AndroidInterface && typeof window.AndroidInterface.processImageData === 'function') { // エラーの場合もnullを通知
                        window.AndroidInterface.processImageData(null);
                    }
                }
            """.trimIndent()
            webView.evaluateJavascript(script, null) // 引数のwebViewを使用
        }
    }
}
