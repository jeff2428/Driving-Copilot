@echo off
cd /d "%~dp0"
python scripts\publish_release.py
if errorlevel 1 (
    echo.
    echo [ERROR] Process finished with errors.
)
echo.
pause
