package com.kidlock.tube

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** شاشة ولي الأمر: تفعيل الخدمة، الرمز، تشغيل وضع الأطفال، وإعدادات الإعلانات. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: KidPrefs
    private lateinit var serviceStatus: TextView
    private lateinit var serviceHint: TextView
    private lateinit var openA11y: MaterialButton
    private lateinit var pinStatus: TextView
    private lateinit var setPin: MaterialButton
    private lateinit var startKids: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = KidPrefs(this)

        serviceStatus = findViewById(R.id.serviceStatus)
        serviceHint = findViewById(R.id.serviceHint)
        openA11y = findViewById(R.id.openA11y)
        pinStatus = findViewById(R.id.pinStatus)
        setPin = findViewById(R.id.setPin)
        startKids = findViewById(R.id.startKids)

        openA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        setPin.setOnClickListener { changePin() }
        startKids.setOnClickListener { if (prefs.locked) stopKidsMode() else startKidsMode() }

        findViewById<MaterialSwitch>(R.id.swSkip).apply {
            isChecked = prefs.skipAds
            setOnCheckedChangeListener { _, checked -> prefs.skipAds = checked }
        }
        findViewById<MaterialSwitch>(R.id.swMute).apply {
            isChecked = prefs.muteAds
            setOnCheckedChangeListener { _, checked -> prefs.muteAds = checked }
        }
        findViewById<MaterialSwitch>(R.id.swShorts).apply {
            isChecked = prefs.blockShorts
            setOnCheckedChangeListener { _, checked -> prefs.blockShorts = checked }
        }
        findViewById<MaterialSwitch>(R.id.swKids).apply {
            isChecked = prefs.allowKids
            setOnCheckedChangeListener { _, checked -> prefs.allowKids = checked }
        }
        findViewById<MaterialButton>(R.id.openPermissions).setOnClickListener { openAppPermissions() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = isServiceEnabled()
        serviceStatus.setText(if (on) R.string.service_on else R.string.service_off)
        serviceHint.visibility = if (on) TextView.GONE else TextView.VISIBLE
        openA11y.visibility = if (on) TextView.GONE else TextView.VISIBLE

        pinStatus.setText(if (prefs.hasPin) R.string.pin_is_set else R.string.pin_not_set)
        setPin.setText(if (prefs.hasPin) R.string.pin_change else R.string.pin_set)
        startKids.setText(if (prefs.locked) R.string.stop_kids else R.string.start_kids)
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, KidService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------------------------------------------------------------- وضع الأطفال

    private fun startKidsMode() {
        when {
            !isServiceEnabled() -> toast(R.string.start_need_service)
            !prefs.hasPin -> {
                toast(R.string.start_need_pin)
                changePin()
            }
            else -> {
                val pkg = listOf(KidService.YOUTUBE, KidService.YOUTUBE_KIDS)
                    .filter { it == KidService.YOUTUBE || prefs.allowKids }
                    .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
                val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (intent == null) {
                    toast(R.string.no_youtube)
                    return
                }
                prefs.lastApp = pkg
                prefs.locked = true
                Toast.makeText(this, R.string.started_hint, Toast.LENGTH_LONG).show()
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }

    /** وصلت لهذه الشاشة والقفل يعمل (مثلًا الخدمة متوقفة): الإيقاف يحتاج الرمز. */
    private fun stopKidsMode() {
        askPin(R.string.pin_enter_current) { pin ->
            if (prefs.checkPin(pin)) {
                prefs.locked = false
                toast(R.string.unlocked)
                refresh()
            } else {
                toast(R.string.pin_wrong_short)
            }
        }
    }

    // ---------------------------------------------------------------- الرمز

    private fun changePin() {
        if (prefs.hasPin) {
            askPin(R.string.pin_enter_current) { current ->
                if (prefs.checkPin(current)) askNewPin() else toast(R.string.pin_wrong_short)
            }
        } else {
            askNewPin()
        }
    }

    private fun askNewPin() {
        askPin(R.string.pin_enter_new) { first ->
            if (first.length != KidPrefs.PIN_LENGTH) {
                toast(R.string.pin_too_short)
                return@askPin
            }
            askPin(R.string.pin_confirm) { second ->
                if (first == second) {
                    prefs.setPin(first)
                    toast(R.string.pin_saved)
                    refresh()
                } else {
                    toast(R.string.pin_mismatch)
                }
            }
        }
    }

    private fun askPin(titleRes: Int, onPin: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(KidPrefs.PIN_LENGTH))
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.5f
        }
        val box = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- أذونات شاومي

    private fun openAppPermissions() {
        // صفحة أذونات شاومي (فيها «النوافذ المنبثقة في الخلفية»)، وإلا صفحة معلومات التطبيق العادية
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName)
        val ok = runCatching { startActivity(miui) }.isSuccess
        if (!ok) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
