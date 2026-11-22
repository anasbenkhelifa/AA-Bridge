package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.link.CarMotion
import dev.caraalink.settings.AppSettings

/**
 * A web page on the car screen. A browser view is one of this app's own
 * windows, so it can go on the car's display with nothing else set up: no
 * Shizuku, no screen sharing, and the phone stays free. YouTube and Claude
 * both run as web pages, laid out for the car's wide screen, with touch
 * working as it does on any page and sound going out through the phone.
 *
 * Sign-ins are shared with [dev.caraalink.ui.WebSignInActivity], which is
 * where typing is comfortable: the phone.
 */
class CarWebPresentation(
    context: Context,
    display: Display,
    private val url: String,
    private val onHome: () -> Unit,
) : Presentation(context, display) {

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private lateinit var root: FrameLayout
    private lateinit var cover: TextView
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val tick = object : Runnable {
        override fun run() {
            val hide = MirrorCarPresentation.hidden(AppSettings.mirrorHideMoving(context), CarMotion.motion())
            cover.visibility = if (hide) View.VISIBLE else View.GONE
            // Covered video keeps playing its sound; pausing the page would also stop a talk or a recitation.
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val view = WebView(context)
        configure(view)
        view.webViewClient = WebViewClient()
        view.webChromeClient = object : WebChromeClient() {
            // A video's full screen button: the player takes the whole car screen.
            override fun onShowCustomView(v: View, callback: CustomViewCallback) {
                leaveFullscreen()
                fullscreen = v
                fullscreenCallback = callback
                root.addView(v, root.indexOfChild(cover), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() = leaveFullscreen()
        }
        web = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cover = TextView(context).apply {
            setText(R.string.web_moving)
            textSize = 26f
            setTextColor(CarWidgets.TEXT)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
        }
        root.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Back goes back a page, and home from the first one.
        CarNav.attach(root, onHome, onBack = { if (!goBack()) onHome() })
        setContentView(root)
        view.loadUrl(url)
    }

    private fun leaveFullscreen() {
        val v = fullscreen ?: return
        fullscreen = null
        root.removeView(v)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    /** The car's Back button: out of full screen, then back a page. False when there is nowhere left to go back to. */
    fun goBack(): Boolean {
        if (fullscreen != null) {
            web?.webChromeClient?.onHideCustomView()
            return true
        }
        val view = web ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    override fun onStart() {
        super.onStart()
        tick.run()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        CookieManager.getInstance().flush()
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            root.removeView(it)
            it.destroy()
        }
        web = null
        super.onStop()
    }

    companion object {
        const val YOUTUBE = "https://m.youtube.com/"
        const val CLAUDE = "https://claude.ai/"

        /** One setup for the car's page and the phone's sign-in page, so they behave as one browser. */
        @SuppressLint("SetJavaScriptEnabled")
        fun configure(view: WebView) {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = browserUserAgent(userAgentString)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }

        /**
         * The embedded browser names itself with "; wv" and a "Version/4.0"
         * token, and many sites serve it a cut-down page or none. Without
         * them it reads as the phone's ordinary Chrome.
         */
        fun browserUserAgent(webViewUserAgent: String): String =
            webViewUserAgent.replace("; wv)", ")").replace(Regex(" Version/\\d+\\.\\d+"), "")

        /** A typed address made loadable: https unless it says otherwise; null when empty. */
        fun normalizeUrl(typed: String?): String? {
            val t = typed?.trim().orEmpty()
            if (t.isEmpty()) return null
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}
