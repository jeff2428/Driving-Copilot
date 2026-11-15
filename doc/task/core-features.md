# 核心功能任務拆解表 (doc/task/core-features.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》之「Phase 3 任務拆解」規範制定。每個任務時間粒度皆在 15~60 分鐘內，具備明確依賴關聯、檔案修改白名單 (Allowed Files)、終端測試命令與客觀驗收標準 (DoD)。**嚴禁使用模糊動詞**。

---

## 任務清單表格 (Task Breakdown Matrix)

| Task ID | Depends On | Allowed Files | Test Command | 預估時間 |
| :--- | :--- | :--- | :--- | :---: |
| **TASK-DR-01** | None | `app/src/main/java/tw/roadguard/aegis/service/IntervalSpeedTracker.kt`<br>`app/src/test/java/tw/roadguard/aegis/service/IntervalSpeedTrackerTest.kt`<br>`tests/test_interval_dead_reckoning.py` | `make.bat test` | 30 分鐘 |
| **TASK-SNAP-02** | TASK-DR-01 | `app/src/main/java/tw/roadguard/aegis/service/IntervalSpeedTracker.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/RoadGuardService.kt`<br>`app/src/test/java/tw/roadguard/aegis/service/IntervalSpeedTrackerTest.kt` | `make.bat test` | 40 分鐘 |
| **TASK-ARB-03** | None | `app/src/main/java/tw/roadguard/aegis/service/AlertArbitrator.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/CameraAlertPolicy.kt`<br>`app/src/test/java/tw/roadguard/aegis/service/CameraAlertPolicyTest.kt`<br>`tests/test_arbitration.py` | `make.bat test` | 35 分鐘 |
| **TASK-AUDIO-04** | TASK-ARB-03 | `app/src/main/java/tw/roadguard/aegis/service/VoiceAlertEngine.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/FloatingOverlayView.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/RoadGuardService.kt`<br>`app/src/test/java/tw/roadguard/aegis/service/FloatingOverlayStateTest.kt` | `make.bat test` | 45 分鐘 |

---

## 任務詳細定義 (Task Specifications)

### 📌 TASK-DR-01: 實作隧道盲區慣性推估演算法
- **目標**: 解決長隧道 GPS 斷訊時區間測速計算停擺問題。
- **具體實作動作**:
  1. 於 `IntervalSpeedTracker` 新增 `isDeadReckoning` 布林旗標與最後訊號接收時間戳記 `lastGpsTimestampMs`。
  2. 實作 `tickDeadReckoning(nowMs: Long)` 方法：當失聯超過 3 秒時啟動推估，以最後車速加權值每秒推進虛擬行駛里程 $\Delta D$。
  3. 實作安全通行剩餘時間動態倒數：$T_{remain} = \max(0, T_{target} - T_{elapsed})$。
  4. 實作 15 分鐘（900 秒）超時門檻：超過則終止推估並標記 `ERR_DR_TIMEOUT`。
- **DoD (驗收條件)**:
  - 單元測試模擬失聯 60 秒，推估里程與均速計算誤差 $\le 1.0\%$。
  - 單元測試驗證失聯超過 900 秒自動安全退場。
  - 執行 `make.bat test` PASS。

---

### 📌 TASK-SNAP-02: 實作區間狀態持久化與崩潰無縫重啟還原
- **目標**: 解決 Android 背景服務遭系統記憶體回收 (Low Memory Kill) 造成區間工作階段丟失問題。
- **具體實作動作**:
  1. 於 `IntervalSpeedTracker` 實作 `saveSnapshotToPrefs(context: Context)`，將符合 `doc/data-contracts.md` 規範之 JSON 快照原子化寫入 `SharedPreferences`。
  2. 實作 `restoreFromSnapshot(context: Context): Boolean`：檢查快照時間戳記與通行窗口，驗證欄位完整性後無縫重構 `IntervalSpeedTracker` 狀態機。
  3. 實作 `clearSnapshot(context: Context)`，於通過終點或手動重置時清除持久化資料。
  4. 於 `RoadGuardService.onCreate()` 插入快照檢查與還原進入點。
- **DoD (驗收條件)**:
  - 單元測試驗證序列化與反序列化資料完全對齊。
  - 模擬進程重啟後直接呼叫還原，狀態機無縫接續先前的均速與累積時間。
  - 執行 `make.bat test` PASS。

---

### 📌 TASK-ARB-03: 建立超速優先多事件仲裁引擎 (AlertArbitrator)
- **目標**: 解決 200m 內相機、科技執法與道路施工併發時的語音轟炸與警示優先級混亂問題。
- **具體實作動作**:
  1. 新建 `AlertArbitrator.kt`，宣告 `ArbitrationInput`、`ArbitrationOutput`、`AlertPriority` 與 `InCallAlertMode`。
  2. 實作 `arbitrate(input: ArbitrationInput): ArbitrationOutput` 決定性函數。
  3. 實作優先級邏輯：當即時車速 > 速限時，強制賦予最高等級 `EMERGENCY_OVERSPEED` (Priority 1)，設定 `isFlushQueue = true`、`isUrgentTone = true`。
  4. 實作未超速融合邏輯：相機與施工併發時，生成單一組合播報字串，消除重複提示。
- **DoD (驗收條件)**:
  - 單元測試覆蓋超速搶佔、未超速融合、純施工、純相機 4 種情境。
  - 執行 `make.bat test` PASS。

---

### 📌 TASK-AUDIO-04: 實作通話語音焦點策略與逼近漸近音階梯
- **目標**: 實作通話中靜音視覺閃爍體驗，以及通過相機前 150m 逼近音階梯。
- **具體實作動作**:
  1. 於 `VoiceAlertEngine` 實作通話狀態監聽：呼叫 `AudioManager.mode` 判定是否為 `MODE_IN_CALL` 或 `MODE_IN_COMMUNICATION`。
  2. 於 `VoiceAlertEngine` 新增 `inCallAlertMode` 偏好設定（預設 `InCallAlertMode.MUTE_VISUAL`）。
  3. 實作通話中靜音與回調：若處於通話且模式為靜音，阻擋 TTS 發聲，發送通知至 `FloatingOverlayView` 啟動視覺閃爍。
  4. 於 `FloatingOverlayView` 實作邊框呼吸閃爍動畫狀態。
  5. 於 `VoiceAlertEngine` 實作 `ProximityBeepController`，依據 150m/100m/50m 階梯排程發出 `ToneGenerator` 音效。
- **DoD (驗收條件)**:
  - 模擬通話中狀態下，TTS 播報次數為 0，懸浮窗閃爍標記為 true。
  - 模擬進入 150m 內能正確計算並排程對應之音效間隔。
  - 執行 `make.bat test` PASS。
