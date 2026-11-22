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
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) = v.evaluateJavascript(FOCUS_SCRIPT, null)
        }
        // The page tells us when a text box has the cursor; that is all it can call.
        view.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun editing(on: Boolean) {
                main.post { showKeyboard(on) }
            }
        }, "AABridge")
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
        // The keyboard sits under the page rather than over it, so the page reflows and the box being typed in stays in view.
        keyboard = CarKeyboard(
            context,
            onText = { text -> js(insertTextScript(text)) },
            onBackspace = { js(DELETE_SCRIPT) },
            onEnter = { js(ENTER_SCRIPT) },
            onHide = { showKeyboard(false) },
        ).apply { visibility = View.GONE }
        setContentView(android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(root, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(keyboard, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        view.loadUrl(url)
    }

    private lateinit var keyboard: CarKeyboard

    private fun showKeyboard(show: Boolean) {
        keyboard.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun js(script: String) {
        web?.evaluateJavascript(script, null)
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

        /** Reports when a text box gains or loses the cursor. Installed once per page. */
        const val FOCUS_SCRIPT = """(function(){if(window.__aab)return;window.__aab=1;
function ed(e){if(!e)return false;var t=e.tagName;if(t==='TEXTAREA')return true;
if(t==='INPUT'){var k=(e.type||'text').toLowerCase();return ['text','search','email','url','tel','password','number'].indexOf(k)>=0;}
return !!e.isContentEditable;}
document.addEventListener('focusin',function(ev){if(ed(ev.target))AABridge.editing(true);},true);
document.addEventListener('click',function(ev){if(ed(ev.target))AABridge.editing(true);},true);
document.addEventListener('focusout',function(){setTimeout(function(){if(!ed(document.activeElement))AABridge.editing(false);},200);},true);
if(ed(document.activeElement))AABridge.editing(true);})()"""

        /** Deletes the character before the cursor, as the Backspace key would. */
        const val DELETE_SCRIPT = "document.execCommand('delete',false,null)"

        /** Enter: the key events a page listens for, then the form's own submit for pages that only listen to that. */
        const val ENTER_SCRIPT = """(function(){var e=document.activeElement;if(!e)return;
var keep=['keydown','keypress','keyup'].map(function(t){return e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});
if(keep[0]&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}})()"""

        /** Types [text] at the cursor the way the page's own input would, so the page sees a real edit. */
        fun insertTextScript(text: String): String = "document.execCommand('insertText',false,${jsString(text)})"

        /** [text] as a JavaScript string literal. */
        fun jsString(text: String): String = buildString {
            append('"')
            for (c in text) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' || c == '\u2028' || c == '\u2029' || c == '<' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

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

        /** A page the home screen can open: its name and address. */
        data class Page(val name: String, val url: String)

        /**
         * The extra pages from Advanced, typed as `Tarteel=tarteel.ai, Quran=quran.com`.
         * A bare address is named after its site. Entries without an address are skipped.
         */
        fun parsePages(typed: String?): List<Page> = typed.orEmpty().split(',', '\n').mapNotNull { entry ->
            val name = entry.substringBefore('=', "").trim()
            val url = normalizeUrl(entry.substringAfter('=')) ?: return@mapNotNull null
            Page(name.ifEmpty { siteName(url) }, url)
        }

        /** "tarteel.ai" becomes "Tarteel": the site's name without www or its ending. */
        fun siteName(url: String): String {
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").removePrefix("m.")
            return host.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifEmpty { host }
        }

        /** The installed app whose icon stands for a site on the home screen, if we know one. */
        fun appFor(url: String): String? {
            val host = url.substringAfter("://").substringBefore('/')
            return SITE_APPS.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value
        }

        private val SITE_APPS = mapOf(
            "youtube.com" to "com.google.android.youtube",
            "claude.ai" to "com.anthropic.claude",
            "whatsapp.com" to "com.whatsapp",
            "tarteel.ai" to "com.mmmoussa.iqra",
            "quran.com" to "com.quran.labs.androidquran",
            "spotify.com" to "com.spotify.music",
            "netflix.com" to "com.netflix.mediaclient",
            "telegram.org" to "org.telegram.messenger",
        )

        /** A typed address made loadable: https unless it says otherwise; null when empty. */
        fun normalizeUrl(typed: String?): String? {
            val t = typed?.trim().orEmpty()
            if (t.isEmpty()) return null
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}
