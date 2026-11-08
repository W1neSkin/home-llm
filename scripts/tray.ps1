# Значок у часов. Правый клик: пауза, продолжить, настройки.
# Пауза гасит Ollama: видеокарта свободна и запросы с телефона не принимаются.
# Файл data\paused сохраняется после перезагрузки, пока не нажать «Продолжить».

if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne "STA") {
    Start-Process powershell.exe -ArgumentList "-NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit 0
}

. "$PSScriptRoot\common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# Второй значок не нужен, если трей уже запущен.
$mutex = New-Object System.Threading.Mutex($false, "Local\HomeLLMTray")
if (-not $mutex.WaitOne(0, $false)) { exit 0 }

New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null
$pauseFlag = Join-Path $Root "data\paused"
$thinkFlag = Join-Path $Root "data\thinking"

function Test-Paused { Test-Path $pauseFlag }

function Test-Thinking {
    if (-not (Test-Path $thinkFlag)) { return $true }
    return (Get-Content $thinkFlag -Raw).Trim() -ne "off"
}

function Update-Tray {
    if (Test-Paused) {
        $notify.Icon = $iconPause
        $notify.Text = "LocAI, пауза"
        $pauseItem.Text = "Запустить"
    } else {
        $notify.Icon = $iconRun
        $notify.Text = "LocAI"
        $pauseItem.Text = "Остановить"
    }
}

function Stop-Engine {
    Set-Content -Path $pauseFlag -Value "paused" -Encoding ASCII
    Get-Process | Where-Object { $_.ProcessName -like "ollama*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Update-Tray
    $notify.ShowBalloonTip(3000, "LocAI", "Остановлено. Видеокарта свободна.", "Info")
}

function Start-Engine {
    if (Test-Path $pauseFlag) { Remove-Item $pauseFlag -Force }
    Update-Tray
    $notify.ShowBalloonTip(3000, "LocAI", "Запускаю модель с диска.", "Info")
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSScriptRoot\start.ps1`""
}

function Show-Settings {
    Import-ProjectEnv
    $form = New-Object System.Windows.Forms.Form
    $form.Text = "LocAI"
    $form.FormBorderStyle = "FixedDialog"
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.StartPosition = "CenterScreen"
    $form.ClientSize = New-Object System.Drawing.Size 440, 280
    $form.Font = New-Object System.Drawing.Font "Segoe UI", 9

    $lblModel = New-Object System.Windows.Forms.Label
    $lblModel.Text = "Файл модели в папке models"
    $lblModel.Location = New-Object System.Drawing.Point 16, 14
    $lblModel.AutoSize = $true
    $modelBox = New-Object System.Windows.Forms.ComboBox
    $modelBox.Location = New-Object System.Drawing.Point 16, 36
    $modelBox.Width = 408
    Get-ChildItem (Join-Path $Root "models\*.gguf") -ErrorAction SilentlyContinue | ForEach-Object {
        [void]$modelBox.Items.Add($_.Name)
    }
    $modelBox.Text = Get-ProjectValue "BASE_MODEL"

    $lblCtx = New-Object System.Windows.Forms.Label
    $lblCtx.Text = "Контекст"
    $lblCtx.Location = New-Object System.Drawing.Point 16, 74
    $lblCtx.AutoSize = $true
    $ctxBox = New-Object System.Windows.Forms.TextBox
    $ctxBox.Location = New-Object System.Drawing.Point 16, 96
    $ctxBox.Width = 120
    $ctxBox.Text = Get-ProjectValue "NUM_CTX" "8192"

    $lblPort = New-Object System.Windows.Forms.Label
    $lblPort.Text = "Порт сайта"
    $lblPort.Location = New-Object System.Drawing.Point 160, 74
    $lblPort.AutoSize = $true
    $portBox = New-Object System.Windows.Forms.TextBox
    $portBox.Location = New-Object System.Drawing.Point 160, 96
    $portBox.Width = 120
    $portBox.Text = Get-ProjectValue "WEBUI_PORT" "8787"

    $lblToken = New-Object System.Windows.Forms.Label
    $lblToken.Text = "Токен Telegram-бота"
    $lblToken.Location = New-Object System.Drawing.Point 16, 128
    $lblToken.AutoSize = $true
    $tokenBox = New-Object System.Windows.Forms.TextBox
    $tokenBox.Location = New-Object System.Drawing.Point 16, 148
    $tokenBox.Width = 408
    $tokenBox.Text = Get-ProjectValue "TELEGRAM_BOT_TOKEN"

    $lblIds = New-Object System.Windows.Forms.Label
    $lblIds.Text = "Чьи id пускать, через запятую"
    $lblIds.Location = New-Object System.Drawing.Point 16, 176
    $lblIds.AutoSize = $true
    $idsBox = New-Object System.Windows.Forms.TextBox
    $idsBox.Location = New-Object System.Drawing.Point 16, 196
    $idsBox.Width = 408
    $idsBox.Text = Get-ProjectValue "TELEGRAM_ALLOWED_IDS"

    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = "Сохранить"
    $ok.Location = New-Object System.Drawing.Point 244, 236
    $ok.DialogResult = "OK"
    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = "Отмена"
    $cancel.Location = New-Object System.Drawing.Point 330, 236
    $cancel.DialogResult = "Cancel"
    $form.AcceptButton = $ok
    $form.CancelButton = $cancel
    $form.Controls.AddRange(@($lblModel, $modelBox, $lblCtx, $ctxBox, $lblPort, $portBox, $lblToken, $tokenBox, $lblIds, $idsBox, $ok, $cancel))
    if ($form.ShowDialog() -ne "OK") { return }

    $oldModel = Get-ProjectValue "BASE_MODEL"
    $oldCtx = Get-ProjectValue "NUM_CTX" "8192"
    Set-ProjectValue "BASE_MODEL" $modelBox.Text.Trim()
    Set-ProjectValue "NUM_CTX" $ctxBox.Text.Trim()
    Set-ProjectValue "WEBUI_PORT" $portBox.Text.Trim()
    Set-ProjectValue "TELEGRAM_BOT_TOKEN" $tokenBox.Text.Trim()
    Set-ProjectValue "TELEGRAM_ALLOWED_IDS" $idsBox.Text.Trim()
    # Старый процесс бота держит прежний токен. Гасим и поднимаем заново.
    Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*telegram\bot.py*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if (Start-TelegramBot) {
        $notify.ShowBalloonTip(3000, "LocAI", "Telegram-бот запущен.", "Info")
    }
    # Новый файл только с диска. update-model.ps1 без ключа в интернет не ходит.
    if ($modelBox.Text.Trim() -ne $oldModel -or $ctxBox.Text.Trim() -ne $oldCtx) {
        if (Test-Path $pauseFlag) { Remove-Item $pauseFlag -Force }
        Update-Tray
        $notify.ShowBalloonTip(4000, "LocAI", "Подключаю модель из папки models.", "Info")
        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSScriptRoot\update-model.ps1`""
    }
}

$iconRun = New-Object System.Drawing.Icon (Join-Path $Root "assets\locai.ico")
$iconPause = New-Object System.Drawing.Icon (Join-Path $Root "assets\locai-paused.ico")
$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Visible = $true
$menu = New-Object System.Windows.Forms.ContextMenuStrip
$pauseItem = $menu.Items.Add("Остановить")
$thinkItem = $menu.Items.Add("Размышления: вкл")
$settingsItem = $menu.Items.Add("Настройки")
$openItem = $menu.Items.Add("Открыть чат")
$shareItem = $menu.Items.Add("Адрес для других")
[void]$menu.Items.Add("-")
$exitItem = $menu.Items.Add("Закрыть значок")
$notify.ContextMenuStrip = $menu

function Update-ThinkItem {
    if (Test-Thinking) { $thinkItem.Text = "Размышления: вкл" }
    else { $thinkItem.Text = "Размышления: выкл" }
}

$pauseItem.add_Click({ if (Test-Paused) { Start-Engine } else { Stop-Engine } })
$thinkItem.add_Click({
    if (Test-Thinking) {
        Set-Content -Path $thinkFlag -Value "off" -Encoding ASCII
        $notify.ShowBalloonTip(2500, "LocAI", "Размышления выключены.", "Info")
    } else {
        Set-Content -Path $thinkFlag -Value "on" -Encoding ASCII
        $notify.ShowBalloonTip(2500, "LocAI", "Размышления включены.", "Info")
    }
    Update-ThinkItem
})
$menu.add_Opening({ Update-ThinkItem })
$settingsItem.add_Click({ Show-Settings })
$openItem.add_Click({
    Import-ProjectEnv
    Start-Process ("http://127.0.0.1:" + (Get-ProjectValue "WEBUI_PORT" "8787") + "/")
})
$shareItem.add_Click({
    Import-ProjectEnv
    $web = "http://$(Get-LanAddress):$(Get-ProjectValue 'WEBUI_PORT' '8787')/"
    $phone = "http://$(Get-LanAddress):$(Get-ProjectValue 'PHONE_PORT' '8765')/"
    $key = Get-ProjectValue "PHONE_TOKEN"
    try { Set-Clipboard "Сайт: $web`r`nПриложение: $phone`r`nКлюч: $key" } catch {}
    $notify.ShowBalloonTip(6000, "LocAI", "Адрес и ключ для приложения скопированы.", "Info")
})
$exitItem.add_Click({
    # Крестик значка не ставит паузу: модель остаётся в том состоянии, в каком была.
    $notify.Visible = $false
    $notify.Dispose()
    $mutex.ReleaseMutex()
    [System.Windows.Forms.Application]::Exit()
})
$notify.add_MouseUp({
    if ($_.Button -eq [System.Windows.Forms.MouseButtons]::Left) {
        try { $menu.Show([System.Windows.Forms.Cursor]::Position) } catch {}
    }
})

if (-not (Test-Path $thinkFlag)) {
    Set-Content -Path $thinkFlag -Value "on" -Encoding ASCII
}
& "$PSScriptRoot\run-phone.ps1"
Update-Tray
Update-ThinkItem
if (Test-Paused) {
    Get-Process | Where-Object { $_.ProcessName -like "ollama*" } | Stop-Process -Force -ErrorAction SilentlyContinue
} else {
    Start-Engine
}

$app = New-Object System.Windows.Forms.ApplicationContext
[System.Windows.Forms.Application]::Run($app)
