English · [Русский](README.ru.md)

# Mascot source artwork

`mascot/poses/` contains the approved lossless WebP images and JSON tracing specifications used
to produce the shipped SVG mascot. These are build inputs, not discarded design references.
Keep the image named by each JSON `source` field, including images marked `skip` when another
pose uses them as its source.

From the repository root:

```powershell
python scripts/brand/build.py
python scripts/brand/icon.py
python scripts/brand/preview.py build/artifacts/brand/preview
```

The first command writes `app/src/main/assets/factory/ui/mascot/*.svg` and a comparison sheet in
`build/artifacts/brand/poses-sheet.png`. The second rebuilds launcher drawable layers. Both
change shipped assets: inspect the results before committing. The preview uses a stubbed shell
and does not validate native Android layout.

The scripts require Pillow, NumPy, Potracer and Playwright with a supported Chromium browser.
See [mascot contracts](../../docs/brand.md) ([Русский](../../docs/brand.ru.md)) for the runtime
configuration, theme tokens, fallback behavior and asset conventions. Generated comparison
images, rejected variants and historical briefs do not belong in the maintained documentation.
