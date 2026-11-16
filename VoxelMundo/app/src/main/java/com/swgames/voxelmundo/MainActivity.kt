package com.swgames.voxelmundo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

// VoxelMundo: o jogo (assets/index.html) numa tela cheia, sem a barra de navegação do celular.
class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // serve o jogo por um endereço https "de mentira" (assim salvar mundos e o multijogador funcionam igual ao site)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { }
                return true // links de fora abrem no navegador
            }
        }
        web.webChromeClient = WebChromeClient()
        setContentView(web)

        if (saved == null || web.restoreState(saved) == null)
            web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        esconderBarras()
    }

    // some com a barra de navegação e a de status; arrastar da borda mostra por um instante
    private fun esconderBarras() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderBarras()
    }

    override fun onResume() { super.onResume(); web.onResume(); esconderBarras() }
    override fun onPause() { web.onPause(); super.onPause() }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    // botão/gesto de voltar = tecla Esc no jogo (pausa, fecha janelas)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){var o={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true};" +
            "document.dispatchEvent(new KeyboardEvent('keydown',o));document.dispatchEvent(new KeyboardEvent('keyup',o));})()", null)
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
