# Поднимает модель и сайт. Это же делает автозапуск при входе в Windows.
# Повторный запуск безопасен: уже работающие части он не ломает.

. "$PSScriptRoot\common.ps1"
Set-Location $Root
Import-ProjectEnv

New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null
$logPath = Join-Path $Root "data\start.log"

function Write-Log {
    param([string]$Message)
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "старт"
& "$PSScriptRoot\run-phone.ps1"

# Файл data\paused создаёт значок в трее. Пока он есть, модель не поднимается.
$pauseFlag = Join-Path $Root "data\paused"
if (Test-Path $pauseFlag) {
    Get-Process | Where-Object { $_.ProcessName -like "ollama*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Log "на паузе, модель не запускаю"
    exit 0
}

# Значок Ollama из автозагрузки обычно успевает подняться сам.
if (-not (Wait-Ollama -Seconds 45)) {
    Write-Log "Ollama молчит, запускаю serve"
    Start-OllamaServe
    if (-not (Wait-Ollama -Seconds 40)) {
        Write-Log "Ollama не запустилась"
        exit 1
    }
}
Write-Log "Ollama отвечает"

# Сайт не обязателен для модели. Ошибка Docker не должна мешать запуску Qwen.
# Образ скачивается только при HOME_LLM_PULL_WEB=1, иначе лишь запускаем уже скачанный.
try {
    $webReady = $env:HOME_LLM_PULL_WEB -eq "1"
    if (-not $webReady -and (Get-Command docker -ErrorAction SilentlyContinue)) {
        docker image inspect ghcr.io/open-webui/open-webui:main 1>$null 2>$null
        $webReady = ($LASTEXITCODE -eq 0)
    }
    if ($webReady -and (Wait-Docker -Seconds 60)) {
        docker compose up -d
        if ($LASTEXITCODE -eq 0) { Write-Log "сайт запущен" } else { Write-Log "сайт не запустился" }
    } else {
        Write-Log "сайт не трогаю: образ ещё не скачан"
    }
} catch {
    Write-Log "сайт пропущен: $($_.Exception.Message)"
}

# Ярлык home-qwen собирается из локального файла. В интернет этот шаг не ходит.
try {
    $model = Get-ProjectValue "MODEL" "home-qwen"
    # Сообщение ollama в stderr не должно обрывать скрипт: модели ещё может не быть.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & (Get-OllamaExe) show $model 1>$null 2>$null
    $missing = $LASTEXITCODE -ne 0
    $ErrorActionPreference = $prev
    if ($missing) {
        Write-Log "собираю модель из локального файла"
        & "$PSScriptRoot\update-model.ps1"
    }
    Invoke-ModelWarmup -Model $model
    Write-Log "модель в памяти"
} catch {
    Write-Log "прогрев модели не удался: $($_.Exception.Message)"
}

if (Start-TelegramBot) {
    Write-Log "телеграм-бот запущен"
} else {
    Write-Log "телеграм-бот выключен: нет токена или списка id"
}

$port = Get-ProjectValue "WEBUI_PORT" "8787"
$lan = Get-LanAddress
Write-Log "этот компьютер: http://127.0.0.1:$port"
Write-Log "домашняя сеть: http://${lan}:$port"
