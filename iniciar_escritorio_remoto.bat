@echo off
REM Inicia el servidor de escritorio remoto en este PC (Windows).
REM Cambia la contrasena antes de usarlo.
cd /d "%~dp0"
set RD_PASSWORD=cambia-esta-clave-larga
python -m remote_desktop.server --port 8765
pause
