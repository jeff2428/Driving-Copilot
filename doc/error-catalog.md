# 系統錯誤目錄與恢復策略 (doc/error-catalog.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》之「鐵律 4：契約優先」原則編寫，定義系統所有標準化錯誤代碼、嚴重度等級、觸發場景與自動降級恢復處置流程。

---

## 1. 錯誤等級劃分 (Severity Levels)

- `CRITICAL`: 影響行車安全判斷或導致程序崩潰之重大錯誤，必須立即降級並紀錄日誌。
- `WARN`: 外部訊號遺失或非預期資料，系統啟動備援模式（如 Dead Reckoning 推估）。
- `INFO`: 一般降級或使用者模式切換資訊（如切換通話視覺模式）。

---

## 2. 錯誤代碼目錄 (Error Catalog Matrix)

| 錯誤代碼 | 嚴重度 | 錯誤名稱 | 觸發條件 | 自動處置與恢復策略 (Fallback) |
| :--- | :---: | :--- | :--- | :--- |
| `ERR_GPS_LOST` | `WARN` | GPS 訊號遺失 | 於區間測速範圍內，超過 3 秒未收到有效坐標更新 | 啟動 `DeadReckoningEngine`，以進入盲區前最後時速進行虛擬里程推算與秒數倒數。 |
| `ERR_DR_TIMEOUT` | `WARN` | 盲區推估超時 | 盲區推估狀態持續超過 15 分鐘 (900 秒) | 自動判定車輛已停車或定位模組故障，安全終止區間測速，懸浮膠囊顯示「GPS 訊號中斷」。 |
| `ERR_SNAP_CORRUPT` | `ERROR` | 快照資料毀損 | 重啟讀取快照時 JSON 解析失敗或必填欄位遺失 | 丟棄快照並清空暫存（`clearSnapshot()`），重置回常態巡航模式，避免重啟循環崩潰。 |
| `ERR_SNAP_EXPIRED` | `INFO` | 快照窗口超時 | 快照時間戳記與目前系統時間差超過最大時間窗口 (例如 > 2 小時) | 判定該趟行程已結束，清空快照，不予還原。 |
| `ERR_CALL_DETECT_FAIL`| `WARN` | 通話狀態判斷失敗 | Android `AudioManager.mode` 拋出例外或無讀取權限 | 自動降級為 `MODE_NORMAL`，維持標準播報音量，確保安全提醒不遺漏。 |
| `ERR_AUDIO_FOCUS_LOST`| `INFO` | 音訊焦點被奪取 | 系統其他 App 請求永久音訊焦點 (如電話響鈴) | 立即暫停 TTS 播報並放棄焦點；若有超速相機則改由懸浮窗視覺閃爍提示。 |
| `ERR_OVERLAY_PERM_DENIED`| `CRITICAL`| 懸浮窗權限未授予 | `Settings.canDrawOverlays()` 回傳 `false` | 停用 `FloatingOverlayView`，降級為純背景語音廣播模式，並發送 Notification 提醒使用者。 |
| `ERR_OPENDATA_PARSE` | `WARN` | 政府圖資解析失敗 | 遠端下載之相機或施工 JSON 缺少必要欄位 | 沿用本機快取或內建 Seed 資料庫 (`taiwan_cameras_latest.csv`)，拒絕無效更新。 |

---

## 3. 恢復策略時序 (Recovery Protocols)

### 3.1 快照毀損恢復流程
```text
[Service onCreate]
       │
       ▼
[檢查是否存在 Snapshot]
       │
       ├──(不存在)──► [進入常態巡航模式 (IDLE)]
       │
       └──(存在)───► [驗證版本與時間窗口]
                           │
                           ├──(資料格式毀損 / ERR_SNAP_CORRUPT)──► [呼叫 clearSnapshot() ➜ 進入 IDLE]
                           ├──(已超時 > 2hr / ERR_SNAP_EXPIRED)──► [呼叫 clearSnapshot() ➜ 進入 IDLE]
                           └──(有效)─────────────────────────────► [還原狀態 ➜ 繼續區間測速]
```

### 3.2 盲區定位恢復流程
```text
[盲區推估中 (Dead Reckoning)]
       │
       ├──(收到新 GPS 點位)
       │         │
       │         ▼
       │   [計算實際坐標與推估里程之殘差]
       │         │
       │         ├──(偏差 < 150m) ──► 平滑吸收殘差，無縫回歸 GPS 追蹤
       │         └──(偏差 >= 150m) ──► 以最新真實 GPS 座標重置累計里程，並記錄日誌
       │
       └──(持續失聯 > 15分鐘 / ERR_DR_TIMEOUT)
                 │
                 ▼
           [安全結束區間推估 ➜ 語音提示「定位訊號遺失」 ➜ 懸浮窗轉為灰色]
```
