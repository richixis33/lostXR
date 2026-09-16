package com.samrat.cardboardhands

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.material.color.DynamicColors

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(28))
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "PhoneXR"
        }, LinearLayout.LayoutParams(dp(128), dp(128)).apply { bottomMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "PhoneXR"
            textSize = 34f
        })
        root.addView(TextView(this).apply {
            text = "Версия ${BuildConfig.VERSION_NAME}"
            textSize = 16f
            alpha = .75f
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(26) })

        val credit = "Made with ❤️ by @Beketov_Samrat"
        val handle = credit.indexOf("@Beketov_Samrat")
        root.addView(TextView(this).apply {
            textSize = 17f
            movementMethod = LinkMovementMethod.getInstance()
            text = SpannableString(credit).apply {
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = openTelegram()
                }, handle, credit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(28) })

        root.addView(TextView(this).apply {
            text = "VR на обычном телефоне: OpenXR через Monado, трекинг рук камерой и Joy‑Con вместо контроллеров."
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = .75f
        })
        setContentView(root)
    }

    private fun openTelegram() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Beketov_Samrat")))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
