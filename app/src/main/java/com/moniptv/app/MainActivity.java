package com.moniptv.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {

    private WebView web;
    private LocalServer server;
    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Petit serveur interne (127.0.0.1) : sert l'interface et relaie les flux IPTV
        try {
            server = new LocalServer(new LocalServer.Assets() {
                @Override
                public InputStream open(String name) throws IOException {
                    return getAssets().open(name);
                }
            });
            server.start();
        } catch (IOException e) {
            finish();
            return;
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setBackgroundColor(0xFF0F1117);
        web.setWebViewClient(new WebViewClient());

        chrome = new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                web.setVisibility(View.GONE);
                MainActivity.this.addContentView(view, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                ViewGroup parent = (ViewGroup) customView.getParent();
                if (parent != null) parent.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
            }
        };
        web.setWebChromeClient(chrome);

        web.requestFocus();
        web.loadUrl("http://127.0.0.1:" + server.getPort() + "/");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            if (customView != null) {
                chrome.onHideCustomView();
                return true;
            }
            // L'interface ferme d'abord le lecteur ; sinon on quitte l'appli
            web.evaluateJavascript(
                    "(function(){return window.tvBack ? String(window.tvBack()) : 'false';})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value == null || !value.contains("true")) finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        if (server != null) server.stop();
        super.onDestroy();
    }
}
