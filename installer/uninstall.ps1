# Удаление Oko. Требуются права администратора.
$dst = Join-Path $env:ProgramFiles 'Oko'
Get-Process Oko -ErrorAction SilentlyContinue | Stop-Process -Force
if (Test-Path "$dst\vcamctl.exe") { & "$dst\vcamctl.exe" uninstall "$dst\Camera2PCCam.dll" }
Restart-Service FrameServer -Force -ErrorAction SilentlyContinue
Remove-Item "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\Oko.lnk", (Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'Oko.lnk') -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Remove-Item $dst -Recurse -Force -ErrorAction SilentlyContinue
Write-Host 'Oko удалён.'
