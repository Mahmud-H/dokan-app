# দামের খাতা (Dokan App)

Offline shop price book for Android.

- `app.html` — the app's screens and features. The installed app downloads this file when internet is available and keeps it for offline use, so changing this file updates the app without reinstalling.
- `src/app.template.html` — source of `app.html` (font is inlined at build time).
- `android/` — the Android wrapper (WebView). Shop data is stored only on the phone, never in this repository.
