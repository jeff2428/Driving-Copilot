@echo off
setlocal EnableDelayedExpansion

if "%1"=="" goto check
if "%1"=="check" goto check
if "%1"=="test" goto test
if "%1"=="parity" goto parity
if "%1"=="swift-lint" goto swift_lint
if "%1"=="android-test" goto android_test
if "%1"=="clean" goto clean

echo Usage: make [check ^| test ^| parity ^| swift-lint ^| android-test ^| clean]
exit /b 1

:check
call :test
if errorlevel 1 exit /b 1
call :parity
if errorlevel 1 exit /b 1
call :swift_lint
if errorlevel 1 exit /b 1
call :android_test
if errorlevel 1 exit /b 1
echo.
echo ===================================================
echo  [PASS] ALL QUALITY GATES VERIFIED SUCCESSFULLY!
echo ===================================================
exit /b 0

:test
echo Running Unit Tests...
python -m unittest discover -s tests -p "test_*.py"
exit /b %errorlevel%

:parity
echo Verifying Cross-Platform Parity...
python scripts\test_parity.py
exit /b %errorlevel%

:swift_lint
echo Validating Swift Structural Integrity...
python scripts\validate_swift.py
exit /b %errorlevel%

:android_test
call "%~dp0gradlew.bat" :app:testDebugUnitTest
exit /b %errorlevel%

:clean
echo Cleaning cache directories...
for /d /r . %%d in (__pycache__ .pytest_cache .mypy_cache) do @if exist "%%d" rd /s /q "%%d" 2>nul
echo Clean complete.
exit /b 0
