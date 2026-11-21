package com.kidlock.tube

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * خدمة الوصول:
 * 1) وضع الأطفال: أي تطبيق غير يوتيوب يظهر في المقدمة يُستبدل فورًا بيوتيوب، ولوحة الإشعارات تُغلق،
 *    والروابط المفتوحة من داخل يوتيوب (كروم، قائمة المشاركة) تُغلق.
 *    الخروج: ضغطة مطوّلة على الركن العلوي الأيسر ثم رمز ولي الأمر.
 * 2) الإعلانات داخل يوتيوب: ضغط «تخطي» تلقائيًا فور ظهوره، وكتم الصوت طوال مدة الإعلان.
 */
class KidService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: KidPrefs
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private var alive = false

    private var corner: View? = null
    private var pinPad: View? = null
    private var debugReceiver: BroadcastReceiver? = null

    private var youtubeInFront = false
    private var lastRelaunch = 0L
    private var lastBack = 0L
    private var lastContentCheck = 0L
    private var lastSkipClick = 0L
    private var adActive = false
    private var lastAdSeen = 0L

    private val guardTask = Runnable { guard() }

    /** فحص دوري أثناء القفل: بعض الحالات (مثل يوتيوب المصغّر فوق الشاشة الرئيسية) لا ترسل أحداثًا. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!alive) return
            if (prefs.locked) guard()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }
    private val adPoll = object : Runnable {
        override fun run() {
            adCheck()
            if (alive && youtubeInFront && youtubeChecksEnabled()) handler.postDelayed(this, AD_POLL_MS)
        }
    }

    // ---------------------------------------------------------------- دورة الحياة

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = KidPrefs(this)
        wm = getSystemService(WindowManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        alive = true
        // إن توقفت الخدمة أثناء إعلان مكتوم، أعد الصوت
        if (prefs.mutedByUs) unmute()
        registerDebugReceiver()
        guard()
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!alive || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handler.removeCallbacks(guardTask)
                handler.postDelayed(guardTask, GUARD_DELAY_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString() in YOUTUBE_APPS && youtubeChecksEnabled()) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastContentCheck >= CONTENT_THROTTLE_MS) {
                        lastContentCheck = now
                        adCheck()
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (alive) {
            alive = false
            handler.removeCallbacksAndMessages(null)
            debugReceiver?.let { runCatching { unregisterReceiver(it) } }
            debugReceiver = null
            if (prefs.mutedByUs) unmute()
            removeView(corner); corner = null
            removeView(pinPad); pinPad = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- حارس وضع الأطفال

    private fun guard() {
        if (!alive) return
        updateCorner()
        val wins = runCatching { windows }.getOrNull().orEmpty()
        val pkg = frontApp(wins)?.let { w -> runCatching { w.root?.packageName?.toString() }.getOrNull() }

        val ytFront = pkg in YOUTUBE_APPS
        if (ytFront != youtubeInFront) {
            youtubeInFront = ytFront
            handler.removeCallbacks(adPoll)
            if (ytFront) handler.post(adPoll) else endAd()
        } else if (ytFront && youtubeChecksEnabled()) {
            // قد يكون الفحص توقف (مثلًا الإعلانات معطّلة) ثم بدأ وضع الأطفال
            handler.removeCallbacks(adPoll)
            handler.post(adPoll)
        }

        if (!prefs.locked || pinPad != null || keyguard.isKeyguardLocked) return

        if (notificationShadeOpen(wins)) {
            Log.d(TAG, "closing notification shade")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        if (pkg == null) {
            // يوتيوب مصغّر (صورة داخل صورة) ولا تطبيق آخر في المقدمة: نعيده لملء الشاشة
            if (wins.any { it.isInPictureInPictureMode }) {
                Log.d(TAG, "YouTube minimized (picture-in-picture)")
                relaunch(fresh = true)
            }
            return
        }
        if (pkg in allowedApps()) {
            if (pkg in YOUTUBE_APPS) prefs.lastApp = pkg
            return
        }
        Log.d(TAG, "blocked $pkg")
        // أولًا «رجوع»: يغلق الرابط المفتوح من داخل يوتيوب (تبويب كروم فوقه) أو قائمة المشاركة.
        // إن بقي تطبيق ممنوع بعدها، نعيد فتح يوتيوب.
        val now = SystemClock.uptimeMillis()
        if (now - lastBack > BACK_WINDOW_MS) {
            lastBack = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.removeCallbacks(guardTask)
            handler.postDelayed(guardTask, AFTER_BACK_MS)
            return
        }
        relaunch(fresh = wins.any { it.isInPictureInPictureMode })
    }

    /**
     * نافذة التطبيق الذي يستخدمه الطفل الآن (نتجاهل نوافذ النظام ونوافذنا العائمة).
     * نافذة «صورة داخل صورة» لا تُحسب: يوتيوب مصغّر فوق الشاشة الرئيسية يعني أن الطفل خرج منه.
     */
    private fun frontApp(wins: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val apps = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        return apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
    }

    /** لوحة الإشعارات أو مركز التحكم مفتوح: نافذة نظام تغطي معظم الشاشة. */
    private fun notificationShadeOpen(wins: List<AccessibilityWindowInfo>): Boolean {
        val screen = screenSize()
        val r = Rect()
        return wins.any {
            if (it.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            it.getBoundsInScreen(r)
            r.height() > screen.y * 0.45 && r.width() > screen.x * 0.6
        }
    }

    private fun allowedApps(): Set<String> {
        val apps = HashSet(CALL_APPS)
        apps.add(YOUTUBE)
        if (prefs.allowKids) apps.add(YOUTUBE_KIDS)
        return apps
    }

    /** يعيد الطفل إلى يوتيوب (من حيث توقف). */
    private fun relaunch(fresh: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (now - lastRelaunch < RELAUNCH_GAP_MS) {
            handler.removeCallbacks(guardTask)
            handler.postDelayed(guardTask, RELAUNCH_GAP_MS)
            return
        }
        lastRelaunch = now
        val target = listOf(prefs.lastApp, YOUTUBE, YOUTUBE_KIDS)
            .filter { it in allowedApps() }
            .firstNotNullOfOrNull { pkg -> packageManager.getLaunchIntentForPackage(pkg) }
        if (target == null) {
            Log.w(TAG, "no YouTube app installed")
            return
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // يوتيوب مصغّر (صورة داخل صورة) لا يعود لملء الشاشة بفتحه فقط: نعيد تشغيله من جديد
        if (fresh) target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(target)
        } catch (e: Exception) {
            Log.w(TAG, "relaunch failed: $e")
        }
        // تحقّق مرة أخرى بعد قليل: إن منع النظام الفتح نحاول من جديد
        handler.removeCallbacks(guardTask)
        handler.postDelayed(guardTask, RECHECK_MS)
    }

    // ---------------------------------------------------------------- ركن الخروج السري

    private val openPinPad = Runnable { showPinPad() }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateCorner() {
        if (prefs.locked && corner == null) {
            val view = View(this)
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> handler.postDelayed(openPinPad, HOLD_TO_EXIT_MS)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE ->
                        handler.removeCallbacks(openPinPad)
                }
                true
            }
            val size = dp(CORNER_DP)
            val lp = overlayParams(size, size, touchable = true).apply {
                gravity = Gravity.TOP or Gravity.LEFT
            }
            if (add(view, lp)) corner = view
        } else if (!prefs.locked && corner != null) {
            handler.removeCallbacks(openPinPad)
            removeView(corner)
            corner = null
        }
    }

    // ---------------------------------------------------------------- لوحة رمز ولي الأمر

    private val entered = StringBuilder()
    private var pinDots: TextView? = null
    private var pinMessage: TextView? = null
    private val closePinPadTask = Runnable { closePinPad() }

    private fun showPinPad() {
        if (pinPad != null || !prefs.locked) return
        entered.clear()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFF202124.toInt())
            }
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            setText(R.string.pin_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        pinDots = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, dp(12), 0, dp(4))
        }
        card.addView(pinDots)
        pinMessage = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(22)
        }
        card.addView(pinMessage)

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", CANCEL_KEY, "0", DELETE_KEY)
        var row: LinearLayout? = null
        for ((index, key) in keys.withIndex()) {
            if (index % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
                pad.addView(row)
            }
            val label = when (key) {
                CANCEL_KEY -> getString(R.string.pin_cancel)
                DELETE_KEY -> "⌫"
                else -> key
            }
            row!!.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = if (key.length == 1 && key[0].isDigit()) 26f else 16f
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF3C4043.toInt())
                    }
                    setOnClickListener { onPinKey(key) }
                },
                LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                    setMargins(dp(8), dp(6), dp(8), dp(6))
                },
            )
        }
        card.addView(pad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xD9000000.toInt())
            // لمسة خارج البطاقة لا تمر إلى يوتيوب
            setOnClickListener {}
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        val lp = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = true)
        if (add(root, lp)) {
            pinPad = root
            refreshPinDots()
            showLockoutIfAny()
            handler.postDelayed(closePinPadTask, PIN_PAD_TIMEOUT_MS)
        }
    }

    private fun onPinKey(key: String) {
        handler.removeCallbacks(closePinPadTask)
        handler.postDelayed(closePinPadTask, PIN_PAD_TIMEOUT_MS)
        when (key) {
            CANCEL_KEY -> {
                closePinPad()
                return
            }
            DELETE_KEY -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            else -> {
                if (showLockoutIfAny()) return
                if (entered.length < KidPrefs.PIN_LENGTH) entered.append(key)
            }
        }
        refreshPinDots()
        if (entered.length == KidPrefs.PIN_LENGTH) verifyPin()
    }

    private fun verifyPin() {
        val ok = prefs.checkPin(entered.toString())
        Log.d(TAG, "pin check: ${if (ok) "ok" else "wrong"}")
        if (ok) {
            prefs.failCount = 0
            unlock()
            return
        }
        entered.clear()
        refreshPinDots()
        val fails = prefs.failCount + 1
        if (fails >= KidPrefs.MAX_FAILS) {
            prefs.failCount = 0
            prefs.lockoutUntil = System.currentTimeMillis() + KidPrefs.LOCKOUT_MS
            showLockoutIfAny()
        } else {
            prefs.failCount = fails
            pinMessage?.text = getString(R.string.pin_wrong, KidPrefs.MAX_FAILS - fails)
        }
    }

    /** يعرض رسالة المنع المؤقت إن وُجد، ويعيد true إن كان الإدخال ممنوعًا الآن. */
    private fun showLockoutIfAny(): Boolean {
        val remaining = prefs.lockoutUntil - System.currentTimeMillis()
        if (remaining <= 0) return false
        pinMessage?.text = getString(R.string.pin_locked_out, (remaining / 1000 + 1).toInt())
        return true
    }

    private fun refreshPinDots() {
        pinDots?.text = (0 until KidPrefs.PIN_LENGTH).joinToString(" ") { if (it < entered.length) "●" else "○" }
    }

    private fun closePinPad() {
        handler.removeCallbacks(closePinPadTask)
        removeView(pinPad)
        pinPad = null
        pinDots = null
        pinMessage = null
        entered.clear()
        guard()
    }

    private fun unlock() {
        prefs.locked = false
        closePinPad()
        updateCorner()
        Toast.makeText(this, R.string.unlocked, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------------------------------------------------------------- إعلانات يوتيوب

    private fun adsEnabled() = prefs.skipAds || prefs.muteAds

    /** حظر Shorts يعمل في وضع الأطفال فقط (ولي الأمر يشاهدها عادي خارجه). */
    private fun shortsBlocked() = prefs.locked && prefs.blockShorts && pinPad == null

    private fun youtubeChecksEnabled() = adsEnabled() || shortsBlocked()

    private class AdScan {
        var skip: AccessibilityNodeInfo? = null
        var marker: String? = null
        var shorts: String? = null
        var home: AccessibilityNodeInfo? = null
        var count = 0
    }

    private fun adCheck() {
        if (!alive || !youtubeChecksEnabled()) return
        val root = youtubeRoot()
        if (root == null) {
            endAd()
            return
        }
        val scan = AdScan()
        scanNode(root, scan, inPlayer = false, depth = 0)
        val now = SystemClock.uptimeMillis()

        if (shortsBlocked()) {
            if (scan.shorts != null) leaveShorts(scan, now) else shortsSince = 0L
        }
        if (!adsEnabled()) return

        if (scan.skip != null || scan.marker != null) {
            lastAdSeen = now
            if (!adActive) {
                adActive = true
                Log.d(TAG, "ad started (marker=${scan.marker}, skip=${scan.skip != null})")
                if (prefs.muteAds) mute()
            }
        } else if (adActive && now - lastAdSeen > AD_END_GRACE_MS) {
            endAd()
        }

        val skip = scan.skip
        if (skip != null && prefs.skipAds && now - lastSkipClick > SKIP_CLICK_GAP_MS) {
            lastSkipClick = now
            Log.d(TAG, "skipping ad: id=${skip.viewIdResourceName} text=${skip.text} desc=${skip.contentDescription}")
            click(skip)
        }
    }

    // ---------------------------------------------------------------- حظر Shorts

    private var shortsSince = 0L
    private var lastShortsAction = 0L

    /** يخرج من Shorts: «رجوع» أولًا، وإن بقيت مفتوحة (تبويب Shorts نفسه) يضغط تبويب «الرئيسية». */
    private fun leaveShorts(scan: AdScan, now: Long) {
        if (now - lastShortsAction < SHORTS_ACTION_GAP_MS) return
        lastShortsAction = now
        if (shortsSince == 0L) shortsSince = now
        val home = scan.home
        if (now - shortsSince > SHORTS_BACK_FIRST_MS && home != null) {
            Log.d(TAG, "shorts (${scan.shorts}): tapping Home tab")
            click(home)
        } else {
            Log.d(TAG, "shorts (${scan.shorts}): back")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun endAd() {
        if (!adActive && !prefs.mutedByUs) return
        if (adActive) Log.d(TAG, "ad ended")
        adActive = false
        if (prefs.mutedByUs) unmute()
    }

    private fun youtubeRoot(): AccessibilityNodeInfo? {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() in YOUTUBE_APPS) return root
        }
        // بعض الشاشات (مثل Shorts) لا تعطي جذر النافذة من القائمة: جرّب النافذة النشطة مباشرة
        val active = runCatching { rootInActiveWindow }.getOrNull()
        return active?.takeIf { it.packageName?.toString() in YOUTUBE_APPS }
    }

    private fun scanNode(node: AccessibilityNodeInfo?, scan: AdScan, inPlayer: Boolean, depth: Int) {
        if (node == null || depth > MAX_DEPTH || scan.count >= MAX_NODES) return
        scan.count++
        if (!node.isVisibleToUser) return

        val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val player = inPlayer || PLAYER_ID.matches(id)

        if (scan.skip == null && (id.contains("skip_ad") || SKIP_TEXT.matches(text) || SKIP_TEXT.matches(desc))) {
            // زر التخطي ظاهر فعلًا (وليس العد التنازلي «يمكنك التخطي بعد 5»)
            if (!COUNTDOWN.containsMatchIn(text) && !COUNTDOWN.containsMatchIn(desc)) scan.skip = node
        }
        if (scan.shorts == null) {
            if (SHORTS_ID.containsMatchIn(id) && coversScreen(node)) {
                scan.shorts = "id:$id"
            } else if (node.isSelected && (SHORTS_LABEL.matches(desc) || SHORTS_LABEL.matches(text))) {
                // تبويب Shorts في الشريط السفلي هو المختار
                scan.shorts = "tab"
            }
        }
        if (scan.home == null && node.isClickable && (HOME_LABEL.matches(desc) || HOME_LABEL.matches(text))) {
            scan.home = node
        }
        if (scan.marker == null && player) {
            when {
                AD_ID.containsMatchIn(id) -> scan.marker = "id:$id"
                AD_TEXT.matches(text) -> scan.marker = "text:$text"
                AD_TEXT.matches(desc) -> scan.marker = "desc:$desc"
            }
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
            scanNode(child, scan, player, depth + 1)
        }
    }

    /** العنصر يغطي معظم الشاشة (مشغّل Shorts) وليس رفًّا صغيرًا في الصفحة الرئيسية. */
    private fun coversScreen(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        val screen = screenSize()
        return r.height() >= screen.y * 0.6 && r.width() >= screen.x * 0.8
    }

    /** يضغط العنصر أو أقرب أب قابل للضغط، وإلا يلمس منتصفه. */
    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        repeat(6) {
            val cur = n ?: return@repeat
            if (cur.isClickable && cur.isEnabled) {
                if (runCatching { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return
            }
            n = cur.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun mute() {
        if (prefs.mutedByUs || audio.isStreamMute(AudioManager.STREAM_MUSIC)) return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        prefs.mutedByUs = true
    }

    private fun unmute() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        prefs.mutedByUs = false
    }

    // ---------------------------------------------------------------- أدوات

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun add(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, lp)
        true
    } catch (e: Exception) {
        Log.w(TAG, "addView failed: $e")
        false
    }

    private fun removeView(view: View?) {
        if (view != null) runCatching { wm.removeView(view) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun screenSize(): Point {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val m = resources.displayMetrics
            Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        return Point(b.width(), b.height())
    }

    /**
     * في نسخة التطوير فقط — للاختبار من الكمبيوتر:
     * adb shell am broadcast -a com.kidlock.tube.DEBUG -p com.kidlock.tube --es action lock|unlock|pin|dump
     *     (أو --es action setpin --es value 1234 لتعيين رمز اختبار)
     */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra("action")) {
                    "lock" -> { prefs.locked = true; guard() }
                    "unlock" -> { prefs.locked = false; closePinPad(); updateCorner() }
                    "pin" -> showPinPad()
                    "setpin" -> intent.getStringExtra("value")?.let { if (it.length == KidPrefs.PIN_LENGTH) prefs.setPin(it) }
                    "dump" -> dumpYoutube()
                }
            }
        }
        val filter = IntentFilter("com.kidlock.tube.DEBUG")
        // إذن DUMP يملكه adb فقط: لا يستطيع أي تطبيق آخر على الهاتف فك القفل بهذا الأمر
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }
        debugReceiver = receiver
    }

    /** يسجّل عناصر شاشة يوتيوب (المعرّفات والنصوص) لمعرفة شكل الإعلان. */
    private fun dumpYoutube() {
        val root = youtubeRoot() ?: run {
            val wins = runCatching { windows }.getOrNull().orEmpty()
            Log.d(TAG, "dump: YouTube not on screen; windows=" + wins.joinToString { w ->
                "${w.type}/${w.title}/root=${runCatching { w.root?.packageName }.getOrNull()}"
            } + " active=${runCatching { rootInActiveWindow?.packageName }.getOrNull()}")
            return
        }
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || count > MAX_NODES) return
            count++
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            val text = n.text?.toString()?.take(40).orEmpty()
            val desc = n.contentDescription?.toString()?.take(40).orEmpty()
            if (n.isVisibleToUser && (id.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty())) {
                Log.d(TAG, "dump ${"·".repeat(minOf(depth, 30))} id=$id | t=$text | d=$desc | click=${n.isClickable}")
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        Log.d(TAG, "dump done: $count nodes")
    }

    companion object {
        private const val TAG = "KidLock"

        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_KIDS = "com.google.android.apps.youtube.kids"
        val YOUTUBE_APPS = setOf(YOUTUBE, YOUTUBE_KIDS)

        /**
         * مسموحة دائمًا: شاشة المكالمات (حتى لا تضيع مكالمة واردة)،
         * ونوافذ طلب الأذونات التي يفتحها يوتيوب فوقه.
         */
        private val CALL_APPS = setOf(
            "com.android.incallui", "com.google.android.dialer", "com.android.server.telecom", "com.android.phone",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        )

        private const val GUARD_DELAY_MS = 120L
        private const val HEARTBEAT_MS = 1_000L
        private const val RELAUNCH_GAP_MS = 600L
        private const val BACK_WINDOW_MS = 1_500L
        private const val AFTER_BACK_MS = 300L
        private const val RECHECK_MS = 800L
        private const val CONTENT_THROTTLE_MS = 250L
        private const val AD_POLL_MS = 700L
        private const val AD_END_GRACE_MS = 1_500L
        private const val SKIP_CLICK_GAP_MS = 800L
        private const val HOLD_TO_EXIT_MS = 3_000L
        private const val PIN_PAD_TIMEOUT_MS = 30_000L
        private const val CORNER_DP = 56
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 80
        private const val CANCEL_KEY = "cancel"
        private const val DELETE_KEY = "delete"

        private val SKIP_TEXT = Regex(
            """^(skip|skip ad|skip ads|skip ad[s]? ▸?|تخطي|تخطّي|تخطي الإعلان|تخطّي الإعلان|تخطي الإعلانات|تخطّي الإعلانات)\s*[▶▸›>]?$""",
            RegexOption.IGNORE_CASE,
        )
        private val COUNTDOWN = Regex("""\d""")
        /**
         * عناصر مشغّل الفيديو نفسه. لا نكتفي بوجود كلمة player: يوتيوب يغلّف الشاشة كلها
         * في حاويات مثل slim_status_bar_player_container، وتحت المشغّل بطاقات «Sponsored» ليست إعلانًا يُعرض الآن.
         */
        private val PLAYER_ID = Regex("""^(watch_player|player_[a-z_]+|inline_player[a-z_]*|youtube_controls_overlay)$""")

        /**
         * مشغّل Shorts نفسه (يملأ الشاشة). لا نكتفي بالبادئة reel_: الصفحة الرئيسية فيها reel_time_bar
         * مخفي بحجم الشاشة، فيُحسب خطأً.
         */
        private val SHORTS_ID = Regex("""^(reel_watch_fragment_root|reel_recycler|reel_player_page_container|reel_watch_player)$""")
        private val SHORTS_LABEL = Regex("""^(shorts|شورتس)$""", RegexOption.IGNORE_CASE)
        private val HOME_LABEL = Regex("""^(home|الرئيسية|الصفحة الرئيسية)$""", RegexOption.IGNORE_CASE)
        private const val SHORTS_ACTION_GAP_MS = 900L
        private const val SHORTS_BACK_FIRST_MS = 1_500L

        /** «ad» ككلمة مستقلة في المعرّف فقط (load_progress مثلًا ليست إعلانًا). */
        private val AD_ID = Regex("""((^|_)ads?(_|$)|skip_ad|player_learn_more|visit_advertiser)""")
        private val AD_TEXT = Regex(
            """^(ad|ad ·.*|ad •.*|sponsored|sponsored ·.*|إعلان|إعلان ·.*|إعلان •.*|إعلان ممول|ممول|visit advertiser|زيارة المعلن|زيارة المعلِن)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
