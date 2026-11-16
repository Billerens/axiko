package app.assi.core

import app.assi.App
import org.json.JSONObject

/**
 * The owner's scale of the whole interface: text, buttons and gaps grow or shrink together, as
 * if the phone had another screen density. Kept apart for a narrow screen and a wide one, because
 * the cover of a folding phone and its inner screen rarely want the same.
 *
 * A page is scaled through its viewport: it is laid out `1/S` of its view wide and the view
 * shows it `S` times larger, so the page's own breakpoints and sizes stay true to what the
 * owner sees. The shell's own parts multiply their sizes by the same number.
 */
object UiScale {
    const val MIN = 80
    const val MAX = 130
    const val STEP = 5

    private fun key(wide: Boolean) = if (wide) "ui.scale.wide" else "ui.scale"

    /** Per cent, 100 for "as designed". [wide] is the screen as the phone reports it, before any scale. */
    fun percent(wide: Boolean): Int = App.prefs.getInt(key(wide), 100).coerceIn(MIN, MAX)

    fun set(wide: Boolean, percent: Int): Int {
        val value = (percent.coerceIn(MIN, MAX) + STEP / 2) / STEP * STEP
        App.prefs.edit().putInt(key(wide), value).apply()
        return value
    }

    fun info(wide: Boolean): JSONObject =
        JSONObject().put("value", percent(wide)).put("min", MIN).put("max", MAX).put("step", STEP).put("wide", wide)

    /**
     * What a page runs before anything else when the scale is not 100: every viewport tag the
     * page brings is rewritten to a width in numbers (a page's own tag would otherwise win), and
     * a page without one gets it. A width in numbers is the one form this WebView lays out right
     * in both directions: "initial-scale" above 1 keeps the layout as wide as the view and cuts
     * it, and without a width the layout falls back to 980 px. `outerWidth` is the view's width
     * whatever the viewport says; the tag follows it when the view changes (folding, the chat
     * panel beside the screen).
     */
    fun script(percent: Int): String? {
        if (percent == 100) return null
        val scale = (percent / 100.0).toString()
        return """
            (function () {
              if (window.top !== window) return;
              function want() {
                return 'width=' + Math.max(40, Math.round((window.outerWidth || screen.width) / $scale)) + ', viewport-fit=cover';
              }
              function fix() {
                var content = want();
                var list = document.querySelectorAll('meta[name="viewport"]');
                for (var i = 0; i < list.length; i++) if (list[i].getAttribute('content') !== content) list[i].setAttribute('content', content);
                return list.length;
              }
              var watch = new MutationObserver(fix);
              watch.observe(document, { childList: true, subtree: true });
              document.addEventListener('DOMContentLoaded', function () {
                watch.disconnect();
                if (!fix() && document.head) {
                  var meta = document.createElement('meta');
                  meta.name = 'viewport';
                  meta.content = want();
                  document.head.appendChild(meta);
                }
              });
              window.addEventListener('resize', fix);
            })();
        """.trimIndent()
    }
}
