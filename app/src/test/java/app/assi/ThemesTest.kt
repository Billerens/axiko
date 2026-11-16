package app.assi

import app.assi.core.Themes
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    private fun theme(tokens: String, css: String = "") = JSONObject("""{"name":"T","tokens":$tokens,"css":${JSONObject.quote(css)}}""")

    @Test
    fun `a theme with only an accent colours the mascot from it`() {
        val css = Themes.render("sunset", theme("""{"accent":"#FF7A3D"}"""))
        assertTrue(css.contains("--accent: #FF7A3D;"))
        assertTrue(css.contains("--mascot-gills: var(--accent);"))
        assertTrue(css.contains("--mascot-body: color-mix(in oklab, var(--accent) 38%, white);"))
        // Guarded: a WebView that cannot mix colours must not get a fill it cannot compute.
        assertTrue(css.contains("@supports (color: color-mix(in oklab, red, blue))"))
    }

    @Test
    fun `a theme that colours the mascot itself is left alone`() {
        val css = Themes.render("graphite", theme("""{"accent":"#E6E6E6","mascot-body":"#3A3A3F"}"""))
        assertTrue(css.contains("--mascot-body: #3A3A3F;"))
        assertFalse(css.contains("color-mix"))
    }

    @Test
    fun `a theme without an accent says nothing about the mascot`() {
        val css = Themes.render("round", theme("""{"radius":"24px"}""", ".card { border-radius: 24px; }"))
        assertFalse(css.contains("--mascot-"))
        assertTrue(css.contains(".card { border-radius: 24px; }"))
    }

    @Test
    fun `token values cannot break out of the declaration`() {
        val css = Themes.render("bad", theme("""{"accent":"red; } body { display: none"}"""))
        assertFalse(css.contains("} body"))
    }
}
