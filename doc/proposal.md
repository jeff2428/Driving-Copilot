# 產品需求提案書 (doc/proposal.md)

## 1. 專案背景與目標 (Project Context & Objectives)
- **專案名稱**: 神盾路況行車助手 (TW-RoadGuard-Aegis / Driving-Copilot)
- **對標產品**: [神盾測速照相 (inmirai.com)](https://www.inmirai.com/)
- **迭代目標**:
  1. 對標業界標竿《神盾測速照相》，解決隧道斷訊推估、進程意外被殺還原、多重路況超速仲裁、通話語音焦點等關鍵痛點。
  2. 依「單一平台先行驗證」原則，以 **Android 平台** 作為第一階段落地與實車驗證目標。
  3. 產出雙平台應用程式商店（Google Play / App Store）上架所需的合規設定與法規文件（隱私權政策、顯著揭露宣告、商店文案）。

---

## 2. 核心功能與行為規格 (Functional Specifications)

### 2.1 隧道內區間測速慣性推估 (Dead Reckoning for Interval Speed)
- **觸發條件**: 於區間測速區間內，無 GPS 訊號更新超過 3 秒。
- **推估演算法**:
  - 取失聯前最後 5 秒的加權平均行車時速 $V_{last}$ 作為推估車速。
  - 每秒模擬行駛推移：$\Delta D = V_{last} \times 1.0\text{s}$。
  - 即時計算預估平均速率 $V_{est} = \frac{D_{traveled} + \Delta D_{accum}}{T_{elapsed}}$。
  - 動態倒數「安全通過剩餘秒數 ($T_{remain}$)」並更新懸浮視窗。
  - 若失聯超過 15 分鐘（900 秒），標記為超時並安全退出推估模式。
  - 重新取得 GPS 定位時，以真實座標與時間平滑校正推估值。

### 2.2 區間測速狀態持久化與進程重啟復原 (Process Death Recovery)
- **觸發條件**: 系統在背景強制殺除 `RoadGuardService`（Low Memory Killer）。
- **儲存規格**:
  - 於 `IntervalSpeedTracker` 狀態變更時，即時持久化至 `SharedPreferences`:
    - `camera_id`: 區間相機 ID
    - `entry_timestamp`: 進入起點時間戳記
    - `total_distance_meters`: 累計行駛公尺
    - `last_speed_kmh`: 最後已知時速
    - `zone_length_meters`: 該區間全長
  - 在 `RoadGuardService.onCreate()` 啟動時自動檢查儲存快照：
    - 若 $T_{now} - T_{entry} < T_{max\_window}$（通行窗口內），立即自動無縫復原監控與計速。
    - 若已超時，自動清空快照並回歸常態巡航。

### 2.3 超速導向之多重路況仲裁機制 (Speeding-First Arbitration)
- **情境**: 200 公尺內同時存在「科技執法/固定照相」+「區間測速終點」+「道路施工/封閉」。
- **仲裁邏輯**:
  1. **當前車速 > 速限（超速中）**:
     - 判定為最高優先級（Emergency Priority 1）。
     - 強制 Flush 語音佇列，壓制道路施工等次要訊息，播放急促警告音與減速警示：「警告！您已超速，前方有測速照相，請立即減速！」。
  2. **當前車速 $\le$ 速限（未超速）**:
     - 採用 Alert Fusion 智慧融合播報：「前方 200 公尺有科技執法，該路段並有道路施工，請小心行駛」。

### 2.4 通話中語音焦點 (Audio Focus) 策略
- **通話偵測**: 透過 `AudioManager.mode` 偵測是否處於 `MODE_IN_CALL` 或 `MODE_IN_COMMUNICATION`。
- **預設行為**: **模式 B (靜音僅視覺閃爍 - Visual Alert Only)**。
  - 語音自動靜音，絕不干擾電話交談。
  - 懸浮視窗膠囊（Floating Capsule）啟動紅黃雙色動態呼吸閃爍提醒。
- **設定支援**: 使用者可在設定頁面自行切換為「模式 A: 降低通話音量播報 (Ducking)」或「模式 C: 正常音量播報」。

### 2.5 逼近漸近警示音 (Proximity Beep)
- **逼近區間**: 進入相機前 150 公尺內。
- **頻率階梯**:
  - 150m ~ 100m: 間隔 1.0 秒短嗶聲。
  - 100m ~ 50m: 間隔 0.5 秒短嗶聲。
  - 50m ~ 通過點: 間隔 0.25 秒急促嗶聲（若超速則切換為雙音急促警報）。
  - 通過瞬間: 播放清脆通過提示音（Pass Chime）。

### 2.6 科技座艙玻璃擬態美化與懸浮膠囊進度升級 (Cyber-Cockpit UI & Overlay Upgrade)
- **設計風格**: 科技座艙玻璃擬態風（深邃漸層玻璃卡片、動態霓虹光暈邊框、平滑呼吸燈效與高辨識字級）。
- **主畫面座艙升級 (`MainActivity`)**:
  - 左下角動態時速與速限徽章下方新增「車頭方位角膠囊 (`🧭 北 0°`)」，即時呈現行進方位。
  - 頂部預警卡片新增「逼近距離／區間完成進度條」與「隧道盲區推估徽章 (`📡 隧道慣性推估中`)」，並完整支援純區間測速狀態展開顯示。
  - 設定頁補齊「通話中語音策略（靜音閃爍／壓低音量／正常播報）」切換開關。
  - 提供「7 條實境模擬試駕路線快速選擇器」，一鍵體驗各類路況、警車熱點與雪隧場景。
- **系統級懸浮膠囊升級 (`FloatingOverlayView` & `RoadGuardService`)**:
  - 深邃雙色玻璃漸層背景與平滑紅黃/紅藍呼吸燈閃爍動畫。
  - 警示展開時同步顯示逼近/區間進度條與 `📡 隧道慣性推估` 徽章。
  - 點擊展開快捷列新增「一鍵靜音切換 (`🔊 語音開` / `🔇 已靜音`)」，方便駕駛在 Google Maps 畫面快速控音。
- **雷達地圖升級 (`radar_map.html`)**:
  - 鎖定目標加入科技感脈衝動畫 (`@keyframes radarPulse`)，夜間模式地標彈窗升級為深色磨砂玻璃外觀。

### 2.7 音效單例池與地圖差異推播優化 (Audio Pool & Map Diff Optimization)
- **音效防爆音優化 (`VoiceAlertEngine`)**:
  - 採用專用背景 `HandlerThread` 搭配單例 `ToneGenerator`，取代高頻臨時建立執行緒與音訊物件，消除急促逼近音爆音與資源抖動。
- **地圖差異推播減載 (`MainActivity.pushMap`)**:
  - 依座標精度、方位、速度、鎖定目標與地標數量計算狀態簽章，僅於狀態變化或強制刷新時推播至 WebView，大幅降低 GPU/JS 橋接負荷。

---

## 3. 雙平台商店上架準備 (Store Submission Readiness)
1. **法規文檔產出**:
   - `doc/privacy-policy.html`: 符合 Google Play / App Store 隱私權政策規範。
   - `doc/store-listing.md`: 商店描述、標題、宣傳標語、隱私與權限揭露宣告。
2. **Android 權限與宣告規範**:
   - `ACCESS_BACKGROUND_LOCATION` 顯著揭露宣告對話框 UI。
   - `foregroundServiceType="location"` 合規標記。
3. **iOS 權限配置預備**:
   - `Info.plist` 定位權限用途描述修訂與背景模式設定。

---

## 4. 驗收標準與測試矩陣 (Definition of Done)
1. **單元測試 (Unit Tests)**:
   - `IntervalSpeedTrackerTest`: 驗證隧道推估邏輯、超時釋放、進程重啟快照還原。
   - `CameraAlertPolicyTest` & `VoiceAlertEngineTest`: 驗證超速仲裁優先權、通話中靜音/Ducking 模式。
   - 測試指令成功通過並輸出完整終端證據。
2. **跨平台等價性 (Cross-Platform Parity)**:
   - 執行 `python scripts/test_parity.py` 維持 100% 幾何與數學模型一致。
3. **產出物完整性**:
   - `doc/privacy-policy.html` 與 `doc/store-listing.md` 建立完成。
