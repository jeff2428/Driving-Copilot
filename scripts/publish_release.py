import os
import sys
import subprocess
import shutil
import json
from pathlib import Path

# 強制標準輸出為 UTF-8
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE_DIR = Path(__file__).resolve().parent.parent

def read_current_version():
    props_file = BASE_DIR / "version.properties"
    vcode = "1"
    vname = "2.0.0"
    if props_file.exists():
        for line in props_file.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                if k.strip() == "VERSION_CODE":
                    vcode = v.strip()
                elif k.strip() == "VERSION_NAME":
                    vname = v.strip()
    return vcode, vname

def run():
    print("=" * 65)
    print("🚗 神盾路況行車助手 — 一鍵自動遞增版本號、編譯並推送雲端更新")
    print("=" * 65)
    
    # 1. 遞增版本號
    print("\n[步驟 1/4] 正在自動遞增版本號並同步雲端更新檔 (cloud_data/app_version.json)...")
    bump_script = BASE_DIR / "scripts" / "bump_version.py"
    res = subprocess.run([sys.executable, str(bump_script)], cwd=str(BASE_DIR))
    if res.returncode != 0:
        print("\n❌ [錯誤] 版本號累加失敗！")
        return False
        
    vcode, vname = read_current_version()

    # 2. 設定環境變數並編譯 Gradle
    print(f"\n[步驟 2/4] 正在執行 Gradle 編譯打包新版 APK (v{vname}, Code: {vcode})，請稍候...")
    java_home = r"C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
    gradle_bat = Path(os.environ["USERPROFILE"]) / ".gradle" / "wrapper" / "dists" / "gradle-8.14.3-bin" / "cv11ve7ro1n3o1j4so8xd9n66" / "gradle-8.14.3" / "bin" / "gradle.bat"
    
    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    
    gradle_cmd = [str(gradle_bat), "assembleDebug", "--console=plain"]
    res = subprocess.run(gradle_cmd, cwd=str(BASE_DIR), env=env)
    if res.returncode != 0:
        print("\n❌ [錯誤] Gradle 編譯失敗，請檢查上方編譯錯誤日誌！")
        return False
        
    # 3. 複製產出 APK
    print("\n[步驟 3/4] 正在同步輸出 APK 安裝包至本機...")
    src_apk = BASE_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not src_apk.exists():
        print(f"\n❌ [錯誤] 找不到編譯產出檔：{src_apk}")
        return False
        
    dst1 = BASE_DIR / "TW-RoadGuard-Aegis.apk"
    dst2 = BASE_DIR / "神盾路況行車助手-v1.0.apk"
    
    shutil.copy2(src_apk, dst1)
    shutil.copy2(src_apk, dst2)
    
    apk_size_mb = src_apk.stat().st_size / (1024 * 1024)
    print(f"✅ 本機安裝包已就緒：{dst1.name} ({apk_size_mb:.2f} MB)")

    # 4. 自動提交並推送到 GitHub 雲端 (觸發所有車主手機更新)
    print(f"\n[步驟 4/4] 正在將新版發布至 GitHub 雲端倉庫 (jeff2428/Driving-Copilot)...")
    try:
        # git add & commit
        subprocess.run(["git", "add", "-A"], cwd=str(BASE_DIR), check=True)
        commit_msg = f"發布新版本 v{vname} (Code: {vcode})"
        subprocess.run(["git", "commit", "-m", commit_msg], cwd=str(BASE_DIR), check=False)
        
        # git pull --rebase & push
        print("  ➔ 正在同步並推送版本推播檔至 GitHub main 分支...")
        subprocess.run(["git", "pull", "--rebase", "origin", "main"], cwd=str(BASE_DIR))
        push_res = subprocess.run(["git", "push", "-u", "origin", "main"], cwd=str(BASE_DIR))
        if push_res.returncode == 0:
            print("  ✅ 雲端版本資訊 (app_version.json) 已成功推送到 GitHub！")
        else:
            print("  ⚠️ git push 未完成，請檢查網路連線")
            
        # gh release upload APK
        print(f"  ➔ 正在建立 GitHub Release (v{vname}) 並上傳 APK 安裝包...")
        release_cmd = [
            "gh", "release", "create", f"v{vname}",
            str(dst1),
            "--title", f"神盾路況行車助手 v{vname}",
            "--notes", (
                f"神盾路況行車助手 v{vname} 最新版發布：\n"
                f"- 🪟 懸浮視窗支援拖曳至螢幕邊界外：徹底解決使用 Google Maps 時懸浮窗遮擋導航轉向視線之困擾\n"
                f"- 📐 智慧貼邊自動收折：推至螢幕左/右邊界自動收折為極簡側邊抽屜標籤（▶ / ◀）與速限小標籤\n"
                f"- 👆 輕點邊界標籤秒速彈出恢復完整視窗\n"
                f"- 🚨 緊急狀況智慧自動滑出：收折狀態下，若偵測到超速、前方 500m 內測速照相或道路封閉，視窗自動滑出展開以保行車安全；危機解除後自動縮回貼邊\n"
                f"- 🛡️ 邊界防丟失保護：拖曳時保留至少 46dp 安全寬度，絕不迷失在螢幕外\n"
                f"- ⚙️ 設定頁面新增「懸浮窗支援貼邊收折」開關，支援偏好設定持久化保存"
            )
        ]
        rel_res = subprocess.run(release_cmd, cwd=str(BASE_DIR))
        if rel_res.returncode == 0:
            print(f"  ✅ GitHub Release (v{vname}) 與 APK 下載載點已建立成功！")
        else:
            print("  ⚠️ GitHub Release 上傳略過或需稍後重試")

    except Exception as e:
        print(f"  ⚠️ 雲端推送步驟提示：{e}")

    print("\n" + "=" * 65)
    print(f"🎉 新版本 v{vname} (Code: {vcode}) 發布流程已全部完成！")
    print(f"📦 本機安裝包：{dst1.name}")
    print(f"🌐 雲端版本檔：https://raw.githubusercontent.com/jeff2428/Driving-Copilot/main/cloud_data/app_version.json")
    print(f"📥 雲端下載點：https://github.com/jeff2428/Driving-Copilot/releases/latest/download/TW-RoadGuard-Aegis.apk")
    print("💡 所有已安裝舊版的車主，開啟 App 將會立即跳出「發現新版本」全螢幕更新提醒！")
    print("=" * 65)
    return True

if __name__ == "__main__":
    success = run()
    sys.exit(0 if success else 1)
