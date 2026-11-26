[English](README.md) · Русский

# Проморолик

Видео в начале основного README — страница `promo.html`, отрисованная покадрово.

- `promo.html` содержит сцены. Анимации остановлены; `window.seek(seconds)` устанавливает
  нужный момент независимо от времени снятия кадра. В браузере страница проигрывается в реальном
  времени; `promo.html?t=12.5` показывает один момент.
- `stills/` хранит экраны телефонов: снимки приложения на эмуляторе с демонстрационными модулями
  и темами, уменьшенные до ширины 540 px. Список — в `stills/stills.js`.
- `assets/` содержит позы маскота вне телефонов.
- `render.mjs` управляет headless Chrome и ffmpeg.

```powershell
node assets/promo/render.mjs                    # out/axiko-promo.mp4, .webp, poster.png
node assets/promo/render.mjs --shots 2,9,15     # out/shot-<t>.png, отдельные моменты
node assets/promo/render.mjs --prep <folder>    # stills/ из полноразмерных снимков и stills.json
```

Нужны Node 22, Chrome, ffmpeg и для `--prep` — ImageMagick. Файлы, которые показывает основной
README, — копии результата: `axiko-promo.webp`, `axiko-promo.mp4`, `owners.png`.
