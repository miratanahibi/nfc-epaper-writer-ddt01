package com.joshuatz.nfceinkwriter

import android.os.Bundle
import android.os.Handler // 既にimportされていました
import android.os.Looper  // 既にimportされていました
import android.util.Log   // 既にimportされていました
import android.widget.Button
import com.google.android.material.dialog.MaterialAlertDialogBuilder

data class FontItem(
    val displayName: String, // Spinnerに表示する名前
    val cssFontFamily: String, // CSSのfont-family名 (ウェブアプリに渡す識別子)
    val assetFileName: String? = null // アセット内のファイル名 (ローカルフォントの場合)
)

class TextEditor : GraphicEditorBase() {
    // GraphicEditorBase から継承するプロパティのオーバーライド
    override val layoutId = R.layout.activity_text_editor
    override val flashButtonId = R.id.flashTextButton
    override val webViewId = R.id.textEditWebView

    // ★ プレビュー用ImageViewのIDを指定 (あなたのレイアウトファイルに合わせてください)
    // GraphicEditorBase で abstract val previewImageViewId: Int を宣言している前提
    override val previewImageViewId = R.id.previewImageView // 仮のIDです。必ず実際のIDに置き換えてください。

    // ★ WebViewのURLをローカルアセットに変更
    override val webViewUrl = "file:///android_asset/editors/text/index.html"

    private lateinit var settingsManager: EditorSettingsManager
    private lateinit var fontSelectButton: Button

    private val availableFonts = listOf(
        FontItem("Arial", "Arial"),
        FontItem("JK丸ゴシック M", "JKMaruGothicM", "JK-Maru-Gothic-M.otf"),
        FontItem("けいふぉんと", "KeiFont", "keifont.ttf"),
        FontItem("よすが", "Yosugara", "yosugaraver1_2.ttf")
    )

    private var currentSelectedFontIndex = 0 // ダイアログの初期選択と、選択後の状態保持

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // GraphicEditorBaseのonCreateが呼ばれます

        settingsManager = EditorSettingsManager(this)
        fontSelectButton = findViewById(R.id.fontSelectButton)

        // 初期フォントインデックスとボタンテキストを設定
        val initialFontItem = getCurrentOrDefaultFontItem()
        currentSelectedFontIndex = if (initialFontItem != null) {
            availableFonts.indexOf(initialFontItem).takeIf { it != -1 } ?: 0
        } else {
            0 // フォールバック
        }
        updateFontSelectButtonText(availableFonts.getOrNull(currentSelectedFontIndex)?.displayName ?: "フォント選択")

        fontSelectButton.setOnClickListener {
            showFontSelectionDialog()
        }
    }

    private fun showFontSelectionDialog() {
        val fontDisplayNames = availableFonts.map { it.displayName }.toTypedArray()

        // ダイアログ表示時のチェック状態は currentSelectedFontIndex を使う
        var temporarilySelectedWhich = currentSelectedFontIndex // ダイアログ内で一時的に選択されるインデックス

        MaterialAlertDialogBuilder(this)
            .setTitle("フォントを選択")
            .setSingleChoiceItems(fontDisplayNames, currentSelectedFontIndex) { _, which ->
                temporarilySelectedWhich = which // ユーザーが選択したインデックスを一時的に保持
            }
            .setPositiveButton("OK") { dialog, _ ->
                currentSelectedFontIndex = temporarilySelectedWhich // OKが押されたら正式な選択インデックスとして採用

                if (currentSelectedFontIndex >= 0 && currentSelectedFontIndex < availableFonts.size) {
                    val selectedFontItem = availableFonts[currentSelectedFontIndex]
                    settingsManager.saveSelectedFont(selectedFontItem.displayName, selectedFontItem.cssFontFamily)

                    Log.d("TextEditor", "Applying font to WebView: ${selectedFontItem.cssFontFamily}")
                    applyFontToWebView(selectedFontItem.cssFontFamily) // WebViewにフォントを適用

                    updateFontSelectButtonText(selectedFontItem.displayName) // ボタンテキストを更新
                    Log.d("TextEditor", "Font selected: ${selectedFontItem.displayName}. Triggering preview update immediately.")

                    // WebViewへのフォント適用後、すぐにAndroid側のプレビューも更新する。
                    // JavaScript側でのフォントロードと再描画がある程度完了していることを期待。
                    try {
                        this.refreshWebViewPreviewBitmap() // GraphicEditorBase のメソッドを呼び出し
                        Log.d("TextEditor", "Called refreshWebViewPreviewBitmap immediately after font selection.")
                    } catch (e: Exception) {
                        Log.e("TextEditor", "Error calling refreshWebViewPreviewBitmap immediately", e)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }


    override fun onWebViewPageFinished() {
        // GraphicEditorBaseのonWebViewPageFinished内で初期プレビュー更新が呼ばれるので、
        // super.onWebViewPageFinished() を適切なタイミングで呼ぶ
        super.onWebViewPageFinished() // これにより GraphicEditorBase の refreshWebViewPreviewBitmap が呼ばれる

        // TextEditor固有の初期化処理
        this.updateCanvasSize()

        // 初期フォントをWebViewに適用し、UIを同期する
        val fontToApply = getCurrentOrDefaultFontItem()
        if (fontToApply != null) {
            applyFontToWebView(fontToApply.cssFontFamily)
            currentSelectedFontIndex = availableFonts.indexOf(fontToApply).takeIf { it != -1 } ?: 0
            updateFontSelectButtonText(fontToApply.displayName)
        } else {
            // フォールバック
            val defaultCss = EditorSettingsManager.DEFAULT_FONT_CSS_FAMILY
            applyFontToWebView(defaultCss)
            currentSelectedFontIndex = availableFonts.indexOfFirst { it.cssFontFamily == defaultCss }.takeIf { it != -1 } ?: 0
            updateFontSelectButtonText(availableFonts.getOrNull(currentSelectedFontIndex)?.displayName ?: "フォント選択")
        }
        // ここでの追加のプレビュー更新呼び出しは、super.onWebViewPageFinished() 内で行われるため不要
    }

    private fun applyFontToWebView(fontCssFamily: String) {
        // GraphicEditorBaseのヘルパーメソッドを使ってWebViewインスタンスを取得
        val webViewInstance = getWebViewInstance() // 親クラスのメソッドを呼び出し

        if (webViewInstance != null) {
            // 取得したWebViewインスタンスに対して処理を行う
            webViewInstance.evaluateJavascript("if(typeof setFontFamily === 'function') { setFontFamily('$fontCssFamily'); } else { console.error('setFontFamily function not found in WebView'); }", null)
            Log.d("TextEditor", "Applied font to WebView via JS: $fontCssFamily")
        } else {
            Log.w("TextEditor", "WebView not initialized or available when trying to apply font: $fontCssFamily")
        }
    }


    private fun getCurrentOrDefaultFontItem(): FontItem? {
        val savedCssFamily = settingsManager.getSelectedFontCssFamily()
        return availableFonts.find { it.cssFontFamily == savedCssFamily }
            ?: availableFonts.find { it.cssFontFamily == EditorSettingsManager.DEFAULT_FONT_CSS_FAMILY }
            ?: availableFonts.firstOrNull()
    }

    private fun updateFontSelectButtonText(fontName: String) {
        fontSelectButton.text = fontName
    }
}
