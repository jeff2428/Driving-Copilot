# 需求可追溯性矩陣 (doc/traceability.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》建立，確保從原始需求提案、架構契約、執行任務至自動化驗證測試之間具備 100% 雙向可追溯性，杜絕無需求實作（Scope Creep）與無測試程式碼。

---

## 雙向追溯矩陣 (Traceability Matrix)

| 需求章節 (Proposal) | 架構契約 (Contracts) | 執行任務 ID (Task ID) | 驗證測試 (Verification Tests) | 驗收狀態 |
| :--- | :--- | :--- | :--- | :---: |
| **§ 2.1 隧道盲區慣性推估** | `IDeadReckoningEngine`<br>`DeadReckoningTickResult` | **TASK-DR-01** | `tests/test_interval_dead_reckoning.py`<br>`IntervalSpeedTrackerTest.kt` | `✅ PASS` |
| **§ 2.2 區間狀態持久化還原** | `IntervalSessionSnapshot` (v1 JSON) | **TASK-SNAP-02** | `tests/test_interval_dead_reckoning.py`<br>`IntervalSpeedTrackerTest.kt` | `✅ PASS` |
| **§ 2.3 超速導向警報仲裁** | `ArbitrationInput`<br>`ArbitrationOutput`<br>`AlertPriority` | **TASK-ARB-03** | `tests/test_arbitration.py`<br>`CameraAlertPolicyTest.kt` | `✅ PASS` |
| **§ 2.4 通話焦點與漸近逼近音** | `InCallAlertMode`<br>`ProximityBeepStage` | **TASK-AUDIO-04** | `tests/test_arbitration.py`<br>`FloatingOverlayStateTest.kt` | `✅ PASS` |
| **§ 3.1 雙平台商店上架準備** | 權限政策與隱私規範 | **TASK-COMP-05** | `tests/test_compliance.py`<br>`make.bat check` | `✅ PASS` |
| **§ 3.2 國道警察出沒點預警** | `CameraAlertPolicy`<br>`AlertArbitrator`<br>`OverlayDisplayConfig` | **TASK-HOTSPOT-01** | `tests/test_police_hotspot.py`<br>`CameraAlertPolicyTest.kt`<br>`make.bat check` | `✅ PASS` |
| **§ 2.6 - 2.7 座艙美化、懸浮膠囊升級與效能優化** | `RadarSnapshot`<br>`OverlayDisplayConfig`<br>`VoiceAlertEngine` | **TASK-COCKPIT-02** | `tests/test_cockpit_upgrade.py`<br>`FloatingOverlayStateTest.kt`<br>`make.bat check` | `✅ PASS` |

---

## 變更衝擊控制 (Impact Control)
- 若任何需求發生變更，必須同步更新 `doc/proposal.md`、`doc/data-contracts.md` 與本矩陣。
- 未在本矩陣內列出的類別與方法，實作階段一律不得隨意新增或重構。
