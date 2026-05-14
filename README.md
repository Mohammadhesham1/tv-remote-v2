# 📺 TV Remote – تحكم في تليفزيونك عبر Wi-Fi

تطبيق Android ريموت كنترول للتليفزيون يعمل على نفس شبكة الـ Wi-Fi بدون أي تثبيت على التليفزيون.

## كيف يشتغل؟

- التطبيق بيبعت أوامر JSON عبر **UDP (port 7777)** للاستجابة السريعة
- أوامر النصوص والتطبيقات بتتبعت عبر **TCP (port 7778)** لضمان الوصول
- التليفزيون لازم يكون شغّال receiver (مثلاً Raspberry Pi أو تطبيق على Android TV)

## البناء التلقائي (GitHub Actions)

كل push على `main` بيبني APK تلقائياً.  
التنزيل من: **Actions → آخر workflow → Artifacts**

## التثبيت والتشغيل

1. حمّل APK من قسم Artifacts في GitHub Actions
2. فعّل "Unknown sources" على جهازك
3. ثبّت الـ APK
4. افتح التطبيق وادخل IP التليفزيون أو ابحث تلقائياً

## بروتوكول الأوامر

```json
{ "cmd": "dpad_up" }
{ "cmd": "vol_up", "val": 60 }
{ "cmd": "open_app", "val": "com.netflix.ninja" }
{ "cmd": "text_input", "val": "مرحبا" }
```

## الصفحات

| الصفحة | الوصف |
|--------|-------|
| MainActivity | الريموت الرئيسي |
| SettingsActivity | قائمة الإعدادات |
| BehaviorSettingsActivity | الاهتزاز، الشاشة، وضع المسرح |
| LayoutSettingsActivity | نمط التنقل والصف السريع |
| AppsSettingsActivity | إدارة اختصارات التطبيقات |
| DevicesActivity | البحث والاتصال بالأجهزة |
| PairDeviceActivity | شاشة الربط الأولى |

## الميزات

- ✅ D-Pad كامل (↑↓←→ + OK)
- ✅ تحكم في الصوت والقنوات
- ✅ اختصارات Netflix / YouTube / Prime / Disney+ / Apple TV / HBO
- ✅ أزرار الميديا (تشغيل، إيقاف، تقديم، ترجيع)
- ✅ أزرار الألوان (أحمر / أخضر / أصفر / أزرق)
- ✅ لوحة أرقام 0-9
- ✅ إرسال نص للتليفزيون
- ✅ إيقاف/تشغيل التليفزيون
- ✅ حفظ الإعدادات بين الجلسات
- ✅ اهتزاز قابل للإيقاف
- ✅ دعم RTL كامل
