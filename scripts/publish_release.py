"""Build and publish a signed RoadGuard APK from a committed main branch."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from bump_version import PROPS_FILE, JSON_FILE, read_properties, write_properties

ROOT = Path(__file__).resolve().parents[1]
REPO = "jeff2428/Driving-Copilot"
RELEASE_NOTES = """1. 👑 全面開放 Pro 尊享狀態：免看廣告直接同步雲端最新圖資，並享有背景自動更新特權。
2. 🛸 科技座艙玻璃擬態 UI 與懸浮膠囊升級：新增逼近/區間進度條、隧道慣性推估徽章、平滑呼吸閃爍動畫與懸浮一鍵靜音切換，並提升地圖與音效效能。
3. 🚇 隧道盲區高精度慣性推估 (Dead Reckoning)：長隧道/衛星盲區自動以進洞車速推算每秒里程、安全剩餘秒數與均速，900秒安全超時保護，出洞無縫切換。
4. 💾 區間測速磁碟持久化與重啟復原 (Crash Recovery)：起點時間、已跑里程、均速即時持久化，抵禦系統被殺或重啟，重啟即可100%精準接續。
5. 🚨 超速搶佔最高優先級多重事件智慧仲裁 (Speeding-First Arbitration)：超速時強制覆蓋一般路況，未超速多重路況智慧融合為單句語音，拒絕疲勞轟炸。
6. 📞 通話語音焦點智慧管理 (Audio Focus) 與漸近逼近音：支援通話中強制降低音量(Ducking)或語音靜音＋懸浮窗微光呼吸閃爍雙模式；150m/100m/50m分段逼近音階。"""


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


def output(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding="utf-8").strip()


def publish() -> None:
    if output("git", "branch", "--show-current") != "main" or output("git", "status", "--porcelain"):
        raise ValueError("請先在 main 分支提交並檢查所有預定發布的變更")
    run("git", "fetch", "origin", "main")
    if output("git", "rev-list", "--count", "HEAD..origin/main") != "0":
        raise ValueError("請先同步 origin/main 並重新驗證")
    wrapper = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "./gradlew"]
    run(*(["cmd", "/c", "make.bat", "check"] if os.name == "nt" else ["make", "check"]))
    props = read_properties(PROPS_FILE)
    manifest = json.loads(JSON_FILE.read_text(encoding="utf-8"))
    old_props = PROPS_FILE.read_bytes()
    # A version-only commit records a pending publication and lets the same tag resume.
    pending = output("git", "log", "-1", "--format=%s") == f"Prepare Android v{props['VERSION_NAME']}"
    if not pending:
        parts = props["VERSION_NAME"].split(".")
        parts[-1] = str(int(parts[-1]) + 1)
        props.update(VERSION_NAME=".".join(parts), VERSION_CODE=str(int(props["VERSION_CODE"]) + 1))
        write_properties(PROPS_FILE, props)
    tag = "v" + props["VERSION_NAME"]
    notes = f"版本 {tag} 更新：\n{RELEASE_NOTES}"
    try:
        run(*wrapper, ":app:assembleRelease")
    except BaseException:
        if not pending:
            PROPS_FILE.write_bytes(old_props)
        raise
    if not pending:
        run("git", "commit", "--only", "-m", "Prepare Android " + tag, "--", "version.properties")
    head = output("git", "rev-parse", "HEAD")
    run("git", "push", "origin", head + ":refs/tags/" + tag)
    with tempfile.TemporaryDirectory() as tmp:
        asset = Path(tmp) / "TW-RoadGuard-Aegis.apk"
        remote = subprocess.run(["gh", "release", "view", tag, "--repo", REPO], cwd=ROOT, capture_output=True)
        if remote.returncode == 0:
            run("gh", "release", "download", tag, "--repo", REPO, "--pattern", asset.name, "--dir", tmp)
        else:
            shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release.apk", asset)
            run("gh", "release", "create", tag, str(asset), "--repo", REPO, "--verify-tag", "--title", tag, "--notes", notes)
        manifest.update(versionCode=int(props["VERSION_CODE"]), versionName=props["VERSION_NAME"],
                        apkSha256=hashlib.sha256(asset.read_bytes()).hexdigest(),
                        apkDownloadUrl=f"https://github.com/{REPO}/releases/download/{tag}/{asset.name}",
                        releaseNotes=notes)
    JSON_FILE.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    run("git", "commit", "--only", "-m", "Publish Android " + tag, "--", "cloud_data/app_version.json")
    run("git", "push", "origin", "HEAD:main")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.dry_run:
        print("make check → 正式版建置 → 版本標籤 → 上傳 APK → 發布校驗碼與版本檔")
    else:
        publish()
