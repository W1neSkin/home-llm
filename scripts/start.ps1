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

if (-not (Wait-Docker -Seconds 300)) {
    Write-Log "Docker Desktop не готов"
    exit 1
}

docker compose up -d
if ($LASTEXITCODE -ne 0) {
    Write-Log "docker compose up не удался"
    exit 1
}
Write-Log "сайт запущен"

# При входе в Windows веса уже на диске. Здесь только загрузка в видеокарту.
# Скачивание новой версии делается отдельно скриптом update-model.ps1.
try {
    Invoke-ModelWarmup -Model (Get-ProjectValue "MODEL" "home-qwen")
    Write-Log "модель в памяти"
} catch {
    Write-Log "прогрев модели не удался: $($_.Exception.Message)"
}

$token = Get-ProjectValue "TELEGRAM_BOT_TOKEN"
$allowed = Get-ProjectValue "TELEGRAM_ALLOWED_IDS"
if ($token -and $allowed) {
    try {
        $already = Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" |
            Where-Object { $_.CommandLine -like "*telegram\bot.py*" }
        if (-not $already) {
            # stdout и stderr нельзя писать в один файл: Windows не даёт открыть его дважды.
            $botLog = Join-Path $Root "data\telegram.log"
            Start-Process -FilePath "py" -ArgumentList @("-3", "telegram\bot.py") `
                -WorkingDirectory $Root -WindowStyle Hidden `
                -RedirectStandardOutput $botLog -RedirectStandardError "$botLog.err"
            Write-Log "телеграм-бот запущен"
        } else {
            Write-Log "телеграм-бот уже работает"
        }
    } catch {
        Write-Log "телеграм-бот не запустился: $($_.Exception.Message)"
    }
} else {
    Write-Log "телеграм-бот выключен: нет токена или списка id"
}

$port = Get-ProjectValue "WEBUI_PORT" "8080"
$lan = Get-LanAddress
Write-Log "этот компьютер: http://127.0.0.1:$port"
Write-Log "домашняя сеть: http://${lan}:$port"
