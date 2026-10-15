package io.github.lj5201lj.xianzhun

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Offline native pages. Only explicit external-link taps leave the app. */
class AppInfoDialogs(private val activity: Activity, private val preferences: SharedPreferences) {
    private val version: String get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    fun showAbout() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        body.addView(text(activity.getString(R.string.about_description, version)))
        body.addView(CheckBox(activity).apply {
            id = R.id.haptics_toggle
            setText(R.string.in_tune_haptics)
            isChecked = preferences.getBoolean("in_tune_haptics", true)
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("in_tune_haptics", checked).apply() }
        })
        fun action(label: Int, idValue: Int, click: () -> Unit) {
            body.addView(Button(activity).apply {
                id = idValue
                setText(label)
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        action(R.string.privacy_policy, R.id.privacy_button) { showPrivacy() }
        action(R.string.sample_licenses, R.id.licenses_button) {
            showText(R.string.sample_licenses, raw(R.raw.guitar_samples_attribution))
        }
        action(R.string.help_title, R.id.help_button) { showText(R.string.help_title, activity.getString(R.string.tuning_help)) }
        action(R.string.feedback, R.id.feedback_button) { showFeedback() }
        AlertDialog.Builder(activity).setTitle(R.string.about_and_privacy)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.got_it, null).show()
    }

    fun showPrivacy() = showText(R.string.privacy_policy, raw(R.raw.privacy_policy), policyLink = true)

    private fun showText(title: Int, content: String, policyLink: Boolean = false) {
        val body = text(content).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val dialog = AlertDialog.Builder(activity).setTitle(title)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.got_it, null)
        if (policyLink) dialog.setNeutralButton(R.string.public_privacy_policy) { _, _ ->
            openExternal("https://lj5201lj.github.io/guitar-tuner-pwa/privacy.html")
        }
        dialog.show()
    }

    private fun showFeedback() {
        AlertDialog.Builder(activity).setTitle(R.string.feedback)
            .setMessage(activity.getString(R.string.feedback_note, version, Build.VERSION.RELEASE))
            .setPositiveButton(R.string.open_feedback) { _, _ ->
                openExternal("https://github.com/lj5201lj/guitar-tuner-pwa/issues")
            }
            .setNeutralButton(R.string.copy_diagnostics) { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Xianzhun", "Xianzhun $version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${activity.getString(R.string.feedback_template)}"))
                Toast.makeText(activity, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openExternal(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(activity, R.string.browser_unavailable, Toast.LENGTH_LONG).show() }
    }

    private fun raw(id: Int) = activity.resources.openRawResource(id).bufferedReader().use { it.readText() }
    private fun text(value: String) = TextView(activity).apply { text = value; textSize = 15f; setPadding(0, dp(6), 0, dp(6)) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
