# Saves a screenshot of the device to a file.
#   scripts\shot.ps1 out.png [-Serial emulator-5554]
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Serial = 'emulator-5554'
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s $Serial shell screencap -p /sdcard/axiko-shot.png
& $adb -s $Serial pull /sdcard/axiko-shot.png $Out | Out-Null
