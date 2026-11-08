# Первая установка: Ollama, модель, сайт, правило брандмауэра, автозапуск.
# Скрипт можно запустить ещё раз. Он перезапускает Ollama, чтобы веса
# гарантированно легли в папку models этого проекта, а не на диск C:.

. "$PSScriptRoot\common.ps1"
Set-Location $Root
Import-ProjectEnv

$models = Join-Path $Root "models"
New-Item -ItemType Directory -Force -Path $models, (Join-Path $Root "data") | Out-Null

# Эти переменные подхватит значок Ollama при следующем входе в систему.
Set-UserEnv "OLLAMA_MODELS" $models
Set-UserEnv "OLLAMA_HOST" "127.0.0.1:11434"
Set-UserEnv "OLLAMA_KEEP_ALIVE" "-1"
Set-UserEnv "OLLAMA_CONTEXT_LENGTH" "8192"
Set-UserEnv "OLLAMA_MAX_LOADED_MODELS" "1"

$setup = Join-Path $Root "data\OllamaSetup.exe"
# Уже скачанный установщик больше 100 МБ. Мелкий файл — это обрывок ошибки, не программа.
$setupReady = (Test-Path -LiteralPath $setup) -and ((Get-Item -LiteralPath $setup).Length -gt 100MB)
if (-not (Get-OllamaExe) -and -not $setupReady) {
    Write-Host "Ставлю Ollama"
    # winget иногда получает 504 от GitHub. Прямая загрузка с повторами это переживает.
    winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements
}
if (-not (Get-OllamaExe)) {
    if (-not $setupReady) {
        Write-Host "Качаю установщик напрямую"
        curl.exe -L --retry 5 --retry-delay 3 --retry-all-errors -o $setup "https://github.com/ollama/ollama/releases/latest/download/OllamaSetup.exe"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $setup)) {
            throw "Не удалось скачать OllamaSetup.exe"
        }
    } else {
        Write-Host "Ставлю Ollama из уже скачанного файла"
    }
    # Тихая установка Inno Setup в профиль пользователя, без перезагрузки.
    $installer = Start-Process -FilePath $setup -ArgumentList "/VERYSILENT", "/NORESTART", "/SP-" -Wait -PassThru
    if ($installer.ExitCode -ne 0) {
        throw "Установщик Ollama завершился с кодом $($installer.ExitCode)"
    }
}
if (-not (Get-OllamaExe)) {
    throw "Ollama не появилась в $env:LOCALAPPDATA\Programs\Ollama"
}

# Установщик сам запускает Ollama со старым окружением. Гасим и поднимаем заново.
Get-Process | Where-Object { $_.ProcessName -like "ollama*" } | Stop-Process -Force
Start-Sleep -Seconds 2
Start-OllamaServe
if (-not (Wait-Ollama -Seconds 40)) {
    throw "Ollama не отвечает после запуска"
}

& "$PSScriptRoot\update-model.ps1"

$port = Get-ProjectValue "WEBUI_PORT" "8787"
$ruleName = "Home LLM web $port"
try {
    $exists = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if (-not $exists) {
        # Только частный профиль. Гостевая и публичная сеть правило не получают.
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP `
            -LocalPort $port -Action Allow -Profile Private | Out-Null
        Write-Host "Брандмауэр: открыт порт $port для частной сети"
    } else {
        Write-Host "Брандмауэр: правило уже есть"
    }
} catch {
    Write-Host "Брандмауэр не изменён. Если телефон не откроет сайт, разрешите входящий TCP $port для частной сети."
    Write-Host $_.Exception.Message
}

# Образ сайта весит около 1.6 ГБ. Сам он не качается: только если явно разрешить.
#   $env:HOME_LLM_PULL_WEB = "1"; .\scripts\install.ps1
if ($env:HOME_LLM_PULL_WEB -eq "1") {
    if (-not (Wait-Docker -Seconds 120)) {
        throw "Docker Desktop не запущен"
    }
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up не удался"
    }
} else {
    Write-Host "Сайт не скачиваю. Когда VPN выключен, запустите install.ps1 с HOME_LLM_PULL_WEB=1"
}

& "$PSScriptRoot\autostart.ps1"

$lan = Get-LanAddress
Write-Host ""
Write-Host "Готово."
Write-Host "Этот компьютер: http://127.0.0.1:$port"
Write-Host "Домашняя сеть:  http://${lan}:$port"
Write-Host "Первый аккаунт на сайте станет администратором."
