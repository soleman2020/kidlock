package com.kidlock.tube

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** إعدادات التطبيق — تقرؤها شاشة الإعدادات وخدمة القفل في نفس العملية. */
class KidPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("kidlock", Context.MODE_PRIVATE)

    /** وضع الأطفال مفعّل: لا خروج من يوتيوب إلا برمز ولي الأمر. */
    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()

    var skipAds: Boolean
        get() = sp.getBoolean("skip_ads", true)
        set(v) = sp.edit().putBoolean("skip_ads", v).apply()

    var muteAds: Boolean
        get() = sp.getBoolean("mute_ads", true)
        set(v) = sp.edit().putBoolean("mute_ads", v).apply()

    /** إغلاق Shorts تلقائيًا أثناء وضع الأطفال. */
    var blockShorts: Boolean
        get() = sp.getBoolean("block_shorts", true)
        set(v) = sp.edit().putBoolean("block_shorts", v).apply()

    /** السماح بتطبيق YouTube Kids أيضًا أثناء القفل. */
    var allowKids: Boolean
        get() = sp.getBoolean("allow_kids", true)
        set(v) = sp.edit().putBoolean("allow_kids", v).apply()

    /** الخدمة هي من كتم الصوت أثناء إعلان — لإعادته حتى لو أُعيد تشغيلها. */
    var mutedByUs: Boolean
        get() = sp.getBoolean("muted_by_us", false)
        set(v) = sp.edit().putBoolean("muted_by_us", v).apply()

    /** آخر تطبيق يوتيوب كان مفتوحًا، لإعادة الطفل إليه. */
    var lastApp: String
        get() = sp.getString("last_app", KidService.YOUTUBE).orEmpty()
        set(v) = sp.edit().putString("last_app", v).apply()

    var failCount: Int
        get() = sp.getInt("fail_count", 0)
        set(v) = sp.edit().putInt("fail_count", v).apply()

    /** وقت انتهاء المنع بعد محاولات خاطئة متكررة (بتوقيت الساعة). */
    var lockoutUntil: Long
        get() = sp.getLong("lockout_until", 0L)
        set(v) = sp.edit().putLong("lockout_until", v).apply()

    val hasPin: Boolean get() = sp.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null) ?: return false
        val stored = sp.getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_FAILS = 5
        const val LOCKOUT_MS = 60_000L
    }
}
