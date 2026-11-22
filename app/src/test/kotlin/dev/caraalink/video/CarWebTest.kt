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
}
