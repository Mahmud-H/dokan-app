package com.mahmud.dokan;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.media.ExifInterface;
import android.os.CancellationSignal;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    // The app's screens and features are downloaded from here when internet is available.
    static final String REMOTE = "https://raw.githubusercontent.com/Mahmud-H/dokan-app/main/app.html";
    static final String MARKER = "<!--DOKAN-APP-->";
    static final String BASE = "https://dokan.local/";
    static final int REQ_PHOTO = 1, REQ_RESTORE = 2, REQ_PERM = 3;

    WebView web;
    final Handler h = new Handler(Looper.getMainLooper());
    volatile boolean ready = false;
    volatile String loaded = null;
    String photoSlot = "o";
    String pendingName, pendingJson;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        getWindow().setStatusBarColor(Color.parseColor("#1F7A52"));
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
        });
        setContentView(web);
        loadApp();
        new Thread(this::checkUpdate).start();
    }

    File f(String name) { return new File(getFilesDir(), name); }

    void loadApp() {
        String html = null;
        boolean cached = false;
        File c = f("app.html");
        if (c.exists() && !f("bad").exists()) {
            try { html = readStream(new java.io.FileInputStream(c)); cached = html.contains(MARKER); } catch (Exception e) { html = null; }
        }
        if (!cached) {
            try { html = readStream(getAssets().open("app.html")); } catch (Exception e) { html = "<h1>app.html missing</h1>"; }
        }
        ready = false;
        loaded = html;
        web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
        if (cached) {
            // If the downloaded version fails to start, fall back to the built-in version.
            h.postDelayed(() -> {
                if (!ready) {
                    try { new FileOutputStream(f("bad")).close(); } catch (Exception ignored) {}
                    loadApp();
                }
            }, 12000);
        }
    }

    void checkUpdate() { checkUpdate(false); }

    // Returns "new", "same" or "fail".
    String checkUpdate(boolean manual) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(REMOTE + "?t=" + (manual ? System.currentTimeMillis() : System.currentTimeMillis() / 60000)).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(20000);
            con.setUseCaches(false);
            if (con.getResponseCode() != 200) return "fail";
            String html = readStream(con.getInputStream());
            if (!html.contains(MARKER) || html.length() < 5000) return "fail";
            if (html.equals(loaded)) return "same";
            String current = null;
            if (f("app.html").exists()) current = readStream(new java.io.FileInputStream(f("app.html")));
            if (!html.equals(current)) {
                File tmp = f("app.html.tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(f("app.html"))) return "fail";
            }
            f("bad").delete();
            if (!manual && current != null && !html.equals(current)) {
                h.post(() -> web.evaluateJavascript("window.onUpdateReady&&onUpdateReady()", null));
            }
            if (!manual && current == null) {
                // First download: switch to it right away if the page is idle at start.
                h.post(() -> web.evaluateJavascript("window.onUpdateReady&&onUpdateReady()", null));
            }
            return "new";
        } catch (Exception e) {
            return "fail";
        }
    }

    static String readStream(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    void js(String code) { h.post(() -> web.evaluateJavascript(code, null)); }

    class Bridge {
        @JavascriptInterface public String load() {
            try { return f("data.json").exists() ? readStream(new java.io.FileInputStream(f("data.json"))) : null; } catch (Exception e) { return null; }
        }
        @JavascriptInterface public synchronized void save(String json) {
            try {
                File tmp = f("data.json.tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                File cur = f("data.json");
                // Keep yesterday-or-older copy as a safety net.
                File prev = f("data-prev.json");
                if (cur.exists() && (!prev.exists() || System.currentTimeMillis() - prev.lastModified() > 86400000L)) {
                    try (InputStream i = new java.io.FileInputStream(cur); OutputStream o = new FileOutputStream(prev)) {
                        byte[] buf = new byte[16384]; int n; while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
                tmp.renameTo(cur);
            } catch (Exception e) {
                h.post(() -> Toast.makeText(MainActivity.this, "সেভ হয়নি!", Toast.LENGTH_LONG).show());
            }
        }
        @JavascriptInterface public void ready() { ready = true; }
        @JavascriptInterface public void reload() { h.post(MainActivity.this::loadApp); }
        @JavascriptInterface public String version() { return "3"; }
        @JavascriptInterface public boolean canBio() {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    BiometricManager bm = getSystemService(BiometricManager.class);
                    return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
                }
                return Build.VERSION.SDK_INT >= 28 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void bio() {
            if (Build.VERSION.SDK_INT < 28) { js("window.onBio&&onBio(false)"); return; }
            h.post(() -> {
                try {
                    BiometricPrompt p = new BiometricPrompt.Builder(MainActivity.this)
                        .setTitle("দামের খাতা খুলুন")
                        .setSubtitle("আঙুলের ছাপ দিন")
                        .setNegativeButton("PIN দিন", getMainExecutor(), (d, w) -> js("window.onBio&&onBio(false)"))
                        .build();
                    p.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { js("window.onBio&&onBio(true)"); }
                        @Override public void onAuthenticationError(int code, CharSequence msg) { js("window.onBio&&onBio(false)"); }
                    });
                } catch (Exception e) { js("window.onBio&&onBio(false)"); }
            });
        }
        @JavascriptInterface public void checkUpdate() {
            new Thread(() -> { String r = MainActivity.this.checkUpdate(true); js("window.onUpdateCheck&&onUpdateCheck('" + r + "')"); }).start();
        }
        @JavascriptInterface public void pickPhoto(String slot) {
            photoSlot = slot;
            h.post(() -> {
                Intent gal = new Intent(Intent.ACTION_GET_CONTENT);
                gal.setType("image/*");
                gal.addCategory(Intent.CATEGORY_OPENABLE);
                Intent ch = Intent.createChooser(gal, "ছবি নিন");
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (cam.resolveActivity(getPackageManager()) != null) ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                try { startActivityForResult(ch, REQ_PHOTO); } catch (Exception e) { js("onPhoto('" + slot + "',null)"); }
            });
        }
        @JavascriptInterface public void pickBackup() {
            h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_RESTORE); } catch (Exception e) { js("onBackupSaved(false,'')"); }
            });
        }
        @JavascriptInterface public void backup(String name, String json) {
            h.post(() -> writeBackup(name, json));
        }
    }

    void writeBackup(String name, String json) {
        try {
            Uri uri;
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dokan");
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                where = "Download/Dokan";
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingName = name; pendingJson = json;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dokan");
                dir.mkdirs();
                File out = new File(dir, name);
                try (OutputStream o = new FileOutputStream(out)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                uri = Uri.fromFile(out);
                where = "Download/Dokan";
            }
            js("onBackupSaved(true," + JSONObject.quote(where + "/" + name) + ")");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(send, "Backup কোথায় পাঠাবেন?")); } catch (Exception ignored) {}
        } catch (Exception e) {
            js("onBackupSaved(false,'')");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_PERM && pendingJson != null) {
            String n = pendingName, j = pendingJson;
            pendingName = null; pendingJson = null;
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) writeBackup(n, j);
            else js("onBackupSaved(false,'')");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PHOTO) {
            if (res != RESULT_OK || data == null) return;
            final String slot = photoSlot;
            final Uri uri = data.getData();
            final Bundle ex = data.getExtras();
            new Thread(() -> {
                String url = null;
                try {
                    Bitmap bm = null;
                    int rot = 0;
                    if (uri != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        try (InputStream i = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(i, null, o); }
                        int sample = 1;
                        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 360) sample *= 2;
                        BitmapFactory.Options o2 = new BitmapFactory.Options();
                        o2.inSampleSize = sample;
                        try (InputStream i = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(i, null, o2); }
                        try (InputStream i = getContentResolver().openInputStream(uri)) {
                            int or = new ExifInterface(i).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                            rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
                        } catch (Exception ignored) {}
                    } else if (ex != null && ex.get("data") instanceof Bitmap) {
                        bm = (Bitmap) ex.get("data");
                    }
                    if (bm != null) {
                        float sc = Math.min(1f, 360f / Math.max(bm.getWidth(), bm.getHeight()));
                        Matrix m = new Matrix();
                        m.postScale(sc, sc);
                        if (rot != 0) m.postRotate(rot);
                        Bitmap out = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        out.compress(Bitmap.CompressFormat.JPEG, 72, bo);
                        url = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                    }
                } catch (Exception ignored) {}
                js("onPhoto(" + JSONObject.quote(slot) + "," + (url == null ? "null" : "'" + url + "'") + ")");
            }).start();
        } else if (req == REQ_RESTORE) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            new Thread(() -> {
                try (InputStream i = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n;
                    while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
                    js("onBackup('" + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP) + "')");
                } catch (Exception e) {
                    js("onBackup('')");
                }
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack?onBack():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("try{saveNow()}catch(e){}", null);
    }
}
