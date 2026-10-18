#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
台灣神盾路況行車助手 (TW RoadGuard Aegis) — 自動化雲端圖資爬蟲與清洗引擎
========================================================================
功能：
1. 自動從【內政部警政署 Open Data】拉取全國最新測速執法設置點 CSV，清洗座標與速限，
   並補充台灣重點區間測速與科技執法路段。
2. 自動從【內政部警廣即時路況 PBS Open Data】拉取全國最新道路施工、封閉管制與事故 JSON，
   自動修正粗略座標（如台中市交大預設點）並過濾無效雜訊。
3. 產出標準化雲端檔案至 `cloud_data/` 與 Android 內建 `app/src/main/assets/`，
   並產生含 SHA-256 校驗碼與統計資訊的 `cloud_data/manifest.json`，供 App 每日自動檢查更新。
"""

import csv
import datetime
import hashlib
import io
import json
import os
import re
import ssl
import sys
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
CLOUD_DIR = ROOT_DIR / "cloud_data"
ASSETS_DIR = ROOT_DIR / "app" / "src" / "main" / "assets"

NPA_CAMERAS_URL = (
    "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/"
    "EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/"
    "AA22AE1B-D30A-4B93-83E4-5ABB9D5CB989/download"
)
PBS_EVENTS_URL = "https://rtr.pbs.gov.tw/NMP103_PbsWS/resources/roadData/opendata"

# 台灣重點區間測速與科技執法補充點位（確保即使警政署 CSV 格式異動，核心區間測速依然 100% 完整）
SUPPLEMENTAL_INTERVAL_AND_TECH_CAMERAS = [
    ("新北市", "新店區", "北宜公路台9線19K~23.1K【區間測速】", "新北市政府警察局", "北宜區間", "121.5762", "24.9486", "雙向區間測速", "40"),
    ("新北市", "萬里區", "台2線萬里隧道【區間測速】", "新北市政府警察局", "萬里區間", "121.6968", "25.1745", "雙向區間測速", "50"),
    ("新北市", "五股區", "台64線快速道路觀音山隧道【區間測速】", "新北市政府警察局", "觀音山區間", "121.4265", "25.1072", "雙向區間測速", "70"),
    ("臺北市", "中山區", "辛亥路隧道【區間測速】", "臺北市政府警察局", "辛亥區間", "121.5495", "25.0116", "雙向區間測速", "50"),
    ("臺北市", "士林區", "自強隧道【區間測速】", "臺北市政府警察局", "自強區間", "121.5482", "25.0912", "雙向區間測速", "50"),
    ("臺北市", "中正區", "鄭州路與西寧北路口【科技執法-多向違規與不停讓行人】", "臺北市政府警察局", "北門科技執法", "121.5094", "25.0491", "全向科技執法", "50"),
    ("桃園市", "龜山區", "台1線萬壽路一段青山段【區間測速】", "桃園市政府警察局", "萬壽區間", "121.3845", "25.0128", "雙向區間測速", "50"),
    ("苗栗縣", "通霄鎮", "台61線西濱快速道路通霄段114.3K~122.6K【區間測速】", "苗栗縣警察局", "西濱通霄區間", "120.6745", "24.5123", "雙向區間測速", "90"),
    ("臺中市", "沙鹿區", "台61線西濱快速道路中彰大橋至鹿港段【區間測速】", "臺中市政府警察局", "西濱中彰區間", "120.5062", "24.2158", "雙向區間測速", "90"),
    ("臺中市", "北屯區", "台74線快速道路松竹匝道【科技執法-跨越槽化線】", "臺中市政府警察局", "台74科技執法", "120.7082", "24.1815", "東向與西向科技執法", "80"),
    ("臺中市", "西屯區", "台灣大道與文心路口【科技執法-闖紅燈與不禮讓行人】", "臺中市政府警察局", "市政科技執法", "120.6468", "24.1619", "全向科技執法", "60"),
    ("彰化縣", "彰化市", "台74甲線彰化東外環道【區間測速】", "彰化縣警察局", "東外環區間", "120.5685", "24.0412", "雙向區間測速", "70"),
    ("南投縣", "埔里鎮", "台21線桃米至魚池段【區間測速】", "南投縣政府警察局", "台21區間", "120.9278", "23.9315", "雙向區間測速", "60"),
    ("雲林縣", "麥寮鄉", "台61線西濱快速道路橋頭至麥寮段【區間測速】", "雲林縣警察局", "西濱麥寮區間", "120.2315", "23.7842", "雙向區間測速", "90"),
    ("高雄市", "鳥松區", "松藝路【區間測速】", "高雄市政府警察局", "松藝區間", "120.3512", "22.6584", "雙向區間測速", "40"),
    ("高雄市", "內門區", "台3線392.5K~396K【區間測速】", "高雄市政府警察局", "內門區間", "120.4628", "22.8915", "雙向區間測速", "60"),
    ("屏東縣", "獅子鄉", "台9線南迴公路草埔森永隧道【區間測速】", "屏東縣政府警察局", "草埔隧道區間", "120.8421", "22.2564", "雙向區間測速", "70"),
    ("宜蘭縣", "南澳鄉", "台9線蘇花改觀音谷風隧道【區間測速】", "宜蘭縣政府警察局", "蘇花改觀音區間", "121.7615", "24.4125", "雙向區間測速", "70"),
    ("花蓮縣", "秀林鄉", "台9線蘇花改中仁隧道【區間測速】", "花蓮縣警察局", "蘇花改中仁區間", "121.7012", "24.2815", "雙向區間測速", "70"),
]


def create_ssl_context():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def fetch_url_text(url: str, timeout: int = 25) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            ),
            "Accept": "*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout, context=create_ssl_context()) as resp:
        raw = resp.read()
        return raw.decode("utf-8-sig", errors="replace")


def classify_camera_type(address: str, direct: str) -> str:
    text = f"{address} {direct}"
    if "區間" in text:
        return "INTERVAL"
    if "科技執法" in text or "不停讓" in text or "違規停車" in text or "跨越" in text:
        return "TECH"
    return "FIXED"


def refine_coarse_coord(lat: float, lng: float, area: str, road: str, comment: str, src: str):
    is_coarse = (abs(lat - 24.2) < 0.001 and abs(lng - 120.52) < 0.001) or (lat == 0.0 or lng == 0.0)
    if not is_coarse:
        return lat, lng

    text = f"{area} {road} {comment} {src}"
    rules = [
        (["崇德路", "進化北路"], (24.1628, 120.6845)),
        (["環中路", "市政路"], (24.1605, 120.6311)),
        (["台灣大道", "文心路"], (24.1619, 120.6468)),
        (["台灣大道"], (24.1642, 120.6395)),
        (["文心路"], (24.1535, 120.6465)),
        (["環中路"], (24.1480, 120.6258)),
        (["五權西路"], (24.1411, 120.6382)),
        (["中清路"], (24.1812, 120.6632)),
        (["松竹路"], (24.1856, 120.6915)),
        (["北屯"], (24.1820, 120.6860)),
        (["西屯"], (24.1650, 120.6350)),
        (["南屯"], (24.1390, 120.6400)),
        (["臺中", "台中"], (24.1585, 120.6540)),
        (["臺北", "台北"], (25.0478, 121.5319)),
        (["新北"], (25.0120, 121.4650)),
        (["桃園"], (24.9936, 121.3010)),
        (["新竹"], (24.8066, 120.9686)),
        (["臺南", "台南"], (22.9997, 120.2270)),
        (["高雄"], (22.6273, 120.3014)),
    ]
    for keywords, coord in rules:
        if all(k in text for k in keywords) if len(keywords) == 2 and keywords[0] != "臺中" and keywords[0] != "臺北" and keywords[0] != "臺南" else any(k in text for k in keywords):
            return coord
    return (lat, lng) if (lat != 0.0 and lng != 0.0) else (24.1631, 120.6403)


def update_cameras():
    print("[1/3] 正在從內政部警政署拉取全國測速執法點位 Open Data...")
    csv_text = ""
    try:
        csv_text = fetch_url_text(NPA_CAMERAS_URL)
        print(f"  -> 成功下載警政署測速資料 ({len(csv_text):,} bytes)")
    except Exception as e:
        print(f"  -> 警告：警政署 API 連線異常 ({e})，改讀取本地種子檔案作為基底")
        seed_path = ASSETS_DIR / "taiwan_cameras_seed.csv"
        if seed_path.exists():
            csv_text = seed_path.read_text(encoding="utf-8-sig")

    reader = csv.reader(io.StringIO(csv_text))
    cleaned_rows = []
    cameras_json = []
    seen_keys = set()

    for row in reader:
        if not row or len(row) < 9:
            continue
        city = row[0].strip().lstrip("\ufeff")
        if city in ("CityName", "設置縣市", ""):
            continue
        region = row[1].strip()
        address = row[2].strip().replace(",", "、")
        dept = row[3].strip()
        branch = row[4].strip()
        try:
            lng = float(row[5].strip())
            lat = float(row[6].strip())
        except ValueError:
            continue

        if not (21.5 <= lat <= 26.5 and 118.0 <= lng <= 122.5):
            continue

        direct = row[7].strip().replace(",", "、")
        digits = "".join(ch for ch in row[8].strip() if ch.isdigit())
        limit = int(digits) if digits else 60
        limit = max(20, min(120, limit))

        dedup_key = f"{round(lat, 4)}_{round(lng, 4)}_{limit}"
        if dedup_key in seen_keys:
            continue
        seen_keys.add(dedup_key)

        cam_type = classify_camera_type(address, direct)
        cleaned_rows.append([city, region, address, dept, branch, f"{lng:.6f}", f"{lat:.6f}", direct, str(limit)])
        cameras_json.append({
            "id": f"NPA-{len(cameras_json) + 1}",
            "city": city,
            "region": region,
            "address": address,
            "dept": dept,
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "direct": direct,
            "limit": limit,
            "type": cam_type,
        })

    # 加入重點區間測速與科技執法補充點
    for sup in SUPPLEMENTAL_INTERVAL_AND_TECH_CAMERAS:
        city, region, address, dept, branch, lng_s, lat_s, direct, limit_s = sup
        lat, lng, limit = float(lat_s), float(lng_s), int(limit_s)
        dedup_key = f"{round(lat, 3)}_{round(lng, 3)}"
        if any(abs(c["lat"] - lat) < 0.002 and abs(c["lng"] - lng) < 0.002 for c in cameras_json):
            continue
        cam_type = classify_camera_type(address, direct)
        cleaned_rows.append([city, region, address, dept, branch, f"{lng:.6f}", f"{lat:.6f}", direct, str(limit)])
        cameras_json.append({
            "id": f"SUP-{len(cameras_json) + 1}",
            "city": city,
            "region": region,
            "address": address,
            "dept": dept,
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "direct": direct,
            "limit": limit,
            "type": cam_type,
        })

    # 輸出標準化 CSV
    csv_buf = io.StringIO()
    csv_buf.write("CityName,RegionName,Address,DeptNm,BranchNm,Longitude,Latitude,direct,limit\n")
    csv_buf.write("設置縣市,設置市區鄉鎮,設置地址,管轄警局,管轄分局,經度,緯度,拍攝方向,速限\n")
    for r in cleaned_rows:
        csv_buf.write(",".join(r) + "\n")
    final_csv_bytes = csv_buf.getvalue().encode("utf-8")

    (CLOUD_DIR / "taiwan_cameras_latest.csv").write_bytes(final_csv_bytes)
    (ASSETS_DIR / "taiwan_cameras_seed.csv").write_bytes(final_csv_bytes)
    (CLOUD_DIR / "taiwan_cameras_latest.json").write_text(
        json.dumps({"updatedAt": now_tw_iso(), "count": len(cameras_json), "cameras": cameras_json}, ensure_ascii=False),
        encoding="utf-8",
    )

    interval_cnt = sum(1 for c in cameras_json if c["type"] == "INTERVAL")
    tech_cnt = sum(1 for c in cameras_json if c["type"] == "TECH")
    print(f"  -> 測速圖資整理完成：共 {len(cameras_json)} 支（含區間測速 {interval_cnt} 處、科技執法 {tech_cnt} 處）")
    return final_csv_bytes, len(cameras_json), interval_cnt, tech_cnt


def update_road_events():
    print("[2/3] 正在從內政部警廣拉取全國即時道路施工與封閉管制 Open Data...")
    raw_json_str = ""
    try:
        raw_json_str = fetch_url_text(PBS_EVENTS_URL)
        print(f"  -> 成功下載警廣即時路況 JSON ({len(raw_json_str):,} bytes)")
    except Exception as e:
        print(f"  -> 警告：警廣 API 連線異常 ({e})，改讀取本地種子檔案作為基底")
        seed_path = ASSETS_DIR / "taiwan_road_events_seed.json"
        if seed_path.exists():
            raw_json_str = seed_path.read_text(encoding="utf-8-sig")

    start_idx = raw_json_str.find("{")
    data = json.loads(raw_json_str[start_idx:]) if start_idx >= 0 else {"result": []}
    records = data.get("result", [])

    cleaned_records = []
    closure_cnt = 0
    construction_cnt = 0

    for item in records:
        comment = re.sub(r"<br\s*/?>", " ", str(item.get("comment", ""))).strip()
        comment = re.sub(r"\s+", " ", comment)
        # 過濾已經完全排除的舊事故（保留施工、管制、封閉與進行中事件）
        road_type = str(item.get("roadtype", "")).strip()
        is_cleared = any(k in comment for k in ("已排除", ":排除", "：排除", "恢復通行", "解除管制", "狀況排除"))
        if is_cleared and road_type not in ("道路施工", "交通管制", "災變"):
            continue

        area = str(item.get("areaNm", "")).strip()
        road = str(item.get("road", "")).strip()
        src = str(item.get("srcdetail", "")).strip()
        try:
            lng = float(str(item.get("x1", "0")).strip() or 0.0)
            lat = float(str(item.get("y1", "0")).strip() or 0.0)
        except ValueError:
            lat, lng = 0.0, 0.0

        lat, lng = refine_coarse_coord(lat, lng, area, road, comment, src)
        if not (21.5 <= lat <= 26.5 and 118.0 <= lng <= 122.5):
            continue

        item["x1"] = f"{lng:.6f}"
        item["y1"] = f"{lat:.6f}"
        item["comment"] = comment

        combined = f"{road_type} {area} {road} {comment}"
        if any(k in combined for k in ("封閉", "管制", "阻斷", "禁止通行", "單線雙向")) or road_type in ("交通管制", "災變"):
            closure_cnt += 1
        elif road_type == "道路施工" or any(k in combined for k in ("施工", "工程", "銑鋪", "緩撞車", "車道縮減", "養護")):
            construction_cnt += 1

        cleaned_records.append(item)

    out_obj = {
        "updatedAt": now_tw_iso(),
        "total": len(cleaned_records),
        "result": cleaned_records,
    }
    final_json_bytes = json.dumps(out_obj, ensure_ascii=False, indent=2).encode("utf-8")

    (CLOUD_DIR / "taiwan_road_events_latest.json").write_bytes(final_json_bytes)
    (ASSETS_DIR / "taiwan_road_events_seed.json").write_bytes(final_json_bytes)

    print(f"  -> 路況事件整理完成：共 {len(cleaned_records)} 筆有效事件（⛔ 封閉管制 {closure_cnt} 處、🚧 道路施工 {construction_cnt} 處）")
    return final_json_bytes, len(cleaned_records), closure_cnt, construction_cnt


def now_tw_iso() -> str:
    tz_tw = datetime.timezone(datetime.timedelta(hours=8))
    return datetime.datetime.now(tz_tw).strftime("%Y/%m/%d %H:%M")


def now_version_code() -> str:
    tz_tw = datetime.timezone(datetime.timedelta(hours=8))
    return datetime.datetime.now(tz_tw).strftime("%Y.%m.%d.%H%M")


def main():
    CLOUD_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    cam_bytes, cam_total, interval_cnt, tech_cnt = update_cameras()
    ev_bytes, ev_total, closure_cnt, construction_cnt = update_road_events()

    manifest = {
        "version": now_version_code(),
        "updatedAt": now_tw_iso(),
        "cameraCount": cam_total,
        "intervalCameraCount": interval_cnt,
        "techLawCameraCount": tech_cnt,
        "eventCount": ev_total,
        "closureCount": closure_cnt,
        "constructionCount": construction_cnt,
        "sha256Cameras": hashlib.sha256(cam_bytes).hexdigest(),
        "sha256Events": hashlib.sha256(ev_bytes).hexdigest(),
        "files": {
            "camerasCsv": "taiwan_cameras_latest.csv",
            "camerasJson": "taiwan_cameras_latest.json",
            "roadEventsJson": "taiwan_road_events_latest.json",
        },
    }

    manifest_path = CLOUD_DIR / "manifest.json"
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    manifest_path.write_bytes(manifest_bytes)
    (ASSETS_DIR / "cloud_manifest.json").write_bytes(manifest_bytes)

    print(f"[3/3] 已產生雲端版本清單 manifest.json (版本號: {manifest['version']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
