#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
神盾路況行車助手 — 自動版本號遞增與發布更新腳本
===================================================
1. 自動讀取 version.properties，將 VERSION_CODE 加 1。
2. 自動更新小版本號 VERSION_NAME (例如 2.0.1 -> 2.0.2)。
3. 同步寫入 cloud_data/app_version.json，讓所有已安裝的車主開啟 App 立即收到更新推播！
"""

import json
import re
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
PROPS_FILE = ROOT_DIR / "version.properties"
JSON_FILE = ROOT_DIR / "cloud_data" / "app_version.json"


def read_properties(path: Path) -> dict:
    data = {}
    if not path.exists():
        return {"VERSION_CODE": "2", "VERSION_NAME": "2.0.0"}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            data[k.strip()] = v.strip()
    return data


def write_properties(path: Path, data: dict):
    lines = [f"{k}={v}" for k, v in data.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def bump_version(custom_name: str = None):
    props = read_properties(PROPS_FILE)
    old_code = int(props.get("VERSION_CODE", 2))
    old_name = props.get("VERSION_NAME", "2.0.0")

    new_code = old_code + 1

    if custom_name:
        new_name = custom_name.strip()
    else:
        # 自動累加最末碼：如 2.0.1 -> 2.0.2
        parts = old_name.split(".")
        if len(parts) >= 3 and parts[-1].isdigit():
            parts[-1] = str(int(parts[-1]) + 1)
            new_name = ".".join(parts)
        else:
            new_name = f"{old_name}.{new_code}"

    props["VERSION_CODE"] = str(new_code)
    props["VERSION_NAME"] = new_name
    write_properties(PROPS_FILE, props)

    # 同步更新 cloud_data/app_version.json
    if JSON_FILE.exists():
        try:
            ver_obj = json.loads(JSON_FILE.read_text(encoding="utf-8"))
        except Exception:
            ver_obj = {}
    else:
        ver_obj = {}

    ver_obj["versionCode"] = new_code
    ver_obj["versionName"] = new_name
    ver_obj["releaseNotes"] = f"版本 v{new_name} 更新：\n1. 支援懸浮視窗自由拖曳至螢幕邊界外（防遮擋導航地圖）\n2. 智慧貼邊收折功能：靠邊自動收成側邊箭頭/速限圓形小標籤\n3. 輕點邊界標籤秒速彈出恢復完整視窗\n4. 危險緊急狀況自動彈出：收折狀態下遇超速、前方500m測速或道路封閉自動滑出展開，安全通過後自動收回\n5. 設定頁面新增「懸浮窗支援貼邊收折」開關與邊界防丟失安全保護"
    ver_obj.setdefault("forceUpdate", False)

    JSON_FILE.parent.mkdir(parents=True, exist_ok=True)
    JSON_FILE.write_text(json.dumps(ver_obj, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"==================================================")
    print(f"🚀 版本號已成功推進！")
    print(f"   舊版本: v{old_name} (Code: {old_code})")
    print(f"   新版本: v{new_name} (Code: {new_code})")
    print(f"   已同步更新: version.properties ＆ cloud_data/app_version.json")
    print(f"==================================================")
    return new_code, new_name


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    bump_version(arg)
