# Ярлык на рабочем столе. В автозагрузку ничего не ставится:
# модель стартует только когда ярлык открывают вручную.
# Повторный запуск убирает старую задачу входа в Windows, если она осталась.

. "$PSScriptRoot\common.ps1"

$task = Get-ScheduledTask -TaskName "HomeLLM" -ErrorAction SilentlyContinue
if ($task) {
    Unregister-ScheduledTask -TaskName "HomeLLM" -Confirm:$false
    Write-Host "Задача HomeLLM удалена"
}

# Установщик Ollama кладёт свой ярлык в автозагрузку. Убираем, чтобы
# видеокарта не занималась сама при включении компьютера.
$startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup\Ollama.lnk"
if (Test-Path $startup) {
    Remove-Item $startup -Force
    Write-Host "Автозагрузка Ollama убрана"
}

$trayScript = Join-Path $PSScriptRoot "tray.ps1"
$desktop = [Environment]::GetFolderPath("Desktop")
foreach ($oldName in @("Домашняя Qwen.lnk", "Лампа.lnk", "Lamp.lnk")) {
    $oldShortcut = Join-Path $desktop $oldName
    if (Test-Path $oldShortcut) { Remove-Item $oldShortcut -Force }
}
$shortcutPath = Join-Path $desktop "LocAI.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "powershell.exe"
$shortcut.Arguments = "-NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$trayScript`""
$shortcut.WorkingDirectory = $Root
$shortcut.WindowStyle = 7
$shortcut.Description = "Start LocAI"
$shortcut.IconLocation = "$(Join-Path $Root 'assets\locai.ico'),0"
$shortcut.Save()

Write-Host "Ярлык: $shortcutPath"
