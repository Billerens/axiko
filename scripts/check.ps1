# Runs the on-device checks (scripts\checks.js) in the app's engine and prints what passed.
#   scripts\check.ps1 [-Serial emulator-5554] [-Only "local model"] [-WaitSeconds 600]
# The app must be installed and running. Checks that need a model which is not downloaded are skipped.
param(
    [string]$Serial = 'emulator-5554',
    [string]$Only = '',
    [int]$WaitSeconds = 600
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$code = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'checks.js'), [Text.Encoding]::UTF8)
$file = Join-Path ([IO.Path]::GetTempPath()) ("assi-checks-" + [guid]::NewGuid().ToString('N') + '.js')
[IO.File]::WriteAllText($file, "var ONLY = " + (ConvertTo-Json $Only) + ";`n" + $code, [Text.UTF8Encoding]::new($false))
# Another app's chat notification for the listener to hear: the shell posts one as a messenger would.
& $adb -s $Serial shell "cmd notification post -t 'Axiko check' -S messaging --conversation Family --message 'Anna:Hello there' --message ':Hi' assi-check 'Axiko check'" | Out-Null
try {
    & (Join-Path $PSScriptRoot 'eval.ps1') $file -Serial $Serial -WaitSeconds $WaitSeconds | Where-Object { $_ -notmatch 'file pushed|Activity not started' }
} finally {
    Remove-Item $file -Force
    & $adb -s $Serial shell rm -f /data/local/tmp/assi-eval.js
}
