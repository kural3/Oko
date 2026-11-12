# Установка Oko: программа + виртуальная камера «Oko Camera». Требуются права администратора.
param([string]$Log)
$ErrorActionPreference = 'Stop'
if ($Log) { Start-Transcript -Path $Log -Force | Out-Null }
try {
    $src = Split-Path -Parent $MyInvocation.MyCommand.Path
    $dst = Join-Path $env:ProgramFiles 'Oko'
    $old = Join-Path $env:ProgramFiles 'Camera2PC'
    $startMenu = "$env:ProgramData\Microsoft\Windows\Start Menu\Programs"
    $desktop = [Environment]::GetFolderPath('CommonDesktopDirectory')

    Get-Process Oko, Camera2PC -ErrorAction SilentlyContinue | Stop-Process -Force

    # снимаем камеру прошлой установки (и Oko, и старую Camera2PC), чтобы служба отпустила DLL
    foreach ($dir in @($dst, $old)) {
        if (Test-Path "$dir\vcamctl.exe") { & "$dir\vcamctl.exe" uninstall "$dir\Camera2PCCam.dll" | Out-Host }
    }
    & "$src\vcamctl.exe" uninstall | Out-Host
    Restart-Service FrameServer -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1

    # старая версия под именем Camera2PC больше не нужна
    Remove-Item $old -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "$startMenu\Camera2PC.lnk", "$desktop\Camera2PC.lnk" -ErrorAction SilentlyContinue

    Remove-Item (Join-Path $dst 'app'), (Join-Path $dst 'runtime') -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item "$src\Oko\*" $dst -Recurse -Force
    Copy-Item "$src\Camera2PCCam.dll", "$src\vcamctl.exe", "$src\uninstall.ps1" $dst -Force

    & "$dst\vcamctl.exe" install "$dst\Camera2PCCam.dll" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Не удалось зарегистрировать камеру (код $LASTEXITCODE)" }

    $shell = New-Object -ComObject WScript.Shell
    foreach ($dir in @($startMenu, $desktop)) {
        $lnk = $shell.CreateShortcut((Join-Path $dir 'Oko.lnk'))
        $lnk.TargetPath = "$dst\Oko.exe"
        $lnk.WorkingDirectory = $dst
        $lnk.IconLocation = "$dst\Oko.exe,0"
        $lnk.Save()
    }
    Write-Host "Готово: Oko установлен в $dst"
} catch {
    Write-Host "ОШИБКА: $_"
    exit 1
} finally {
    if ($Log) { Stop-Transcript | Out-Null }
}
