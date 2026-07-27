package com.hatadefteri.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAM_PERM = 2;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private OutputStream exportStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // sayfa https://appassets.androidplatform.net üzerinden servis edilir:
        // IndexedDB ve kamera (getUserMedia) güvenli origin ister, file:// ile çalışmazlar
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // WebView içi kamera isteği (Seri Çekim): uygulama iznine bağla
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                cameraOutputUri = null;

                boolean wantsImage = false;
                String[] accept = params.getAcceptTypes();
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && a.contains("image")) wantsImage = true;
                    }
                }

                // manifest CAMERA izni bildirdiği için izin verilmeden ACTION_IMAGE_CAPTURE açılamaz
                Intent cam = null;
                if (wantsImage && ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        File dir = new File(getCacheDir(), "images");
                        dir.mkdirs();
                        File out = File.createTempFile("cam_", ".jpg", dir);
                        cameraOutputUri = FileProvider.getUriForFile(MainActivity.this,
                                "com.hatadefteri.app.fileprovider", out);
                        cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
                        cam.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception e) {
                        cameraOutputUri = null;
                        cam = null;
                    }
                }

                Intent launch;
                if (cam != null && params.isCaptureEnabled()) {
                    // sayfadaki capture="environment" girişi: seçici yerine doğrudan kamera
                    launch = cam;
                } else {
                    Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                    content.addCategory(Intent.CATEGORY_OPENABLE);
                    content.setType(wantsImage ? "image/*" : "*/*");
                    launch = Intent.createChooser(content,
                            wantsImage ? "Fotoğraf seç" : "Dosya seç");
                    if (cam != null) {
                        launch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    }
                }

                try {
                    startActivityForResult(launch, REQ_FILE);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "HataBridge");
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraOutputUri != null) {
                // galeri değil kamera seçildi: fotoğraf EXTRA_OUTPUT dosyasında
                result = new Uri[]{cameraOutputUri};
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraOutputUri = null;
    }

    /** index.html içinden window.HataBridge olarak erişilir */
    class Bridge {

        @JavascriptInterface
        public boolean openFile(String name) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Hata Defteri");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                exportStream = getContentResolver().openOutputStream(uri);
                return exportStream != null;
            } catch (Exception e) {
                exportStream = null;
                return false;
            }
        }

        @JavascriptInterface
        public boolean writeChunk(String chunk) {
            try {
                exportStream.write(chunk.getBytes("UTF-8"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean closeFile() {
            try {
                exportStream.close();
                exportStream = null;
                return true;
            } catch (Exception e) {
                exportStream = null;
                return false;
            }
        }

        @JavascriptInterface
        public boolean saveImage(String name, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Hata Defteri");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(bytes);
                os.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
