# Runs a JavaScript file in the app's engine on a device and prints the result.
#   scripts\eval.ps1 path\to\code.js [-Serial emulator-5554] [-WaitSeconds 20]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$Serial = 'emulator-5554',
    [int]$WaitSeconds = 20
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s $Serial push $File /data/local/tmp/assi-eval.js | Out-Null
& $adb -s $Serial logcat -c
$loader = "(0,eval)(String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get('/data/local/tmp/assi-eval.js')),'UTF-8')))"
& $adb -s $Serial shell am start --user 0 -n app.assi/.MainActivity --ei evalTimeout $WaitSeconds --es eval "`"$loader`"" | Out-Null

$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    Start-Sleep -Milliseconds 700
    $lines = & $adb -s $Serial logcat -d -v raw -s AssiEval:*
} until (($lines -match '<<end>>') -or (Get-Date) -gt $deadline)
$lines | Where-Object { $_ -and $_ -notmatch '^-+ beginning' -and $_ -ne '<<end>>' }
