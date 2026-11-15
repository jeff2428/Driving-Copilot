# 待決策問題與決議清單 (doc/open-questions.md)

本文件紀錄《Phase 0 需求澄清》期間所有的開放性問題、使用者正式決策與系統假設。**所有項目已 100% 達成共識並關閉**：

---

### ✅ 已確認決策清單 (Resolved Decisions)

#### 1. 首選單一驗證平台
- **決議**: **【Android 先行驗證】**
- **理由**: Android 具備完整的後台常駐服務 (`RoadGuardService`)、懸浮視窗膠囊 (`FloatingOverlayView`) 以及既有的 JUnit 單元測試，且可直接產出 APK 在實體車輛上進行實際道路驗證。驗證無誤後再行鏡像至 iOS。

#### 2. 通話中語音焦點系統預設行為
- **決議**: **【預設模式 B：靜音僅懸浮窗視覺閃爍 (Visual Alert Only)】**
- **規格**: 
  - 駕駛通話中不打斷通話，避免突發高音量與隱私尷尬。
  - 懸浮窗（Floating Capsule）啟動紅黃雙色動態呼吸閃爍警示。
  - 使用者仍可在「設定」中自行切換為「降低通話音量播報 (Ducking)」或「正常播報」。

#### 3. 逼近漸近警示音 (Proximity Beep)
- **決議**: **【納入本次迭代範圍】**
- **規格**: 
  - 參考《神盾測速照相》，在車輛距測速點 150 公尺內，依剩餘距離遞減（150m、100m、50m、通過點）動態加快播放 "嗶-嗶-嗶" 逼近提示音。
  - 若當前車速超速，逼近音切換為雙音急促警報聲。

#### 4. 雙平台商店上架法規檔案
- **決議**: **【完整建立法規檔案】**
- **規格**:
  - 產出 `doc/privacy-policy.html`（符合 Google Play / App Store 隱私權規範）。
  - 產出 `doc/store-listing.md`（應用程式商店文案、關鍵字、功能亮點與權限揭露宣告）。
  - 在 Android 端建立「背景定位顯著揭露宣告 (Prominent Disclosure Modal)」UI 與提示文案。

---

### 📌 系統已確立之技術假設 (System Assumptions)

1. `[ASSUMPTION-01]` **狀態持久化技術棧**:
   - Android 平台採用高速可靠的 `SharedPreferences` (Commit mode) 記錄 `interval_session_snapshot`，在 `RoadGuardService.onCreate()` 優先復原。
2. `[ASSUMPTION-02]` **隧道推估基準時間視窗**:
   - 無 GPS 訊號持續超過 3 秒啟動盲區推估；失聯持續超過 15 分鐘（900 秒）視為停車或長時間斷訊，安全終止推估。
3. `[ASSUMPTION-03]` **超速優先權重仲裁**:
   - 當即時車速 > 速限時，超速警告擁有最高權重（Priority 1），強制 Flush TTS 佇列並壓制次要事件（如道路施工）。
