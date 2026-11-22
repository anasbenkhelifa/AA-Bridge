package dev.caraalink.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import dev.caraalink.video.CarWebPresentation

/**
 * The same browser the car screen uses, on the phone, for signing in where
 * there is a keyboard. What is signed in here is signed in on the car.
 */
class WebSignInActivity : ComponentActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        CarWebPresentation.configure(view)
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        web = view
        setContentView(view)
        view.loadUrl(intent.getStringExtra(EXTRA_URL) ?: CarWebPresentation.YOUTUBE)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
