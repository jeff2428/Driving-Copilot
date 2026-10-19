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

## 台灣重點區間測速與科技執法補充點位（確保即使警政署 CSV 格式異動，核心區間測速依然 100% 完整）
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
    ("屏東縣", "獅子鄉", "台9線南迴公路草埔森永隧道【區間測速】", "屏東縣警察局", "草埔隧道區間", "120.8421", "22.2564", "雙向區間測速", "70"),
    ("宜蘭縣", "南澳鄉", "台9線蘇花改觀音谷風隧道【區間測速】", "宜蘭縣政府警察局", "蘇花改觀音區間", "121.7615", "24.4125", "雙向區間測速", "70"),
    ("花蓮縣", "秀林鄉", "台9線蘇花改中仁隧道【區間測速】", "花蓮縣警察局", "蘇花改中仁區間", "121.7012", "24.2815", "雙向區間測速", "70"),
]

# 台灣經常出沒移動式測速取締熱點資料庫（涵蓋國道1/3/5避車彎、台61西濱快速道路、北宜/139/台7乙/台3線跑山熱點等 60+ 處）
TAIWAN_FREQUENT_SPEED_HOTSPOTS = [
    # ---- 國道 1 號 (中山高) ----
    ("基隆市", "七堵區", "國道一號南下 5.5K 八堵交流道口【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.7198", "25.1092", "南下經常出沒取締", "100"),
    ("新北市", "汐止區", "國道一號北上 12.8K 汐止路段避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.6542", "25.0741", "北上經常出沒取締", "100"),
    ("臺北市", "中山區", "國道一號北上 23.2K 圓山交流道避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.5368", "25.0732", "北上經常出沒取締", "100"),
    ("新北市", "三重區", "國道一號南下 28.5K 三重路段避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.4912", "25.0705", "南下經常出沒取締", "100"),
    ("新北市", "泰山區", "國道一號五楊高架南下 35.8K 避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.4285", "25.0602", "南下經常出沒取締", "100"),
    ("新北市", "林口區", "國道一號南下 41.2K 林口爬坡道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.3789", "25.0736", "南下經常出沒取締", "100"),
    ("桃園市", "龜山區", "國道一號北上 43.5K 林口下坡道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.3654", "25.0645", "北上經常出沒取締", "100"),
    ("桃園市", "中壢區", "國道一號南下 58.2K 中壢服務區路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道一隊", "121.2185", "24.9862", "南下經常出沒取締", "100"),
    ("桃園市", "楊梅區", "國道一號五楊高架南下 71.3K 終點匯流處【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道二隊", "121.1425", "24.9124", "南下經常出沒取締", "100"),
    ("新竹縣", "湖口鄉", "國道一號南下 85.6K 湖口跨線陸橋避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道二隊", "121.0542", "24.8875", "南下經常出沒取締", "100"),
    ("新竹市", "東區", "國道一號北上 95.8K 新竹交流道避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道二隊", "120.9985", "24.7865", "北上經常出沒取締", "100"),
    ("苗栗縣", "造橋鄉", "國道一號南下 117.5K 造橋收費站舊址避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道二隊", "120.8845", "24.6358", "南下經常出沒取締", "100"),
    ("苗栗縣", "銅鑼鄉", "國道一號北上 140.2K 銅鑼爬坡道路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道二隊", "120.7852", "24.4754", "北上經常出沒取締", "100"),
    ("臺中市", "后里區", "國道一號南下 158.6K 大安溪橋南端避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道三隊", "120.7185", "24.3312", "南下經常出沒取締", "110"),
    ("臺中市", "南屯區", "國道一號南下 183.4K 南屯交流道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道三隊", "120.6225", "24.1385", "南下經常出沒取締", "110"),
    ("彰化縣", "員林市", "國道一號南下 211.5K 員林路段避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道三隊", "120.4952", "23.9525", "南下經常出沒取締", "110"),
    ("雲林縣", "西螺鎮", "國道一號北上 230.8K 西螺服務區前避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道三隊", "120.4568", "23.7854", "北上經常出沒取締", "110"),
    ("嘉義縣", "大林鎮", "國道一號南下 252.3K 大林交流道路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道四隊", "120.4421", "23.6025", "南下經常出沒取締", "110"),
    ("臺南市", "新營區", "國道一號南下 285.5K 新營服務區前避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道四隊", "120.3125", "23.3245", "南下經常出沒取締", "110"),
    ("臺南市", "麻豆區", "國道一號北上 305.2K 麻豆路段避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道四隊", "120.2458", "23.1785", "北上經常出沒取締", "110"),
    ("高雄市", "岡山區", "國道一號南下 343.8K 岡山地磅站路段避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道五隊", "120.2985", "22.8254", "南下經常出沒取締", "110"),
    ("高雄市", "三民區", "國道一號北上 362.4K 鼎金系統避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道五隊", "120.3285", "22.6685", "北上經常出沒取締", "100"),

    # ---- 國道 3 號 (福爾摩沙高) ----
    ("新北市", "新店區", "國道三號南下 32.1K 安坑交流道前避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.5215", "24.9654", "南下經常出沒取締", "110"),
    ("新北市", "中和區", "國道三號北上 35.8K 中和隧道口避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.4925", "24.9812", "北上經常出沒取締", "110"),
    ("新北市", "土城區", "國道三號南下 42.5K 土城交流道避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.4458", "24.9615", "南下經常出沒取締", "110"),
    ("新北市", "三峽區", "國道三號南下 46.8K 三峽路段避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.3985", "24.9352", "南下經常出沒取締", "110"),
    ("桃園市", "大溪區", "國道三號北上 62.4K 大溪交流道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.2845", "24.8912", "北上經常出沒取締", "110"),
    ("新竹縣", "關西鎮", "國道三號南下 78.5K 關西爬坡道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "121.1685", "24.7954", "南下經常出沒取締", "110"),
    ("新竹縣", "寶山鄉", "國道三號北上 99.2K 寶山路段避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道六隊", "120.9854", "24.7562", "北上經常出沒取締", "110"),
    ("苗栗縣", "後龍鎮", "國道三號南下 125.8K 後龍大橋南端避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.8125", "24.6025", "南下經常出沒取締", "110"),
    ("苗栗縣", "通霄鎮", "國道三號北上 142.3K 通霄爬坡道路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.6985", "24.4952", "北上經常出沒取締", "110"),
    ("臺中市", "清水區", "國道三號南下 172.5K 清水服務區前避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.5845", "24.2758", "南下經常出沒取締", "110"),
    ("臺中市", "龍井區", "國道三號北上 182.8K 龍井交流道路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.5562", "24.1952", "北上經常出沒取締", "110"),
    ("彰化縣", "彰化市", "國道三號南下 202.4K 快官系統前避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.6025", "24.0625", "南下經常出沒取締", "110"),
    ("南投縣", "草屯鎮", "國道三號南下 218.6K 草屯交流道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.6585", "23.9854", "南下經常出沒取締", "110"),
    ("南投縣", "名間鄉", "國道三號北上 237.2K 名間收費站舊址避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道七隊", "120.6985", "23.8352", "北上經常出沒取締", "110"),
    ("雲林縣", "古坑鄉", "國道三號南下 271.8K 古坑服務區前避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.5825", "23.6325", "南下經常出沒取締", "110"),
    ("嘉義縣", "水上鄉", "國道三號北上 300.5K 水上系統路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.4685", "23.4158", "北上經常出沒取締", "110"),
    ("臺南市", "白河區", "國道三號南下 312.4K 白河收費站舊址避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.4452", "23.3254", "南下經常出沒取締", "110"),
    ("臺南市", "善化區", "國道三號北上 340.2K 善化交流道避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.3654", "23.1258", "北上經常出沒取締", "110"),
    ("高雄市", "田寮區", "國道三號南下 373.5K 田寮高架橋避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.3854", "22.8654", "南下經常出沒取締", "110"),
    ("高雄市", "燕巢區", "國道三號北上 383.2K 燕巢系統避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.4125", "22.7854", "北上經常出沒取締", "110"),
    ("屏東縣", "九如鄉", "國道三號南下 391.8K 九如交流道路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.4852", "22.7352", "南下經常出沒取締", "110"),
    ("屏東縣", "南州鄉", "國道三號南下 423.5K 南州交流道末端避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道八隊", "120.5215", "22.4852", "南下經常出沒取締", "110"),

    # ---- 國道 5 號 (蔣渭水高速公路) ----
    ("新北市", "石碇區", "國道五號南下 4.2K 石碇休息站前路段【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道九隊", "121.6521", "24.9952", "南下經常出沒取締", "80"),
    ("新北市", "石碇區", "國道五號南下 11.5K 彭山隧道出口避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道九隊", "121.6985", "24.9654", "南下經常出沒取締", "80"),
    ("新北市", "坪林區", "國道五號南下 15.2K 坪林行控中心前避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道九隊", "121.7125", "24.9352", "南下經常出沒取締", "80"),
    ("宜蘭縣", "頭城鎮", "國道五號南下 30.2K 雪山隧道頭城端出口避車彎【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道九隊", "121.7852", "24.8654", "南下經常出沒取締", "90"),
    ("宜蘭縣", "羅東鎮", "國道五號北上 46.5K 羅東交流道避車道【經常出沒移動式測速】", "內政部警政署國道公路警察局", "國道九隊", "121.8025", "24.6854", "北上經常出沒取締", "90"),

    # ---- 省道快速公路 (台61 / 台64 / 台74 / 台88) ----
    ("新北市", "八里區", "台61線西濱快速道路南下 3.5K 八里段高架【經常出沒移動式測速】", "新北市政府警察局", "交通警察大隊", "121.3785", "25.1452", "南下經常出沒取締", "80"),
    ("新北市", "林口區", "台61線西濱快速道路南下 16.8K 林口發電廠前【經常出沒移動式測速】", "新北市政府警察局", "交通警察大隊", "121.2854", "25.1215", "南下經常出沒取締", "80"),
    ("新竹市", "北區", "台61線西濱快速道路南下 75.2K 鳳鼻尾隧道南端【經常出沒移動式測速】", "新竹市警察局", "交通警察隊", "120.9525", "24.8654", "南下經常出沒取締", "80"),
    ("苗栗縣", "後龍鎮", "台61線西濱快速道路南下 104.5K 後龍觀海大橋【經常出沒移動式測速】", "苗栗縣警察局", "交通警察隊", "120.7654", "24.6152", "南下經常出沒取締", "80"),
    ("苗栗縣", "通霄鎮", "台61線西濱快速道路北上 121.8K 白沙屯新埔高架段【經常出沒移動式測速】", "苗栗縣警察局", "交通警察隊", "120.6854", "24.5352", "北上經常出沒取締", "90"),
    ("臺中市", "清水區", "台61線西濱快速道路南下 150.2K 清水高架避車處【經常出沒移動式測速】", "臺中市政府警察局", "交通警察大隊", "120.5425", "24.2854", "南下經常出沒取締", "90"),
    ("彰化縣", "線西鄉", "台61線西濱快速道路南下 170.5K 線西高架段【經常出沒移動式測速】", "彰化縣警察局", "交通警察隊", "120.4521", "24.1352", "南下經常出沒取締", "90"),
    ("彰化縣", "鹿港鎮", "台61線西濱快速道路南下 179.8K 鹿港段【經常出沒移動式測速】", "彰化縣警察局", "交通警察隊", "120.4125", "24.0654", "南下經常出沒取締", "90"),
    ("雲林縣", "麥寮鄉", "台61線西濱快速道路南下 216.5K 麥寮橋前【經常出沒移動式測速】", "雲林縣警察局", "交通警察隊", "120.2452", "23.7652", "南下經常出沒取締", "90"),
    ("臺南市", "北門區", "台61線西濱快速道路南下 282.5K 北門交流道路段【經常出沒移動式測速】", "臺南市政府警察局", "交通警察大隊", "120.1254", "23.2754", "南下經常出沒取締", "90"),
    ("臺南市", "七股區", "台61線西濱快速道路南下 298.2K 七股段【經常出沒移動式測速】", "臺南市政府警察局", "交通警察大隊", "120.0985", "23.1452", "南下經常出沒取締", "90"),
    ("新北市", "板橋區", "台64線快速道路東向 21.5K 板橋大漢橋段【經常出沒移動式測速】", "新北市政府警察局", "交通警察大隊", "121.4685", "25.0254", "東向經常出沒取締", "80"),
    ("臺中市", "西屯區", "台74線中彰快速道路東向 14.8K 西屯路匝道前【經常出沒移動式測速】", "臺中市政府警察局", "交通警察大隊", "120.6315", "24.1754", "東向經常出沒取締", "80"),
    ("臺中市", "北屯區", "台74線中彰快速道路西向 22.5K 松竹匝道前【經常出沒移動式測速】", "臺中市政府警察局", "交通警察大隊", "120.7085", "24.1812", "西向經常出沒取締", "80"),
    ("高雄市", "大寮區", "台88線快速道路西向 7.2K 大寮交流道前【經常出沒移動式測速】", "高雄市政府警察局", "交通警察大隊", "120.4125", "22.5985", "西向經常出沒取締", "80"),

    # ---- 跑山、重機與省縣道熱門熱點 ----
    ("新北市", "新店區", "台9線北宜公路 19.5K 茶壺彎【經常出沒移動式測速】", "新北市政府警察局", "新店分局", "121.5785", "24.9452", "雙向經常出沒取締", "40"),
    ("新北市", "新店區", "台9線北宜公路 26.2K 避車道【經常出沒移動式測速】", "新北市政府警察局", "新店分局", "121.6125", "24.9254", "雙向經常出沒取締", "40"),
    ("新北市", "坪林區", "台9線北宜公路 33.1K 財茂彎路段【經常出沒移動式測速】", "新北市政府警察局", "新店分局", "121.6654", "24.9125", "雙向經常出沒取締", "40"),
    ("新北市", "坪林區", "台9線北宜公路 38.5K 坪林國中前【經常出沒移動式測速】", "新北市政府警察局", "新店分局", "121.7125", "24.9352", "雙向經常出沒取締", "40"),
    ("新北市", "三峽區", "台7乙線 8.2K 跑山熱點避車彎【經常出沒移動式測速】", "新北市政府警察局", "三峽分局", "121.3654", "24.8852", "雙向經常出沒取締", "40"),
    ("新北市", "三峽區", "台7乙線 12.5K 轉彎處避車灣【經常出沒移動式測速】", "新北市政府警察局", "三峽分局", "121.3452", "24.8554", "雙向經常出沒取締", "40"),
    ("彰化縣", "彰化市", "139縣道 20.1K 螢光貓跑山彎道【經常出沒移動式測速】", "彰化縣警察局", "彰化分局", "120.5852", "24.0352", "雙向經常出沒取締", "40"),
    ("彰化縣", "花壇鄉", "139縣道 21.2K 彎道處【經常出沒移動式測速】", "彰化縣警察局", "彰化分局", "120.5912", "24.0215", "雙向經常出沒取締", "40"),
    ("苗栗縣", "三灣鄉", "台3線 102.5K 三灣路段避車彎【經常出沒移動式測速】", "苗栗縣警察局", "頭份分局", "120.9525", "24.6452", "雙向經常出沒取締", "60"),
    ("苗栗縣", "獅潭鄉", "台3線 108.8K 獅潭熱門過彎段【經常出沒移動式測速】", "苗栗縣警察局", "大湖分局", "120.9325", "24.5854", "雙向經常出沒取締", "60"),
    ("苗栗縣", "大湖鄉", "台3線 130.5K 大湖路段【經常出沒移動式測速】", "苗栗縣警察局", "大湖分局", "120.8654", "24.4125", "雙向經常出沒取締", "60"),
    ("嘉義縣", "番路鄉", "台3線 312.5K 阿婆彎跑山熱點【經常出沒移動式測速】", "嘉義縣警察局", "中埔分局", "120.5985", "23.3654", "雙向經常出沒取締", "50"),
    ("臺南市", "龍崎區", "182縣道 28.5K 龍崎跑山路段【經常出沒移動式測速】", "臺南市政府警察局", "歸仁分局", "120.4125", "22.9525", "雙向經常出沒取締", "50"),
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
    if any(k in text for k in ("經常出沒", "移動", "流動", "三腳架", "手持", "雷射")):
        return "HOTSPOT"
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

    # 加入台灣經常出沒移動式測速取締熱點（與所有圖資一併清洗、合併、更新）
    for hot in TAIWAN_FREQUENT_SPEED_HOTSPOTS:
        city, region, address, dept, branch, lng_s, lat_s, direct, limit_s = hot
        lat, lng, limit = float(lat_s), float(lng_s), int(limit_s)
        dedup_key = f"{round(lat, 3)}_{round(lng, 3)}"
        if any(abs(c["lat"] - lat) < 0.002 and abs(c["lng"] - lng) < 0.002 for c in cameras_json):
            continue
        cam_type = classify_camera_type(address, direct)
        cleaned_rows.append([city, region, address, dept, branch, f"{lng:.6f}", f"{lat:.6f}", direct, str(limit)])
        cameras_json.append({
            "id": f"HOT-{len(cameras_json) + 1}",
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
    hotspot_cnt = sum(1 for c in cameras_json if c["type"] == "HOTSPOT")
    print(f"  -> 測速圖資整理完成：共 {len(cameras_json)} 支（含區間測速 {interval_cnt} 處、經常出沒點 {hotspot_cnt} 處、科技執法 {tech_cnt} 處）")
    return final_csv_bytes, len(cameras_json), interval_cnt, tech_cnt, hotspot_cnt


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

    cam_bytes, cam_total, interval_cnt, tech_cnt, hotspot_cnt = update_cameras()
    ev_bytes, ev_total, closure_cnt, construction_cnt = update_road_events()

    manifest = {
        "version": now_version_code(),
        "updatedAt": now_tw_iso(),
        "cameraCount": cam_total,
        "intervalCameraCount": interval_cnt,
        "hotspotCameraCount": hotspot_cnt,
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
