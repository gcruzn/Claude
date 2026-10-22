@echo off
REM Detiene Mi PC, quita el arranque automatico y lo saca de Tailscale.
REM Tu configuracion queda en %APPDATA%\MiPC por si lo vuelves a instalar.
set PIDFILE=%APPDATA%\MiPC\mi_pc.pid
if exist "%PIDFILE%" (
  for /f %%p in (%PIDFILE%) do taskkill /PID %%p /F >nul 2>nul
)
del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\Mi PC.lnk" >nul 2>nul
where tailscale >nul 2>nul && tailscale serve --https=443 off >nul 2>nul
echo Mi PC desinstalado.
pause
