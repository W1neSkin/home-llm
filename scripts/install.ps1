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

if (-not (Get-OllamaExe)) {
    Write-Host "Ставлю Ollama"
    winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements
    if (-not (Get-OllamaExe)) {
        throw "Ollama не появилась в $env:LOCALAPPDATA\Programs\Ollama"
    }
}

# Установщик сам запускает Ollama со старым окружением. Гасим и поднимаем заново.
Get-Process | Where-Object { $_.ProcessName -like "ollama*" } | Stop-Process -Force
Start-Sleep -Seconds 2
Start-OllamaServe
if (-not (Wait-Ollama -Seconds 40)) {
    throw "Ollama не отвечает после запуска"
}

& "$PSScriptRoot\update-model.ps1"

$port = Get-ProjectValue "WEBUI_PORT" "8080"
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

if (-not (Wait-Docker -Seconds 120)) {
    throw "Docker Desktop не запущен"
}

docker compose up -d
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up не удался"
}

& "$PSScriptRoot\autostart.ps1"

$lan = Get-LanAddress
Write-Host ""
Write-Host "Готово."
Write-Host "Этот компьютер: http://127.0.0.1:$port"
Write-Host "Домашняя сеть:  http://${lan}:$port"
Write-Host "Первый аккаунт на сайте станет администратором."
