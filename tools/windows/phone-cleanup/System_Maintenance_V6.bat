@echo off
setlocal
title System Maintenance

REM Clear current clipboard
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { Set-Clipboard -Value $null -ErrorAction Stop } catch { cmd /c 'echo off | clip' }" >nul 2>&1

REM Stop Explorer so cache files are released
taskkill /f /im explorer.exe >nul 2>&1
timeout /t 1 /nobreak >nul

REM File Explorer recent items / jump lists
del /f /q "%APPDATA%\Microsoft\Windows\Recent\*" >nul 2>&1
del /f /q "%APPDATA%\Microsoft\Windows\Recent\AutomaticDestinations\*" >nul 2>&1
del /f /q "%APPDATA%\Microsoft\Windows\Recent\CustomDestinations\*" >nul 2>&1

REM Explorer MRU/history registry keys
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\RunMRU" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\TypedPaths" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\WordWheelQuery" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\ComDlg32\OpenSavePidlMRU" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\ComDlg32\LastVisitedPidlMRU" /f >nul 2>&1

REM Remote Desktop Connection history
reg delete "HKCU\Software\Microsoft\Terminal Server Client\Default" /va /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Terminal Server Client\Servers" /f >nul 2>&1
reg add "HKCU\Software\Microsoft\Terminal Server Client\Servers" /f >nul 2>&1

REM Remove last RDP address/username from Default.rdp while preserving other settings
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p = Join-Path $env:USERPROFILE 'Documents\Default.rdp'; if (Test-Path $p) { $lines = Get-Content -LiteralPath $p -Encoding Unicode; $lines = $lines | Where-Object { $_ -notmatch '^(full address|username):s:' }; Set-Content -LiteralPath $p -Value $lines -Encoding Unicode }" >nul 2>&1

REM Thumbnail/icon cache
del /f /q "%LOCALAPPDATA%\Microsoft\Windows\Explorer\thumbcache_*.db" >nul 2>&1
del /f /q "%LOCALAPPDATA%\Microsoft\Windows\Explorer\iconcache_*.db" >nul 2>&1

REM Restart Explorer
start "" explorer.exe

echo Maintenance completed.
timeout /t 2 /nobreak >nul
endlocal
exit /b
