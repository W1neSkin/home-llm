# Скачивает новую базовую модель и заново собирает ярлык home-qwen.
# Чаты продолжают звать home-qwen, поэтому имя в интерфейсе не прыгает.
#
# Примеры:
#   .\scripts\update-model.ps1
#   .\scripts\update-model.ps1 -Base qwen3:8b
#   .\scripts\update-model.ps1 -Base qwen3:14b-q4_K_M

param(
    [string]$Base = ""
)

. "$PSScriptRoot\common.ps1"
Set-Location $Root
Import-ProjectEnv

if (-not $Base) {
    $Base = Get-ProjectValue "BASE_MODEL" "qwen3:8b-q8_0"
}
# Имя уходит в команду ollama. Пускаем только обычные символы тега.
if ($Base -notmatch "^[A-Za-z0-9_.:/-]+$") {
    throw "Некорректное имя модели: $Base"
}

$alias = Get-ProjectValue "MODEL" "home-qwen"
if ($alias -notmatch "^[A-Za-z0-9_.:/-]+$") {
    throw "Некорректное имя MODEL: $alias"
}

if (-not (Wait-Ollama -Seconds 5)) {
    Start-OllamaServe
}
if (-not (Wait-Ollama -Seconds 40)) {
    throw "Ollama не отвечает на 127.0.0.1:11434"
}

$exe = Get-OllamaExe
Write-Host "Скачиваю $Base"
& $exe pull $Base
if ($LASTEXITCODE -ne 0) {
    throw "ollama pull завершился с ошибкой"
}

# 8192 токена хватает на обычный разговор и не съедает всю RTX 5070 Ti.
# Полный контекст Qwen3 (40k) вместе с Q8 уже не оставляет запаса под рабочий стол.
$dataDir = Join-Path $Root "data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$modelfile = Join-Path $dataDir "Modelfile"
$modelText = @"
FROM $Base
PARAMETER num_ctx 8192
PARAMETER temperature 0.7
"@
[System.IO.File]::WriteAllText($modelfile, $modelText.Trim() + "`n")

Write-Host "Собираю ярлык $alias"
& $exe create $alias -f $modelfile
if ($LASTEXITCODE -ne 0) {
    throw "ollama create завершился с ошибкой"
}

Set-ProjectValue "BASE_MODEL" $Base

Write-Host "Прогреваю $alias"
Invoke-ModelWarmup -Model $alias
Write-Host "Готово: $alias ($Base)"
