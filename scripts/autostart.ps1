# Задача планировщика: через полминуты после входа в Windows запустить start.ps1.
# Полминуты нужны, чтобы успели подняться Ollama и Docker Desktop.
# Повторный запуск этого скрипта просто перезаписывает задачу.

. "$PSScriptRoot\common.ps1"

$startScript = Join-Path $PSScriptRoot "start.ps1"
$action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$startScript`"" `
    -WorkingDirectory $Root

$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 20)

Register-ScheduledTask `
    -TaskName "HomeLLM" `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Локальная Qwen и веб-чат в домашней сети" `
    -Force | Out-Null

Write-Host "Автозапуск записан: задача HomeLLM"
