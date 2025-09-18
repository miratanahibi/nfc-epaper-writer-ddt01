package com.joshuatz.nfceinkwriter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView // TextView は現在コメントアウトされている部分でのみ使用
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageView

class MainActivity : AppCompatActivity() {
    private var mPreferencesController: Preferences? = null
    private var mHasReFlashableImage: Boolean = false
    private val mReFlashButton: CardView get() = findViewById(R.id.reflashButton)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register action bar / toolbar
        setSupportActionBar(findViewById(R.id.main_toolbar))

        // Get user preferences
        this.mPreferencesController = Preferences(this)
        //this.updateScreenSizeDisplay(null)

        // Setup screen size changer
        /*
        val screenSizeChangeInvite: Button = findViewById(R.id.changeDisplaySizeInvite)
        screenSizeChangeInvite.setOnClickListener {
            this.mPreferencesController?.showScreenSizePicker(fun(updated: String): Void? {
                this.updateScreenSizeDisplay(updated)
                return null
            })
        }
        */

        // Check for previously generated image, enable re-flash button if available
        checkReFlashAbility()

        mReFlashButton.setOnClickListener {
            if (mHasReFlashableImage) {
                val navIntent = Intent(this, NfcFlasher::class.java)
                startActivity(navIntent)
            } else {
                val toast = Toast.makeText(this, "There is no image to re-flash!", Toast.LENGTH_SHORT)
                toast.show()
            }
        }


        // Setup image file picker
        val imageFilePickerCTA: Button = findViewById(R.id.cta_pick_image_file)
        imageFilePickerCTA.setOnClickListener {
            val screenSizePixels = this.mPreferencesController?.getScreenSizePixels()!!

            CropImage
                .activity()
                .setGuidelines(CropImageView.Guidelines.ON)
                .setAspectRatio(screenSizePixels.first, screenSizePixels.second)
                .setRequestedSize(screenSizePixels.first, screenSizePixels.second, CropImageView.RequestSizeOptions.RESIZE_EXACT)
                .start(this)
        }

        /*
        // Setup WYSIWYG button click
        val wysiwygEditButtonInvite: Button = findViewById(R.id.cta_new_graphic)
        wysiwygEditButtonInvite.setOnClickListener {
            val intent = Intent(this, WysiwygEditor::class.java)
            startActivity(intent)
        }
        */

        // Setup text button click
        val textEditButtonInvite: Button = findViewById(R.id.cta_new_text)
        textEditButtonInvite.setOnClickListener {
            val intent = Intent(this, TextEditor::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        checkReFlashAbility()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            val result = CropImage.getActivityResult(resultData)
            if (resultCode == Activity.RESULT_OK) {
                val croppedBitmap = result?.getBitmap(this) // `var` から `val` に変更 (再代入がないため)
                if (croppedBitmap != null) {
                    // Resizing should have already been taken care of by setRequestedSize
                    // Save
                    openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutStream) // croppedBitmapが非nullであることをスマートキャストで利用
                        // fileOutStream.close() // .use ブロックが自動的にクローズします
                        // Navigate to flasher
                        val navIntent = Intent(this, NfcFlasher::class.java)
                        startActivity(navIntent)
                    }
                } else {
                    Log.e("Crop image callback", "Crop image result not available")
                }
            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                val error = result?.error // resultがnullの場合を考慮して ?. を使用
                Log.e("Crop image callback", "Crop image error: ${error?.message}", error) // エラーログを少し詳細に
            }
        }
    }

    /*
    private fun updateScreenSizeDisplay(updated: String?) {
        var screenSizeStr = updated
        if (screenSizeStr == null) {
            screenSizeStr = this.mPreferencesController?.getPreferences()
                ?.getString(Constants.PreferenceKeys.DisplaySize, DefaultScreenSize)
        }
        findViewById<TextView>(R.id.currentDisplaySize).text = screenSizeStr ?: DefaultScreenSize
    }
    */
    @Suppress("DEPRECATION") // getDrawable(Int, Theme) の代替が API 21+ のため、minSdkによっては抑制が必要な場合がある
    private fun checkReFlashAbility() {
        val lastGeneratedFile = getFileStreamPath(GeneratedImageFilename)
        val reFlashImagePreview: ImageView = findViewById(R.id.reflashButtonImage)
        if (lastGeneratedFile.exists()) {
            mHasReFlashableImage = true
            // Need to set null first, or else Android will cache previous image
            reFlashImagePreview.setImageURI(null)
            reFlashImagePreview.setImageURI(Uri.fromFile((lastGeneratedFile)))
        } else {
            mHasReFlashableImage = false // 状態を明確に設定
            // Grey out button
            mReFlashButton.setCardBackgroundColor(Color.DKGRAY)

            // APIレベルによる分岐 (getDrawable)
            /*val warningDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                resources.getDrawable(android.R.drawable.stat_sys_warning, null)
            } else {
                resources.getDrawable(android.R.drawable.stat_sys_warning)
            }
            reFlashImagePreview.setImageDrawable(warningDrawable)*/
        }
    }

}
