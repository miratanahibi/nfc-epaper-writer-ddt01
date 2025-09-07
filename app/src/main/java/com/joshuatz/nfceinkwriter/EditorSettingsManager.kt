package com.joshuatz.nfceinkwriter

import android.content.Context
import android.content.SharedPreferences

class EditorSettingsManager(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SELECTED_FONT_DISPLAY_NAME = "selected_font_display_name"
        const val KEY_SELECTED_FONT_CSS_FAMILY = "selected_font_css_family"

        const val DEFAULT_FONT_DISPLAY_NAME = "Arial"
        const val DEFAULT_FONT_CSS_FAMILY = "Arial" // ArialのCSS FamilyもArial
    }

    fun saveSelectedFont(displayName: String, cssFamily: String) {
        preferences.edit()
            .putString(KEY_SELECTED_FONT_DISPLAY_NAME, displayName)
            .putString(KEY_SELECTED_FONT_CSS_FAMILY, cssFamily)
            .apply()
    }

    fun getSelectedFontDisplayName(): String {
        return preferences.getString(KEY_SELECTED_FONT_DISPLAY_NAME, DEFAULT_FONT_DISPLAY_NAME) ?: DEFAULT_FONT_DISPLAY_NAME
    }

    fun getSelectedFontCssFamily(): String {
        return preferences.getString(KEY_SELECTED_FONT_CSS_FAMILY, DEFAULT_FONT_CSS_FAMILY) ?: DEFAULT_FONT_CSS_FAMILY
    }
}
