# 資料契約與介面規格書 (doc/data-contracts.md)

本文件依據《AI Vibe Coding Standard Protocol (AGENTS.md)》之「鐵律 4：契約優先」原則編寫。所有模組、類別、介面與持久化序列化格式均已在此嚴格定義，任何實作不得超出此契約約束。

---

## 1. 區間測速持久化快照契約 (IntervalSessionSnapshot)

### 1.1 JSON 序列化 Schema (版本: v1)
用於將區間測速狀態寫入本機 `SharedPreferences`（Key: `interval_session_snapshot_v1`）。

```json
{
  "version": 1,
  "cameraId": "CAM_ZONE_001",
  "speedLimit": 90,
  "zoneLengthMeters": 3000.0,
  "entryTimeMs": 1729900000000,
  "lastUpdateTimeMs": 1729900050000,
  "totalDistanceMeters": 1250.0,
  "lastSpeedKmh": 90,
  "isDeadReckoning": false,
  "state": "IN_ZONE"
}
```

### 1.2 欄位驗證約束表 (Field Constraints)

| 欄位名稱 | 型別 | 必填 | 數值範圍 / 格式約束 | 違規行為處理 |
| :--- | :--- | :--- | :--- | :--- |
| `version` | `Int` | 是 | 必須等於 `1` | 若版本不相容，放棄復原並丟棄快照 |
| `cameraId` | `String` | 是 | 長度 1~64 字元，不可為空 | 判定無效快照 |
| `speedLimit` | `Int` | 是 | $20 \le \text{speedLimit} \le 150$ | 預設以 60 作為安全防護值 |
| `zoneLengthMeters` | `Double` | 是 | $100.0 \le \text{length} \le 50000.0$ | 若小於 100 判定異常快照 |
| `entryTimeMs` | `Long` | 是 | $> 0$ 且 $\le \text{CurrentTime}$ | 若大於當前時間判定無效 |
| `lastUpdateTimeMs` | `Long` | 是 | $\ge \text{entryTimeMs}$ | 若小於起點時間判定無效 |
| `totalDistanceMeters` | `Double`| 是 | $\ge 0.0$ 且 $\le \text{zoneLengthMeters} \times 1.5$ | 超過 1.5 倍判定已通過終點，強制結算 |
| `lastSpeedKmh` | `Int` | 是 | $0 \le \text{speed} \le 250$ | 若負數校正為 0 |
| `isDeadReckoning` | `Boolean` | 是 | `true` 或 `false` | 預設 `false` |
| `state` | `String` | 是 | 枚舉: `APPROACHING_START`, `IN_ZONE`, `APPROACHING_END`, `COMPLETED` | 不在枚舉內則退回 `IDLE` |

---

## 2. 警報仲裁契約 (AlertArbitrator Contract)

### 2.1 輸入參數契約 (ArbitrationInput)

```kotlin
data class ArbitrationInput(
    val currentSpeedKmh: Int,
    val speedLimitKmh: Int,
    val distanceToCameraMeters: Double?,
    val nearestCamera: SpeedCamera?,
    val activeRoadEvent: RoadEvent?,
    val isInCall: Boolean,
    val inCallAlertMode: InCallAlertMode
)
```

- **輸入邊界約束**:
  - `currentSpeedKmh`: $\ge 0$
  - `speedLimitKmh`: 若無速限預設為 0
  - `distanceToCameraMeters`: 若無相機為 `null`，若有則 $\ge 0.0$
  - `isInCall`: 布林值
  - `inCallAlertMode`: 枚舉（`MUTE_VISUAL`, `DUCKING`, `NORMAL`）

### 2.2 輸出結果契約 (ArbitrationOutput)

```kotlin
enum class AlertPriority(val level: Int) {
    EMERGENCY_OVERSPEED(1), // 最高優先級：超速警告
    INTERVAL_END(2),        // 次高：區間終點結算
    TECH_CAMERA(3),         // 一般相機/科技執法
    ROAD_CLOSURE(4),        // 道路封閉
    ROAD_WORK(5),           // 一般施工路況
    IDLE(99)
}

enum class InCallAlertMode {
    MUTE_VISUAL,            // 通話中靜音，懸浮窗視覺閃爍 (預設)
    DUCKING,                // 降低通話音量播報
    NORMAL                  // 正常播報
}

data class ArbitrationOutput(
    val priority: AlertPriority,
    val isVoiceAllowed: Boolean,
    val isUrgentTone: Boolean,
    val isFlushQueue: Boolean,
    val isVisualFlashRequired: Boolean,
    val speechText: String?,
    val displayMessage: String
)
```

---

## 3. 逼近音調度契約 (ProximityBeepContract)

### 3.1 逼近距離階梯映射 (Stage Mapping)

```kotlin
enum class ProximityBeepStage(
    val maxDistanceMeters: Double,
    val minDistanceMeters: Double,
    val intervalMs: Long,
    val isDoubleTone: Boolean
) {
    STAGE_FAR(150.0, 100.0, 1000L, false),      // 150m ~ 100m: 1.0秒單音
    STAGE_MID(100.0, 50.0, 500L, false),        // 100m ~ 50m: 0.5秒單音
    STAGE_NEAR(50.0, 0.0, 250L, false),         // 50m ~ 通過點: 0.25秒急促單音
    STAGE_NEAR_OVERSPEED(50.0, 0.0, 200L, true),// 50m ~ 通過點且超速: 0.20秒急促雙音
    NONE(Double.MAX_VALUE, 150.0, 0L, false)
}
```

- **輸入合約**: 傳入 `distanceToCameraMeters: Double` 與 `isOverspeed: Boolean`。
- **輸出合約**: 傳回對應之 `ProximityBeepStage`。若距離大於 150m，傳回 `NONE` 不發聲。

---

## 4. 隧道慣性推估契約 (DeadReckoningContract)

```kotlin
interface IDeadReckoningEngine {
    /** 當定位遺失超過 3 秒時觸發 */
    fun onGpsLost(lastKnownSpeedKmh: Int, nowMs: Long)
    
    /** 每秒時鐘推進 (Heartbeat Tick) */
    fun tick(deltaSeconds: Double, nowMs: Long): DeadReckoningTickResult
    
    /** 當 GPS 重新獲得有效信號時觸發校驗與平滑過渡 */
    fun onGpsRecovered(actualLat: Double, actualLng: Double, actualSpeedKmh: Int, nowMs: Long)
    
    /** 判定是否已超過最大容許盲區時間 (15 分鐘 = 900 秒) */
    fun isExpired(nowMs: Long): Boolean
}

data class DeadReckoningTickResult(
    val simulatedSpeedKmh: Int,
    val addedDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val remainingSafeSeconds: Int,
    val estimatedAverageSpeedKmh: Int
)
```

---

## 5. 座艙與懸浮膠囊顯示擴充契約 (Cockpit & Overlay Display Contract)

### 5.1 `RadarSnapshot` 擴充欄位
```kotlin
data class RadarSnapshot(
    // ...既有欄位維持向下相容
    val isDeadReckoning: Boolean = false,
    val intervalProgressFraction: Float = 0f,
    val intervalSpeedLimit: Int = 0
)
```

### 5.2 `OverlayDisplayConfig` 擴充欄位與計算契約
```kotlin
internal data class OverlayDisplayConfig(
    // ...既有欄位維持向下相容
    val progressFraction: Float = 0f,
    val isDeadReckoning: Boolean = false
)
```
- **值域約束**:
  - `progressFraction`: 常態巡航 (`COMPACT_SPEED`) 為 `0f`；處於警示模式 (`ALERT`) 時：
    - 相機逼近：`1.0 - (distanceMeters / farMeters)`，限幅於 `[0.05f, 1.0f]`。
    - 區間測速：`distanceTraveledMeters / zoneLengthMeters`，限幅於 `[0.05f, 1.0f]`。
    - 雪隧進洞引導：`(2500.0 - distanceToEntranceMeters) / 2180.0`，限幅於 `[0.05f, 1.0f]`。
    - 道路事件：`(1500.0 - distanceMeters) / 1500.0`，限幅於 `[0.05f, 1.0f]`。
  - `isDeadReckoning`: 與 `IntervalSpeedTracker.ZoneStatus.isDeadReckoning` 或 `RadarSnapshot.isDeadReckoning` 同步。
