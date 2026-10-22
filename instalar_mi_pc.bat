@echo off
REM Instala Mi PC en este computador (Windows) y lo deja arrancando solo.
setlocal
cd /d "%~dp0"
echo.
echo === Instalando Mi PC ===
echo.

where py >nul 2>nul
if errorlevel 1 (
  echo Falta Python. Instalalo desde https://www.python.org/downloads/ y vuelve a ejecutar esto.
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo Creando entorno de Python...
  py -3 -m venv .venv || goto :error
)
echo Instalando dependencias...
".venv\Scripts\python.exe" -m pip install --quiet --upgrade pip
".venv\Scripts\python.exe" -m pip install --quiet -r remote_desktop\requirements.txt || goto :error

where claude >nul 2>nul
if errorlevel 1 (
  echo.
  echo AVISO: no encuentro Claude Code. Instalalo desde https://claude.com/claude-code
  echo        y ejecuta "claude" una vez para iniciar sesion con tu cuenta.
)

echo.
echo Elige la contrasena con la que vincularas el celular (solo se pide una vez).
".venv\Scripts\python.exe" -m remote_desktop.server --set-password || goto :error

echo.
echo Configurando el arranque automatico al iniciar Windows...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Startup')+'\Mi PC.lnk');" ^
  "$s.TargetPath='%CD%\.venv\Scripts\pythonw.exe'; $s.Arguments='-m remote_desktop.server';" ^
  "$s.WorkingDirectory='%CD%'; $s.Description='Mi PC'; $s.Save()" || goto :error

echo Iniciando Mi PC...
start "" ".venv\Scripts\pythonw.exe" -m remote_desktop.server

where tailscale >nul 2>nul
if errorlevel 1 (
  echo.
  echo AVISO: falta Tailscale. Instalalo desde https://tailscale.com/download
  echo        y despues ejecuta en una terminal:  tailscale serve --bg 8765
) else (
  echo Publicando Mi PC en tu red de Tailscale con HTTPS...
  tailscale serve --bg 8765
)

echo.
echo === Listo ===
echo Ahora agrega en "projects" las carpetas donde trabajara Claude.
echo Se abre el archivo de configuracion; guardalo y ejecuta reiniciar_mi_pc.bat.
timeout /t 3 >nul
notepad "%APPDATA%\MiPC\config.json"
exit /b 0

:error
echo.
echo Algo fallo. Revisa el mensaje de arriba.
pause
exit /b 1
