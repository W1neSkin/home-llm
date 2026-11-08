# Поднимает дверь для телефона. Ключ создаётся один раз и лежит в .env.
# Повторный запуск не плодит вторую копию.

. "$PSScriptRoot\common.ps1"
Set-Location $Root
Import-ProjectEnv

if (-not (Get-ProjectValue "PHONE_TOKEN")) {
    $alphabet = (48..57) + (65..90) + (97..122)
    $token = -join ($alphabet | Get-Random -Count 20 | ForEach-Object { [char]$_ })
    Set-ProjectValue "PHONE_TOKEN" $token
}
if (-not (Get-ProjectValue "PHONE_PORT")) {
    Set-ProjectValue "PHONE_PORT" "8765"
}

$already = Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*scripts\phone.py*" }
if (-not $already) {
    Start-Process -FilePath "py" -ArgumentList @("-3", "scripts\phone.py") `
        -WorkingDirectory $Root -WindowStyle Hidden
}

$quiet = Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*scripts\ollama-quiet.py*" }
if (-not $quiet) {
    Start-Process -FilePath "py" -ArgumentList @("-3", "scripts\ollama-quiet.py") `
        -WorkingDirectory $Root -WindowStyle Hidden
}

# Телефон в домашней сети. Без прав администратора правило не создастся, локальный чат при этом жив.
$port = Get-ProjectValue "PHONE_PORT" "8765"
$rule = "LocAI phone $port"
$oldRule = "Lamp phone $port"
try {
    # Старое правило с прежним именем уже открывает порт. Второе без прав администратора не создастся.
    $haveRule = Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue
    $haveOld = Get-NetFirewallRule -DisplayName $oldRule -ErrorAction SilentlyContinue
    if (-not $haveRule -and -not $haveOld) {
        New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol TCP `
            -LocalPort $port -Action Allow -Profile Private | Out-Null
    }
} catch {}
