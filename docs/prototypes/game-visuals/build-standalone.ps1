$ErrorActionPreference = 'Stop'
# Run with PowerShell 7. This packages the gallery without touching Axiko assets.
$galleryRoot = $PSScriptRoot
$html = Get-Content -LiteralPath (Join-Path $galleryRoot 'index.html') -Raw -Encoding utf8
$css = Get-Content -LiteralPath (Join-Path $galleryRoot 'gallery.css') -Raw -Encoding utf8
$html = $html.Replace('<link rel="stylesheet" href="gallery.css">', "<style>`n$css`n</style>")
$scripts = @('registry.js', 'scenes-worlds.js', 'scenes-combat.js', 'scenes-systems.js', 'scenes-sparks.js', 'gallery.js')
foreach ($scriptName in $scripts) {
    $source = Get-Content -LiteralPath (Join-Path $galleryRoot $scriptName) -Raw -Encoding utf8
    $source = $source.Replace('</script', '<\/script')
    $html = $html.Replace('<script src="' + $scriptName + '"></script>', "<script>`n$source`n</script>")
}
$outputPath = Join-Path $galleryRoot 'standalone.html'
Set-Content -LiteralPath $outputPath -Value $html -Encoding utf8
Get-Item -LiteralPath $outputPath | Select-Object FullName, Length
