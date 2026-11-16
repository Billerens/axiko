# Moves the owner's state from the old install (app.assi) into the renamed one (app.axiko):
# the working folder, data, history of changes, settings and keys. Both must be debug builds
# (run-as works only for them); install the new APK first and open it once.
#   scripts\migrate-from-assi.ps1 [-Serial R5CX…] [-Remove]
# -Remove uninstalls the old app after a successful copy. Without it the old app stays (stopped),
# so nothing is lost if something went wrong; remove it by hand once the new one works.
param(
    [string]$Serial = '',
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = if ($Serial) { @('-s', $Serial) } else { @() }
function Device([string]$command) { & $adb @target shell $command }

$old = 'app.assi'; $new = 'app.axiko'
$packages = Device 'pm list packages'
if (-not ($packages -match "^package:$([regex]::Escape($old))$")) { throw "Старой установки $old на устройстве нет." }
if (-not ($packages -match "^package:$([regex]::Escape($new))$")) { throw "Сначала установите новую сборку ($new) и откройте её один раз." }

# Everything the app keeps in its private folder except caches; the Linux image is large but moves too.
$parts = 'files shared_prefs databases no_backup'
Device "am force-stop $old; am force-stop $new"
Write-Host "Копирую $old -> $new (может занять несколько минут, если стоит Linux или модели)…"
# Both run-as calls run on the device, so the archive never passes through this computer.
# run-as starts in the app's own data folder, so the paths are relative.
Device "run-as $old tar -cf - $parts 2>/dev/null | run-as $new tar -xf -"
# Settings may hold absolute paths (local models, voice packs): point them at the new folder.
Device "run-as $new sh -c 'for f in shared_prefs/*.xml; do sed -i s#/$old/#/$new/#g `$f; done'"

$check = Device "run-as $new ls files/assi" | Select-Object -First 3
if (-not $check) { throw "В новой установке нет рабочей папки — перенос не удался, старая установка не тронута." }
Write-Host "Готово. Рабочая папка на месте: $($check -join ', ')…"
Write-Host 'Заново выдайте Axiko системные разрешения: экран, уведомления, роль ассистента, Shizuku.'
if ($Remove) { & $adb @target uninstall $old | Out-Null; Write-Host "Старая установка $old удалена." }
