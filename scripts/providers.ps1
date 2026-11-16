# Puts the model providers from .env into the app on a device, next to the ones already there.
#   scripts\providers.ps1 [-Serial emulator-5554]
# .env holds pairs  NAME_BASEURL=https://…/v1  and  NAME_TOKEN=…  (the file is not in git).
# Keys go straight to the app's settings: they are not printed and nothing is left on the device.
param([string]$Serial = 'emulator-5554')

$root = Split-Path $PSScriptRoot -Parent
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$names = @{ 'openrouter.ai' = 'OpenRouter'; 'api.openai.com' = 'OpenAI' }

$values = @{}
foreach ($line in Get-Content (Join-Path $root '.env')) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $values[$Matches[1]] = $Matches[2].Trim().Trim('"', "'") }
}
$found = @()
foreach ($key in $values.Keys | Where-Object { $_ -match '_BASEURL$' } | Sort-Object) {
    $id = ($key -replace '_BASEURL$', '').ToLower()
    $url = $values[$key].TrimEnd('/')
    $token = $values[($key -replace '_BASEURL$', '_TOKEN')]
    if (-not $url) { continue }
    $hostName = ([uri]$url).Host
    $found += @{ id = $id; name = $(if ($names[$hostName]) { $names[$hostName] } else { $hostName }); baseUrl = $url; key = "$token" }
}
if (-not $found) { Write-Output 'В .env нет пар NAME_BASEURL / NAME_TOKEN'; exit 1 }

$code = @"
var wanted = $(ConvertTo-Json @($found) -Compress);
var all = native('models.providers');
wanted.forEach(function (provider) {
  var at = -1;
  all.forEach(function (item, index) { if (item.id === provider.id) at = index; });
  if (at >= 0) all[at] = provider; else all.push(provider);
});
native('models.setProviders', all);
all.map(function (item) { return item.name + ' (' + item.baseUrl + ')' + (item.key ? ', ключ задан' : ', без ключа'); }).join('\n');
"@
$file = Join-Path ([IO.Path]::GetTempPath()) ("assi-providers-" + [guid]::NewGuid().ToString('N') + '.js')
[IO.File]::WriteAllText($file, $code, [Text.UTF8Encoding]::new($false))
try {
    & (Join-Path $PSScriptRoot 'eval.ps1') $file -Serial $Serial -WaitSeconds 25
} finally {
    Remove-Item $file -Force
    & $adb -s $Serial shell rm -f /data/local/tmp/assi-eval.js
}
