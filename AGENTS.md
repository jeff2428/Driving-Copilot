# AGENTS.md - AI Vibe Coding Standard Protocol

## 1. 專案基本資訊 (Project Context)
- **專案名稱**: 神盾路況行車助手 (TW-RoadGuard-Aegis / Driving-Copilot)
- **核心目標**: 台灣全區即時路況、測速照相、區間測速、科技執法與雪隧即時路況警示系統（Android 懸浮窗/後台服務 + iOS SwiftUI/即時動態 + 雲端自動圖資管線）
- **技術棧**: Android (Kotlin, Gradle, JUnit), iOS (Swift, SwiftUI, ActivityKit), Python (自動圖資抓取、版本發布)
- **當前階段**: `Phase 0: 需求澄清` (預設)

---

## 2. AI 核心鐵律 (Core Guardrails)
⛔ **你（AI 代理）必須嚴格遵守以下原則，違反任何一項即視為任務失敗：**
1. **絕不猜測需求**：遇到模糊地帶必須提問。可給出選項與建議，但必須標記為 `[OPEN]` 或 `[ASSUMPTION]`，未經我確認不得視為需求。
2. **最小改動原則**：實作階段只修改 `Allowed Files` 中列出的檔案，絕不順手重構或修改無關程式碼。
3. **證據驅動完成**：任務狀態絕不能直接標記為 `✅ 已完成`，必須提供 `make check` 或 `pytest` 的成功終端輸出作為證據。
4. **契約優先**：所有模組、函數、API 必須先有明確定義的輸入/輸出/錯誤碼契約，才開始寫實作。
5. **狀態同步**：任何階段產出物完成後，必須主動要求我更新 `doc/progress.md` 或本文件的「當前狀態」區塊。

---

## 3. 標準工作流與觸發指令 (Workflow & Prompts)

### 📍 Phase 0: 需求澄清 (Requirement Clarification)
**目標**：將模糊草稿轉化為具備邊界條件、異常處理與驗收標準的 `proposal.md`。
**進入條件**：專案剛啟動。
**產出物**：`doc/proposal.md`, `doc/open-questions.md`
**觸發 Prompt (當你需要啟動此階段時，對我輸出以下指令)**：
> "請啟動【Phase 0 需求澄清】。我稍後會提供原始草稿，請你扮演嚴苛的 Technical PM，使用蘇格拉底式提問，向我提出 5~8 個具體、包含邊界與異常處理的選擇/場景題。請記住【絕不猜測】的鐵律。"

### 📍 Phase 1: 專案初始化 (Project Initialization)
**目標**：建立品質閘門與基礎骨架。
**進入條件**：Phase 0 完成，`proposal.md` 已確認。
**產出物**：`pyproject.toml`, `Makefile`, `.pre-commit-config.yaml`, `tests/` 目錄。
**觸發 Prompt**：
> "請啟動【Phase 1 專案初始化】。請根據 `doc/proposal.md` 的技術需求，幫我生成 `pyproject.toml` (含 ruff/mypy/pytest 嚴格配置) 與標準化的 `Makefile`，並指導我建立 `src/` 與 `tests/` 結構。"

### 📍 Phase 2: 架構與契約設計 (Architecture & Contracts)
**目標**：定義模組劃分、資料契約與錯誤目錄。
**進入條件**：Phase 1 完成，基礎環境可跑 `make check`。
**產出物**：`doc/high-level-design.md`, `doc/data-contracts.md`, `doc/error-catalog.md`
**觸發 Prompt**：
> "請啟動【Phase 2 架構設計】。讀取 `doc/proposal.md`，輸出模組劃分、資料契約 (Input/Output 格式限制) 與錯誤目錄 (Error Codes & 恢復策略)。若發現需求缺口，請先輸出 `BLOCKERS.md` 暫停設計。"

### 📍 Phase 3: 任務拆解 (Task Breakdown)
**目標**：將設計拆解為 15~60 分鐘可驗證的最小任務。
**進入條件**：Phase 2 完成，契約已定義。
**產出物**：`doc/task/<module>.md`, `doc/progress.md`, `doc/traceability.md`
**觸發 Prompt**：
> "請啟動【Phase 03 任務拆解】。根據設計文檔，將模組拆解為任務表格。每個任務必須包含：Task ID、Depends On、Allowed Files、Test Command、DoD。禁止出現模糊動詞。"

### 📍 Phase 4: 實作迴圈 (Implementation Loop)
**目標**：逐一執行任務，寫測試 -> 實作 -> 驗證。
**進入條件**：Phase 3 完成，有明確的任務清單。
**產出物**：實際的 Source Code 與 Tests。
**觸發 Prompt (每次執行單一任務時使用)**：
> "請啟動【Phase 4 實作迴圈】。我要執行任務 `<TASK_ID>`。請先重述該任務的契約與 DoD，然後引導我：1. 寫失敗測試 2. 實作程式碼 3. 執行 `make check`。請嚴格監控我是否只修改了 Allowed Files。"

### 📍 Phase 5: 審查與驗收 (Review & Acceptance)
**目標**：獨立審查程式碼，確保符合契約與需求。
**進入條件**：Phase 4 任務完成。
**產出物**：Review Report (PASS/FAIL), 驗收測試。
**觸發 Prompt**：
> "請啟動【Phase 5 獨立審查】。你現在是嚴格的 QA/Reviewer。請審查 `<TASK_ID>` 的變更，檢查是否滿足 DoD、是否覆蓋邊界條件、是否過度 mock。若缺少證據，請直接給出 FAIL 與修改清單。"

---

## 4. 專案狀態追蹤區 (State Tracking)
*(AI 每次回覆前，應先讀取此處以確認當前上下文；階段轉換時，AI 應主動要求我更新此處)*

- **Current Phase**: `Phase 5: 審查與驗收 (APPROVED) -> 準備發布上傳更新`
- **Active Task**: `TASK-COCKPIT-02 完成 (科技座艙玻璃擬態美化、懸浮膠囊進度條/隧道推估/一鍵靜音與效能優化，全數 7 項任務皆已 PASS)`
- **Last Verified Command**: `make.bat check (PASS: 31 Python + 36 Android Unit Tests, Parity, Swift Validation, Exit Code 0)`
- **Blockers**: `無`

---

## 5. 防呆糾正指令 (Correction Triggers)
若我（或 AI 自身）在對話中偏離了上述規範，請立即使用以下語句糾正：
- ⚠️ `違反鐵律 1：你正在猜測需求。請停止，並將該項標記為 [OPEN] 並提供選項。`
- ⚠️ `違反鐵律 2：你修改了 Allowed Files 以外的檔案。請回退非必要變更。`
- ⚠️ `違反鐵律 3：此任務缺少測試證據 (make check / pytest 輸出)。請補齊證據再聲稱完成。`
- ⚠️ `任務粒度過大：請將此任務拆分為可在 1 小時內驗證的子任務。`
