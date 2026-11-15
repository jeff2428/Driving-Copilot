# 國道警察經常出沒點任務拆解表 (doc/task/police-hotspot.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》之規範制定。任務涵蓋國道警察出沒點圖資解析、三重防誤報演算法、超速分級視聽呈現與實境模擬驗證。

---

## 任務清單表格 (Task Breakdown Matrix)

| Task ID | Depends On | Allowed Files | Test Command | 預估時間 | 狀態 |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **TASK-HOTSPOT-01** | None | `cloud_data/taiwan_cameras_latest.csv`<br>`app/src/main/java/tw/roadguard/aegis/service/CameraAlertPolicy.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/AlertArbitrator.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/OverlayDisplayState.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/FloatingOverlayView.kt`<br>`app/src/main/java/tw/roadguard/aegis/service/RoadGuardService.kt`<br>`app/src/test/java/tw/roadguard/aegis/service/CameraAlertPolicyTest.kt`<br>`tests/test_police_hotspot.py` | `make.bat check` | 40 分鐘 | `✅ PASS` |

---

## 任務詳細定義 (Task Specifications)

### 📌 TASK-HOTSPOT-01: 國道警察與雷射槍出沒點智慧預警系統
- **目標**: 達成高速公路非固定式測速、雷射槍執法與避車彎警車巡邏點之精準預警，徹底消除對向車道與平面側車道誤報。
- **具體實作動作**:
  1. **圖資與識別**: 在 `CameraAlertPolicy.isFreewayPoliceHotspot()` 精確判定國道警察熱點，支援 58+ 處國1、國3、國5、台61、台74熱門點位。
  2. **三重防誤報演算法**:
     - 航向角比對：南下/北向嚴格過濾反向避車彎。
     - 走廊橫向偏距限制：`CameraAlertPolicy.maxCrossTrackMeters()` 針對國道熱點嚴格限縮為 $35.0\,\text{m}$，阻絕隔壁平面側車道。
     - 向量通過門閥：車輛越過避車彎後即刻靜音解除。
  3. **超速分級視聽呈現**:
     - 未超速：柔和播報「注意，前方 X 公尺有警車經常出沒點，速限 Y」；懸浮窗顯示「🚔 警車出沒」精簡膠囊。
     - 超速：最高優先級搶佔「警告！您已超速，前方 X 公尺有警車經常出沒點，速限 Y，請立即減速！」；懸浮窗觸發紅藍雙色警燈微光呼吸閃爍。
  4. **模擬試駕與測試集**:
     - 於 `RoadGuardService` 新增模擬路線 7（國道一號南下 158.6K 后里避車彎超速實測）。
     - 建立 `tests/test_police_hotspot.py` 測試套件，涵蓋圖資完備性、反向阻絕、35m 走廊篩選、超速仲裁與向量解除。
- **DoD (驗收條件)**:
  - 單元測試 `tests/test_police_hotspot.py` 全數 PASS。
  - Kotlin 單元測試 `CameraAlertPolicyTest` 涵蓋熱點識別與 35m 走廊。
  - 執行 `make.bat check` PASS (26 Tests, Parity, Swift Validation, Exit Code 0)。
