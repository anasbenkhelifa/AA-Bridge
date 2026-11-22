package dev.caraalink.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarWebTest {

    @Test
    fun `the car's browser reads as the phone's ordinary Chrome`() {
        val webView = "Mozilla/5.0 (Linux; Android 14; SM-S938B Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; SM-S938B Build/UP1A) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.6723.58 Mobile Safari/537.36",
            CarWebPresentation.browserUserAgent(webView),
        )
    }

    @Test
    fun `a user agent without the embedded markers is left alone`() {
        val chrome = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        assertEquals(chrome, CarWebPresentation.browserUserAgent(chrome))
    }

    @Test
    fun `a typed address gets https unless it names its own scheme`() {
        assertEquals("https://tarteel.ai", CarWebPresentation.normalizeUrl(" tarteel.ai "))
        assertEquals("http://192.168.1.4:8080/", CarWebPresentation.normalizeUrl("http://192.168.1.4:8080/"))
        assertEquals("https://example.org/a", CarWebPresentation.normalizeUrl("https://example.org/a"))
        assertNull(CarWebPresentation.normalizeUrl("  "))
        assertNull(CarWebPresentation.normalizeUrl(null))
    }

    @Test
    fun `every screen has Home, and Back above it only where there is one`() {
        assertEquals(listOf(CarNav.HOME), CarNav.buttons({}, null).map { it.first })
        assertEquals(listOf(CarNav.BACK, CarNav.HOME), CarNav.buttons({}, {}).map { it.first })

        var went = ""
        val buttons = CarNav.buttons({ went += "home" }, { went += "back" })
        buttons[0].second()
        buttons[1].second()
        assertEquals("backhome", went)
    }

    @Test
    fun `extra web apps are typed as name and address pairs`() {
        val pages = CarWebPresentation.parsePages("Tarteel=tarteel.ai, quran.com ,WhatsApp = https://web.whatsapp.com/, =, Broken=")

        assertEquals(listOf("Tarteel", "Quran", "WhatsApp"), pages.map { it.name })
        assertEquals(listOf("https://tarteel.ai", "https://quran.com", "https://web.whatsapp.com/"), pages.map { it.url })
        assertEquals(emptyList<Any>(), CarWebPresentation.parsePages(null))
    }

    @Test
    fun `a site is named after its address, and matched to its app for the icon`() {
        assertEquals("Youtube", CarWebPresentation.siteName("https://m.youtube.com/"))
        assertEquals("Whatsapp", CarWebPresentation.siteName("https://web.whatsapp.com/x"))
        assertEquals("com.google.android.youtube", CarWebPresentation.appFor(CarWebPresentation.YOUTUBE))
        assertEquals("com.anthropic.claude", CarWebPresentation.appFor(CarWebPresentation.CLAUDE))
        assertNull(CarWebPresentation.appFor("https://notyoutube.com.example.org/"))
        assertNull(CarWebPresentation.appFor("https://example.org/"))
    }

    @Test
    fun `typed text reaches the page as a safe string literal`() {
        assertEquals("document.execCommand('insertText',false,\"a\")", CarWebPresentation.insertTextScript("a"))
        assertEquals("\"\\\"\\\\\"", CarWebPresentation.jsString("\"\\"))
        assertEquals("\"\\u003c/script>\"", CarWebPresentation.jsString("</script>"))
        assertEquals("\"ع\"", CarWebPresentation.jsString("ع"))
    }

    @Test
    fun `the keyboard has English with shift, Arabic, and numbers with symbols`() {
        val english = CarKeyboard.rows(CarKeyboard.Layout.ENGLISH, shift = false)
        assertEquals(listOf(10, 9, 7), english.map { it.size })
        assertEquals("Q", CarKeyboard.rows(CarKeyboard.Layout.ENGLISH, shift = true)[0][0])
        val arabic = CarKeyboard.rows(CarKeyboard.Layout.ARABIC, shift = false).flatten()
        assertEquals(arabic.size, arabic.toSet().size)
        for (letter in "ابتثجحخدذرزسشصضطظعغفقكلمنهوي") assertEquals(true, letter.toString() in arabic)
        val symbols = CarKeyboard.rows(CarKeyboard.Layout.SYMBOLS, shift = false).flatten()
        for (c in "0123456789@.?") assertEquals(true, c.toString() in symbols)
    }

    @Test
    fun `a drawn icon keeps one colour blend per name, and the grid grows a column past eight`() {
        assertEquals(CarArt.blendFor("Tarteel").toList(), CarArt.blendFor("Tarteel").toList())
        assertEquals(4, CarHomePresentation.columnsFor(7))
        assertEquals(4, CarHomePresentation.columnsFor(8))
        assertEquals(5, CarHomePresentation.columnsFor(9))
    }
}
