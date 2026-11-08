# Скачивает новую базовую модель и заново собирает ярлык home-qwen.
# Чаты продолжают звать home-qwen, поэтому имя в интерфейсе не прыгает.
#
# Примеры:
#   .\scripts\update-model.ps1
#   .\scripts\update-model.ps1 -Base qwen3:8b
#   .\scripts\update-model.ps1 -Base qwen3:14b-q4_K_M

param(
    [string]$Base = "",
    # Без этого ключа скрипт не ходит в интернет. Берёт только файл из папки models.
    [switch]$AllowDownload
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
# Локальный .gguf уже лежит в models. Иначе это имя модели на ollama.com.
$local = Join-Path $Root "models\$Base"
if (Test-Path -LiteralPath $local) {
    Write-Host "Беру локальный файл $local"
    $from = ($local -replace '\\', '/')
} elseif ($AllowDownload) {
    Write-Host "Скачиваю $Base"
    & $exe pull $Base
    if ($LASTEXITCODE -ne 0) {
        throw "ollama pull завершился с ошибкой"
    }
    $from = $Base
} else {
    throw "Файл models\$Base не найден. Скачивание выключено, чтобы не тратить трафик."
}

$ctx = Get-ProjectValue "NUM_CTX" "8192"
if ($ctx -notmatch "^[0-9]+$") {
    throw "NUM_CTX должен быть числом"
}

# 8192 токена хватает на обычный разговор и не съедает всю RTX 5070 Ti.
# Полный контекст Qwen3 (40k) вместе с Q8 уже не оставляет запаса под рабочий стол.
$dataDir = Join-Path $Root "data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$modelfile = Join-Path $dataDir "Modelfile"
# У Qwen3 8B в файле не было шаблона чата, поэтому подставляем свой.
# У Qwen3.8 шаблон уже внутри GGUF. Чужой шаблон её ломает.
$useQwen3Template = $Base -like "Huihui-Qwen3-8B*"
if ($useQwen3Template) {
    $template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "qwen3.template"))
    $head = @"
FROM "$from"
PARAMETER num_ctx $ctx
PARAMETER temperature 0.7
TEMPLATE """
"@
    $text = $head + $template.Trim() + "`n" + '"""' + "`n"
} else {
    # Шаблон уже внутри файла Qwen3.8. Свой сюда подставлять нельзя:
    # Ollama читает его как чужой язык и сборка падает.
    $text = @"
FROM "$from"
PARAMETER num_ctx $ctx
PARAMETER temperature 1
PARAMETER top_k 20
PARAMETER top_p 0.95
"@
    $text = $text.Trim() + "`n"
}
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($modelfile, $text, $utf8)

Write-Host "Собираю ярлык $alias"
& $exe create $alias -f $modelfile
if ($LASTEXITCODE -ne 0) {
    throw "ollama create завершился с ошибкой"
}

Set-ProjectValue "BASE_MODEL" $Base

Write-Host "Прогреваю $alias"
Invoke-ModelWarmup -Model $alias
Write-Host "Готово: $alias ($Base)"
