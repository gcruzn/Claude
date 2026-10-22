@echo off
REM Reinicia Mi PC (por ejemplo, despues de editar config.json).
cd /d "%~dp0"
set PIDFILE=%APPDATA%\MiPC\mi_pc.pid
if exist "%PIDFILE%" (
  for /f %%p in (%PIDFILE%) do taskkill /PID %%p /F >nul 2>nul
)
timeout /t 1 >nul
start "" ".venv\Scripts\pythonw.exe" -m remote_desktop.server
echo Mi PC reiniciado.
timeout /t 2 >nul
