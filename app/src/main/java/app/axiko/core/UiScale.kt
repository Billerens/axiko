package app.axiko.core

import app.axiko.App
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
     * What a page runs before anything else, at 100 too: every viewport tag the page brings is
     * rewritten to a width in numbers (a page's own tag would otherwise win), and
     * a page without one gets it. `outerWidth` is the view's width whatever the viewport says;
     * the tag follows it when the view changes (folding, the chat panel beside the screen).
     *
     * The tag names the scale too: `initial-scale` = view / layout width. With a width alone the
     * WebView sometimes keeps the scale it took from the page's own tag (1) — the page is then
     * laid out wider than the view and scrolls sideways. With both, the layout is the width asked
     * for whichever way the scale goes. Without a width the layout falls back to 980 px.
     *
     * The scale is pinned as well (`minimum-scale` = `maximum-scale`): a changed `initial-scale`
     * alone is not taken by a page already shown, and on a turn of the phone the WebView picks a
     * scale of its own to keep the same content in sight — turned back, the page stayed at that
     * scale, larger or smaller than the view and shifted sideways. The view's width can come late
     * after a turn, so the tag is checked again once the turn has settled. A reload keeps the
     * scale the page had, so a page at 100 is pinned too, or it would stay at the scale of before.
     */
    fun script(percent: Int): String {
        val scale = (percent / 100.0).toString()
        return """
            (function () {
              if (window.top !== window) return;
              function want() {
                var view = window.outerWidth || screen.width;
                var width = Math.max(40, Math.round(view / $scale));
                var zoom = (view / width).toFixed(4);
                return 'width=' + width + ', initial-scale=' + zoom + ', minimum-scale=' + zoom + ', maximum-scale=' + zoom +
                  ', viewport-fit=cover';
              }
              function fix() {
                var content = want();
                var list = document.querySelectorAll('meta[name="viewport"]');
                for (var i = 0; i < list.length; i++) if (list[i].getAttribute('content') !== content) list[i].setAttribute('content', content);
                // The page is as wide as the view: nothing is left to the side.
                if (window.scrollX) window.scrollTo(0, window.scrollY);
                return list.length;
              }
              function settle() {
                fix();
                requestAnimationFrame(fix);
                setTimeout(fix, 300);
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
              window.addEventListener('resize', settle);
              if (window.visualViewport) window.visualViewport.addEventListener('resize', fix);
              if (screen.orientation) screen.orientation.addEventListener('change', settle);
            })();
        """.trimIndent()
    }
}
