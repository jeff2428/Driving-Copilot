# 科技座艙美化與效能升級任務拆解表 (doc/task/cockpit-ui-upgrade.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》制定，記錄 `TASK-COCKPIT-02` 之契約、允許修改檔案與驗收標準。

---

## 任務清單表格 (Task Breakdown Matrix)

| Task ID | Depends On | Allowed Files | Test Command | 預估時間 | 狀態 |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **TASK-COCKPIT-02** | TASK-DR-01, TASK-ARB-03, TASK-AUDIO-04, TASK-HOTSPOT-01 | `app/src/main/java/tw/roadguard/aegis/model/Models.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/IntervalSpeedTracker.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/OverlayDisplayState.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/FloatingOverlayView.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/VoiceAlertEngine.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/RoadGuardService.kt`<br>`app/src/main/java/tw/roadguard/aegis/ui/MainActivity.kt`<br>`app/src/main/java/tw/roadguard/aegis/ads/ProLicenseManager.kt`<br>`app/build.gradle.kts`<br>`scripts/publish_release.py`<br>`app/src/main/assets/radar_map.html`<br>`ios/RoadGuard/Resources/radar_map.html`<br>`app/src/test/java/tw/roadguard/aegis/service/FloatingOverlayStateTest.kt`<br>`tests/test_cockpit_upgrade.py`<br>`doc/proposal.md`, `doc/data-contracts.md`, `doc/traceability.md`, `doc/progress.md`, `doc/task/cockpit-ui-upgrade.md`, `AGENTS.md` | `make.bat check` | 60 分鐘 | `✅ PASS` |

---

## 任務詳細定義 (Task Specifications)

### 📌 TASK-COCKPIT-02: 科技座艙玻璃擬態美化、懸浮膠囊升級與底層效能優化
- **目標**: 全面升級 Android 主畫面與懸浮窗視覺質感，補齊通話語音策略設定與 7 大模擬路線選擇器，並優化 `ToneGenerator` 音效池與 WebView 差異推播效能。
- **具體實作動作**:
  1. **資料契約擴充**: 於 `RadarSnapshot`、`IntervalSpeedTracker.ZoneStatus` 與 `OverlayDisplayConfig` 擴充進度條比例 (`progressFraction`)、區間速限與盲區推估狀態 (`isDeadReckoning`)。
  2. **懸浮膠囊升級 (`FloatingOverlayView` & `RoadGuardService`)**: 加入逼近/區間進度條、`📡 隧道慣性推估` 徽章、玻璃漸層底色、平滑呼吸閃爍動畫、`AlertArbitrator` 接線，以及懸浮快捷列「一鍵靜音切換」鈕。
  3. **主畫面座艙升級 (`MainActivity`)**: 玻璃擬態卡片與漸層時速圈、車頭方位角膠囊、頂部預警卡進度條與盲區推估顯示、區間測速展開邏輯修正、設定頁「通話中語音策略」切換器、7 條實境模擬路線選擇器，以及地圖簽章差異推播減載。
  4. **地圖美化 (`radar_map.html`)**: 目標脈衝光暈動畫與夜間磨砂玻璃彈窗，Android/iOS 雙端同步。
  5. **音效單例池 (`VoiceAlertEngine`)**: 採用專用 `HandlerThread` 複用單一 `ToneGenerator` 實例並於 `shutdown()` 釋放資源。
- **DoD (驗收條件)**:
  - `FloatingOverlayStateTest.kt` 新增進度條比例與 `isDeadReckoning` 傳遞測試並通過。
  - `tests/test_cockpit_upgrade.py` 全數 PASS。
  - 執行 `cmd /c make.bat check` 全數品質閘門 PASS (Exit Code 0)。
