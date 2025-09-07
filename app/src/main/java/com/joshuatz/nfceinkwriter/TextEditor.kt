package com.joshuatz.nfceinkwriter

import android.os.Bundle
import android.widget.Button
// import androidx.appcompat.app.AlertDialog // MaterialAlertDialogBuilderを使用するため不要な場合
import com.google.android.material.dialog.MaterialAlertDialogBuilder

data class FontItem(
    val displayName: String, // Spinnerに表示する名前
    val cssFontFamily: String, // CSSのfont-family名 (ウェブアプリに渡す識別子)
    val assetFileName: String? = null // アセット内のファイル名 (ローカルフォントの場合)
)

class TextEditor : GraphicEditorBase() {
    override val layoutId = R.layout.activity_text_editor
    override val flashButtonId = R.id.flashTextButton
    override val webViewId = R.id.textEditWebView
    override val webViewUrl = "https://appassets.androidplatform.net/assets/editors/text/index.html"

    private lateinit var settingsManager: EditorSettingsManager
    private lateinit var fontSelectButton: Button

    private val availableFonts = listOf(
        FontItem("Arial", "Arial"),
        FontItem("JK丸ゴシック M", "JKMaruGothicM", "JK-Maru-Gothic-M.otf"),
        FontItem("けいふぉんと", "KeiFont", "keifont.ttf"),
        FontItem("よすが", "Yosugara", "yosugaraver1_2.ttf")
    )

    private var currentSelectedFontIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = EditorSettingsManager(this)
        fontSelectButton = findViewById(R.id.fontSelectButton)

        // 初期フォントインデックスとボタンテキストを設定
        val initialFontItem = getCurrentOrDefaultFontItem()
        if (initialFontItem != null) {
            currentSelectedFontIndex = availableFonts.indexOf(initialFontItem)
            updateFontSelectButtonText(initialFontItem.displayName)
        } else {
            // フォールバック (availableFontsが空など、非常に稀なケース)
            currentSelectedFontIndex = 0
            updateFontSelectButtonText("フォント選択") // デフォルトに戻す
        }

        fontSelectButton.setOnClickListener {
            showFontSelectionDialog()
        }
    }

    private fun showFontSelectionDialog() {
        val fontDisplayNames = availableFonts.map { it.displayName }.toTypedArray()

        // ダイアログ表示時に現在選択されているフォントを事前にチェック状態にする
        // getCurrentOrDefaultFontItem を使って、現在有効なフォントのインデックスを取得
        val currentFont = getCurrentOrDefaultFontItem()
        val checkedItem = if (currentFont != null) availableFonts.indexOf(currentFont) else 0

        MaterialAlertDialogBuilder(this)
            .setTitle("フォントを選択")
            .setSingleChoiceItems(fontDisplayNames, checkedItem) { dialog, which ->
                currentSelectedFontIndex = which // ユーザーが選択したインデックスを一時的に保持
            }
            .setPositiveButton("OK") { dialog, which ->
                if (currentSelectedFontIndex >= 0 && currentSelectedFontIndex < availableFonts.size) {
                    val selectedFontItem = availableFonts[currentSelectedFontIndex]
                    settingsManager.saveSelectedFont(selectedFontItem.displayName, selectedFontItem.cssFontFamily)
                    applyFontToWebView(selectedFontItem.cssFontFamily)
                    updateFontSelectButtonText(selectedFontItem.displayName) // ボタンテキストを更新
                }
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onWebViewPageFinished() {
        super.onWebViewPageFinished()
        this.updateCanvasSize()

        val fontToApply = getCurrentOrDefaultFontItem()

        if (fontToApply != null) {
            applyFontToWebView(fontToApply.cssFontFamily)
            currentSelectedFontIndex = availableFonts.indexOf(fontToApply)
            updateFontSelectButtonText(fontToApply.displayName) // WebView初期化後にもボタンテキストを同期
        } else {
            // フォールバック (availableFontsが空など)
            val defaultCss = EditorSettingsManager.DEFAULT_FONT_CSS_FAMILY
            applyFontToWebView(defaultCss)
            currentSelectedFontIndex = availableFonts.indexOfFirst { it.cssFontFamily == defaultCss }.takeIf { it != -1 } ?: 0
            updateFontSelectButtonText(availableFonts.getOrNull(currentSelectedFontIndex)?.displayName ?: "フォント選択")
        }
    }

    private fun applyFontToWebView(fontCssFamily: String) {
        mWebView?.evaluateJavascript("if(typeof setFontFamily === 'function') { setFontFamily('$fontCssFamily'); } else { console.error('setFontFamily function not found in WebView'); }", null)
    }

    /**
     * 現在選択されているフォント、またはデフォルトフォント、または利用可能な最初のフォントを取得します。
     */
    private fun getCurrentOrDefaultFontItem(): FontItem? {
        val savedCssFamily = settingsManager.getSelectedFontCssFamily()
        return availableFonts.find { it.cssFontFamily == savedCssFamily }
            ?: availableFonts.find { it.cssFontFamily == EditorSettingsManager.DEFAULT_FONT_CSS_FAMILY }
            ?: availableFonts.firstOrNull()
    }

    /**
     * フォント選択ボタンのテキストを指定されたフォント名に更新します。
     */
    private fun updateFontSelectButtonText(fontName: String) {
        fontSelectButton.text = fontName
    }
}
