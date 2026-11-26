English · [Русский](README.ru.md)

# Promo video

The video at the top of the main README is a web page, `promo.html`, rendered frame by frame.

- `promo.html` holds the scenes. Every animation is paused and `window.seek(seconds)` puts the
  page at a moment, so a frame looks the same however long it took to capture. Opened in a
  browser, the page plays in real time; `promo.html?t=12.5` shows one moment.
- `stills/` holds the screens shown in the phones. They are screenshots of the real app, taken on
  an emulator with demo modules and themes, scaled to 540 px wide. `stills/stills.js` lists them.
- `assets/` holds the mascot poses used outside the phones.
- `render.mjs` drives headless Chrome and ffmpeg.

```powershell
node assets/promo/render.mjs                    # out/axiko-promo.mp4, .webp, poster.png
node assets/promo/render.mjs --shots 2,9,15     # out/shot-<t>.png, to look at moments
node assets/promo/render.mjs --prep <folder>    # stills/ from full-size screenshots and their stills.json
```

It needs Node 22, Chrome, ffmpeg and, for `--prep`, ImageMagick. The files the main README shows
are copies of the render: `axiko-promo.webp`, `axiko-promo.mp4` and `owners.png`.
