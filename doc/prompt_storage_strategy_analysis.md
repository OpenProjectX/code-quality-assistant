# Prompt 儲存策略分析（branch/prompt_window）

## 1) 儲存位置與格式

目前 Prompt 設定集中儲存在專案根目錄的 `.ai-test.yaml` / `.ai-test.yml`，由 `LlmSettingsLoader` 讀寫。

- 讀取：優先找現有檔案，找不到會建立 `.ai-test.yaml`。
- 格式：YAML，主要節點為 `prompts`。
- 內容分為兩層：
  - 單一模板欄位（例如 `prompts.commitMessage`）
  - 多 Profile 欄位（例如 `prompts.commitMessageProfiles.selected + items`）

## 2) 內建預設值來源

內建 prompt 文案放在 `AiPromptDefaults`（程式常數），包含：

- `COMMIT_MESSAGE`
- `PULL_REQUEST`
- `BRANCH_DIFF_SUMMARY`
- Generation wrapper / framework rules

當 YAML 缺值或空字串時，會 fallback 到這些預設值。

## 3) Profile 結構與解析規則

Prompt profile 使用 `PromptProfileSet`：

- `selected`: 目前選用 profile 名稱
- `items`: `Map<String, String>`（profileName -> template）
- 預設 profile 名稱固定為 `default`

解析規則重點：

1. 僅保留「名稱非空 + 模板非空白」的項目。
2. 若 `items` 為空，會自動補上 `default: <defaultTemplate>`。
3. 若 `items` 非空但沒有 `default`，也會自動補 `default`。
4. 實際使用時會先取 `selected`，不存在或空白就退回 `default`，再不行才退回函式傳入的 fallback template。

## 4) 寫回策略（Settings UI）

Settings 畫面儲存時，`buildPromptsMap` 會把 prompts 整包重建後寫回 YAML：

- `prompts.generation`（wrapper + framework rules）
- `prompts.commitMessage` / `pullRequest` / `branchDiffSummary`
- 三類 profile：`generationProfiles` / `commitMessageProfiles` / `branchDiffSummaryProfiles`

Profile 在 UI 內採「YAML 文字」編輯，儲存時再 parse 成 map。

## 5) 執行時使用策略

- Commit message 與 Branch diff 都支援從 profile menu 選 prompt。
- 使用者點選某個 profile 後，會立即把該名稱寫回 `selected`（持久化到 `.ai-test.yaml`）。
- 之後若未指定 override，系統會用 `PromptProfileResolver` 依 `selected -> default -> fallback` 決策。

## 6) 優點

- 可版本控管：`.ai-test.yaml` 可跟 repo 一起管理。
- 有穩定 fallback：缺值不至於崩潰，能退回內建 prompt。
- 支援多 profile：可快速切換不同提示詞風格。

## 7) 目前風險 / 限制

1. **專案級儲存，不是使用者級儲存**：不同開發者可能互相覆蓋 `selected`。
2. **整包重寫 prompts 區塊**：手動註解或格式可能在儲存後丟失（YAML dump 行為）。
3. **`selected` 不保證一定存在於 `items`**：雖有 runtime fallback，但設定值可能漂移。
4. **UI 以原始 YAML 編輯 profiles**：可用性高彈性，但格式錯誤風險高。
5. **Pull Request prompt 目前只有單模板欄位，沒有 profile set**：與 commit/branch diff 能力不一致。

## 8) 若要做 prompt window 的建議方向

- 在視窗層加「preview 最終 prompt」：顯示 selected profile 套變數後的內容。
- 優化 profile 管理：改為結構化列表編輯（新增/複製/刪除），降低 YAML 手誤。
- 增加驗證：儲存時檢查 `selected` 必須存在於 `items`，否則自動回落 `default`。
- 規劃儲存分層：
  - repo 層（共享）
  - user 層（本機偏好，不進版控）
