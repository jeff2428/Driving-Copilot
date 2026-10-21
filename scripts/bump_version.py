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
    ver_obj["releaseNotes"] = f"""版本 v{new_name} 更新：
1. 🎯 科技執法細項智慧識別：精準辨識闖紅燈、未禮讓行人、違規轉彎、佔用專用道、跨越雙白線等取締重點並專屬語音播報
2. ⏱️ 區間測速完整生命週期：導入起點預警、歷程累計真實均速計算、安全通行秒數即時倒數、均速超速警告與終點結算
3. 📐 法向量通過門閥 (Vector Pass Gate)：徹底解決山路急彎（如北宜公路 33K）與引道轉向時提早誤鳴「叮咚通過」問題
4. 🌉 高架與平面快速道路智慧分流：全台快速高架路網精準辨識，在高架巡航時智慧排除橋下平面測速
5. 🎙️ 多重警報智慧仲裁與融合機制：測速照相與施工封閉併發時自動融合成單一精煉語句，消除喋喋不休連續語音轟炸
6. 🍎 iOS 雙平台 100% 同步：Swift 核心演算法與數學模型同步升級並通過全數結構檢驗"""
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
