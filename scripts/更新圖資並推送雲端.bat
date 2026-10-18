@echo off
chcp 65001 >nul
cd /d "%~dp0.."
echo ========================================================
echo  神盾路況行車助手 — 自動爬取最新警政署測速與警廣施工圖資
echo ========================================================
python scripts\update_taiwan_road_data.py
if %ERRORLEVEL% NEQ 0 (
    echo [錯誤] 爬蟲執行失敗，請確認網路連線與 Python 環境
    pause
    exit /b 1
)

echo.
echo [完成] 最新圖資已寫入 cloud_data\ 與 app\src\main\assets\
echo 若已綁定 GitHub 遠端倉庫，正在自動推送更新...
git status >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    git add cloud_data/ app/src/main/assets/
    git commit -m "chore(data): 更新全台測速與施工封閉圖資"
    git push
)
echo.
echo 圖資同步作業完成！
pause
