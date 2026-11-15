# 上架合規與法規文檔任務拆解表 (doc/task/compliance.md)

本文件定義雙平台商店（Google Play 與 Apple App Store）上架所需之法規、隱私權政策與權限顯著揭露任務。

---

## 任務清單表格 (Task Breakdown Matrix)

| Task ID | Depends On | Allowed Files | Test Command | 預估時間 |
| :--- | :--- | :--- | :--- | :---: |
| **TASK-COMP-05** | None | `doc/privacy-policy.html`<br>`doc/store-listing.md`<br>`app/src/main/AndroidManifest.xml` | `make.bat check` | 25 分鐘 |

---

## 任務詳細定義 (Task Specifications)

### 📌 TASK-COMP-05: 建立雙平台商店上架法規檔案與權限揭露
- **目標**: 符合 Google Play 與 Apple App Store 關於「背景定位權限 (Background Location)」、「前景服務 (Foreground Service)」與「懸浮視窗 (Overlay)」之審查規範。
- **具體實作動作**:
  1. 建立 `doc/privacy-policy.html`：包含標準隱私權條款、定位資訊收集目的宣告（僅本機運算、不販售個人數據、關閉服務即停止定位）。
  2. 建立 `doc/store-listing.md`：編寫繁體中文與英文之應用程式名稱、短描述、完整功能介紹、關鍵字與權限聲明文案。
  3. 檢驗 `app/src/main/AndroidManifest.xml`：
     - 確認宣告 `android.permission.ACCESS_BACKGROUND_LOCATION`。
     - 確認 Service 標記 `android:foregroundServiceType="location"`。
- **DoD (驗收條件)**:
  - `doc/privacy-policy.html` 具備完整 HTML5 結構與清楚條款段落。
  - `doc/store-listing.md` 包含符合 Google Play 字數規範之短描述 (< 80 字) 與長描述 (< 4000 字)。
  - `AndroidManifest.xml` 符合 Google Play 審查標準。
  - 執行 `make.bat check` PASS。
