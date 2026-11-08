# Общие функции. Другие скрипты подключают файл так:
#   . "$PSScriptRoot\common.ps1"

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Import-ProjectEnv {
    $example = Join-Path $Root ".env.example"
    $path = Join-Path $Root ".env"
    # Рабочий .env не в git. При первом запуске копируем образец.
    if (-not (Test-Path $path)) {
        Copy-Item $example $path
    }

    $script:ProjectEnv = @{}
    foreach ($line in Get-Content -Path $path -Encoding UTF8) {
        $trim = $line.Trim()
        if (-not $trim -or $trim.StartsWith("#") -or -not $trim.Contains("=")) {
            continue
        }
        $key, $value = $trim.Split("=", 2)
        $script:ProjectEnv[$key.Trim()] = $value.Trim()
    }
}

function Get-ProjectValue {
    param(
        [string]$Name,
        [string]$Default = ""
    )
    if ($script:ProjectEnv.ContainsKey($Name) -and $script:ProjectEnv[$Name]) {
        return $script:ProjectEnv[$Name]
    }
    return $Default
}

function Set-ProjectValue {
    param(
        [string]$Name,
        [string]$Value
    )
    # Меняем одну строку .env и не трогаем комментарии вокруг.
    $path = Join-Path $Root ".env"
    $lines = @(Get-Content -Path $path -Encoding UTF8)
    $found = $false
    $updated = foreach ($line in $lines) {
        if ($line -match ("^\s*" + [regex]::Escape($Name) + "=")) {
            $found = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $found) {
        $updated += "$Name=$Value"
    }
    [System.IO.File]::WriteAllLines($path, $updated)
    $script:ProjectEnv[$Name] = $Value
}

function Set-UserEnv {
    param(
        [string]$Name,
        [string]$Value
    )
    # HKCU\Environment читается при следующем входе в Windows.
    # Текущий процесс обновляем сразу, чтобы дочерний Ollama увидел значение.
    [Environment]::SetEnvironmentVariable($Name, $Value, "User")
    Set-Item -Path "Env:$Name" -Value $Value
}

function Get-OllamaExe {
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe",
        "$env:ProgramFiles\Ollama\ollama.exe"
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) {
            return $path
        }
    }
    $cmd = Get-Command ollama -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    return $null
}

function Test-OllamaApi {
    try {
        Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Wait-Ollama {
    param([int]$Seconds = 40)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-OllamaApi) {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-OllamaServe {
    # Запасной запуск, если значок Ollama сам не поднялся.
    # Веса лежат в папке проекта, контекст ограничен, в видеокарте одна модель.
    $models = Join-Path $Root "models"
    New-Item -ItemType Directory -Force -Path $models | Out-Null
    Set-UserEnv "OLLAMA_MODELS" $models
    Set-UserEnv "OLLAMA_HOST" "127.0.0.1:11434"
    Set-UserEnv "OLLAMA_KEEP_ALIVE" "-1"
    Set-UserEnv "OLLAMA_CONTEXT_LENGTH" "8192"
    Set-UserEnv "OLLAMA_MAX_LOADED_MODELS" "1"

    $exe = Get-OllamaExe
    if (-not $exe) {
        throw "Ollama не установлен."
    }
    Start-Process -FilePath $exe -ArgumentList "serve" -WindowStyle Hidden | Out-Null
}

function Wait-Docker {
    param([int]$Seconds = 300)
    # Docker Desktop после входа в систему стартует не сразу.
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        docker info 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) {
            return $true
        }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Invoke-ModelWarmup {
    param([string]$Model)
    # Один токен ответа достаточен, чтобы прочитать веса в видеопамять.
    # keep_alive -1 оставляёт их там после этого запроса.
    $payload = @{
        model      = $Model
        prompt     = "ok"
        stream     = $false
        keep_alive = -1
        options    = @{ num_predict = 1 }
    } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:11434/api/generate" `
        -Body $payload -ContentType "application/json" -TimeoutSec 900 | Out-Null
}

function Get-LanAddress {
    # Нужен адрес домашней сети, не VPN и не виртуальные адаптеры.
    $nets = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.PrefixOrigin -ne "WellKnown" -and
            $_.InterfaceAlias -notmatch "vEthernet|WSL|Wintun|Loopback|Default Switch"
        }
    $lan = $nets | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } |
        Select-Object -First 1
    if ($lan) {
        return $lan.IPAddress
    }
    return "192.168.?.?"
}
