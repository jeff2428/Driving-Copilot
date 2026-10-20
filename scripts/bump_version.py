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
        f"1. 🎯 大幅提升測速與路況警告精準度：導入橫向偏距走廊過濾 (Cross-Track Corridor)，徹底排除隔壁平行道路誤報\n"
        f"2. 🧭 雙重方向解析與國道/平面分流：結合地址與拍攝方向精準判斷，排除高架橋下與橫向路口誤報\n"
        f"3. 🚇 雪山隧道進洞後自動停止提醒：進洞前提醒完畢，一旦進入雪隧立即關閉車道建議並無縫切換隧道內科技執法測速\n"
        f"4. 📏 依速限與車速動態調整預警距離，並新增通過測速桿後 65 秒防回頭誤報冷卻機制"
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
