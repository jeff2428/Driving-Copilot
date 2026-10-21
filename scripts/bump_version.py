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
    ver_obj["releaseNotes"] = (
        f"版本 v{new_name} 更新：\n"
        f"1. 🔊 修復按「開始行車」語音播報兩次問題：加入狀態守衛與 4 秒語音去重防抖，確保「神盾行車防護已啟動」僅精準播報一次\n"
        f"2. 🗺️ 地圖標示與實體路網精準對齊：解耦雷達警報走廊與地圖實體標記，地圖如實呈現半徑 4.5km 所有實體測速照相與 8km 施工封閉\n"
        f"2. 🔄 修正車輛箭頭旋轉支點偏移：修復 SVG 旋轉中心漂移問題，轉彎時車身永恆固定在道路中央\n"
        f"3. 📡 全新動態車頭前向雷達掃描光錐：在 Leaflet 地圖呈現 380m/38° 青藍與警示橙紅動態掃描光錐\n"
        f"4. 🚇 國道5號雪山隧道進洞引導優化：進洞前引導完畢，入洞後自動停止重複提醒並切換隧道內科技執法測速\n"
        f"5. 🎯 提高測速與路況警告精準度：導入橫向偏距走廊過濾 (Cross-Track Corridor)，雙重解析拍攝方向，高架橋下與平面道路智慧分流\n"
        f"6. 🍎 同步發布 iOS 原生版本 (SwiftUI) 與動態島 / 即時動態支援"
    )
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
