# قفل الأطفال — KidLock for YouTube

تطبيق أندرويد يعطي الطفل الهاتف وهو يشاهد يوتيوب فقط: لا يخرج إلى تطبيقات أخرى، ولا يفتح روابط، ولا يرى Shorts، والإعلانات تُتخطّى وتُكتم تلقائيًا. الخروج برمز ولي الأمر فقط.

**العربية** · [English](#kidlock-قفل-الأطفال--english)

## المميزات

- **وضع الأطفال**: أي تطبيق آخر يُغلق ويعود يوتيوب خلال ثانية تقريبًا. يشمل ذلك الشاشة الرئيسية والإعدادات والمتصفح وقائمة المشاركة ولوحة الإشعارات.
- **منع الروابط**: الروابط التي تُفتح من داخل يوتيوب لا تخرج منه.
- **إغلاق Shorts** تلقائيًا والرجوع للصفحة الرئيسية.
- **تخطّي الإعلانات**: يضغط زر «تخطي» فور ظهوره، ويكتم الصوت طوال الإعلان ثم يعيده.
- **YouTube Kids** مسموح أيضًا (اختياري).
- **رمز ولي الأمر** من 4 أرقام:
  - يُحفظ مشفّرًا (SHA-256 مع salt).
  - بعد 5 محاولات خاطئة تُقفل لوحة الأرقام دقيقة كاملة.
- **مكالمات الهاتف** تعمل عادي أثناء القفل.
- بلا إنترنت: التطبيق لا يطلب إذن الإنترنت أصلًا، ولا يجمع أي بيانات.

## طريقة الاستخدام

1. ثبّت التطبيق، ثم فعّل **قفل الأطفال — يوتيوب** من إعدادات إمكانية الوصول.
   - إن ظهرت رسالة «إعداد مقيّد»: معلومات التطبيق ← القائمة ⋮ ← «السماح بالإعدادات المقيّدة».
2. عيّن رمز ولي الأمر.
3. اضغط **تشغيل وضع الأطفال**، فيفتح يوتيوب مقفولًا.
4. **للخروج**: اضغط مطولًا 3 ثوانٍ على الركن العلوي الأيسر من الشاشة (فوق الساعة)، ثم أدخل الرمز.

### هواتف شاومي (HyperOS/MIUI)

- من أذونات التطبيق فعّل «عرض النوافذ المنبثقة أثناء التشغيل في الخلفية». بدونها لا يستطيع التطبيق إرجاع الطفل إلى يوتيوب.
- فعّل «التشغيل التلقائي»، واجعل توفير البطارية «بلا قيود».
- لا تفعّل «اختصار إمكانية الوصول» للخدمة، لأن الطفل قد يوقفها به.

## حدود يجب معرفتها

- هذا ليس قفلًا أمنيًا كاملًا. طفل كبير يعرف إعادة تشغيل الهاتف في الوضع الآمن قد يتجاوزه. لحماية أقوى استخدم Google Family Link مع التطبيق.
- التطبيق **لا يفلتر المحتوى داخل يوتيوب**. لمحتوى مختار بعناية استخدم YouTube Kids بوضع «الموافقة على المحتوى بنفسك».
- اكتشاف الإعلانات وShorts يعتمد على واجهة تطبيق يوتيوب، وقد يحتاج تحديثًا إذا غيّرت يوتيوب تصميمها.
- تخطّي الإعلانات قد يخالف شروط خدمة يوتيوب. التطبيق لا يحجب الإعلانات، بل يضغط زر «تخطي» المتاح للمستخدم ويكتم الصوت فقط. استخدمه على مسؤوليتك.

## البناء من المصدر

المتطلبات: Android Studio حديث (JDK 17 أو أحدث) و Android SDK 36.

```bash
./gradlew assembleDebug
# الملف الناتج: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 (أندرويد 8)، targetSdk 35
- كل المنطق في `KidService.kt` (AccessibilityService)

## المساهمة

البلاغات وطلبات الدمج مرحّب بها، خصوصًا عند تغيّر واجهة يوتيوب.

## الرخصة

[MIT](LICENSE)

---

<div dir="ltr">

# KidLock (قفل الأطفال) — English

Hand your child the phone with YouTube only. They can't leave to other apps, open links or watch Shorts, and ads are skipped and muted automatically. Only a parent PIN unlocks it.

## Features

- **Kids mode**: any other app is closed and YouTube comes back within about a second. That includes the home screen, Settings, the browser, the share sheet and the notification shade.
- **Link blocking**: links opened from inside YouTube don't leave it.
- **Shorts blocking**: Shorts are closed automatically and the app returns to Home.
- **Ad skipping**: taps "Skip" as soon as it appears, and mutes the sound for the whole ad, then restores it.
- **YouTube Kids** can be allowed too (optional).
- **Parent PIN** of 4 digits:
  - Stored hashed (SHA-256 with a salt).
  - After 5 wrong tries the keypad locks for one minute.
- **Phone calls** keep working while locked.
- **No internet**: the app doesn't even request the internet permission, and collects no data.

## How to use

1. Install the app, then enable **قفل الأطفال — يوتيوب** in Accessibility settings.
   - If you see "Restricted setting": App info → ⋮ menu → "Allow restricted settings".
2. Set a parent PIN.
3. Tap **Start kids mode** (تشغيل وضع الأطفال). YouTube opens, locked.
4. **To exit**: long-press the top-left corner of the screen (above the clock) for 3 seconds, then enter the PIN.

### Xiaomi phones (HyperOS/MIUI)

- In the app's permissions, enable "Display pop-up windows while running in the background". Without it the app can't bring the child back to YouTube.
- Enable "Autostart", and set battery saver to "No restrictions".
- Don't enable the "Accessibility shortcut" for the service, because a child could turn it off with it.

## Limitations

- This is not a full security lock. An older child who knows how to reboot into safe mode can get around it. For stronger protection, use Google Family Link alongside it.
- The app **does not filter content inside YouTube**. For hand-picked content, use YouTube Kids with "Approve content yourself".
- Ad and Shorts detection depends on the YouTube app's UI and may need updates when YouTube changes its design.
- Skipping ads may conflict with YouTube's Terms of Service. The app doesn't block ads: it only taps the Skip button that's already offered to the user, and mutes the sound. Use it at your own risk.

## Build from source

You need a recent Android Studio (JDK 17+) and Android SDK 36.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 (Android 8), targetSdk 35
- All the logic is in `KidService.kt` (an AccessibilityService)

## Contributing

Issues and pull requests are welcome, especially when YouTube's UI changes.

## License

[MIT](LICENSE)

</div>
