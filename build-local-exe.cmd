@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%build-local-exe.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
	echo.
	echo Build en erreur. Appuie sur une touche pour fermer...
	pause >nul
)
endlocal & exit /b %EXIT_CODE%
