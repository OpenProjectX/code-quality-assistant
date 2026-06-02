# Code Quality Assistant (CQA) — 功能文档

## 快速开始

### 第一步：导入远程配置

1. **File → Settings → Tools → Code Quality Improver → Login 标签页**
2. 在 **Bitbucket Prompt Repo** 区域填写：
   - **Repository URL**：如 `https://bitbucket.org/your-org/prompt-repo`
   - **Branch**：默认 `main`
   - **Token**：Bitbucket App Password 或 Personal Access Token
3. 点击 **Import Repo Config** 按钮
4. 等待导入完成，提示 "Config imported successfully"

这会将远程仓库中的 Prompt、Skill 模板和 UI 配置同步到本地 `~/.codeimprover/` 目录。

### 第二步：配置 LLM 登录

1. **File → Settings → Tools → Code Quality Improver → LLM 标签页**
2. **Provider**：选择 `openai-compatible`（兼容 OpenAI API 的端点，如 DeepSeek、OpenRouter 等）
3. **Model**：填入模型名，如 `deepseek-chat`
4. **Endpoint**：填入 API 端点，如 `https://api.deepseek.com/v1/chat/completions`
5. **API Key**：填入你的 API Key（或设置 **API Key Env** 环境变量名，如 `DEEPSEEK_API_KEY`）

#### 备选：使用 Template Login 自动获取 Key

如果 API Key 需要每次登录获取：
1. 在 **LLM → Login Template** 区域配置：
   - **Method**：`POST`
   - **URL**：登录端点
   - **Headers**：请求头（JSON 格式）
   - **Body**：请求体模板，使用 `{{username}}` `{{password}}` `{{model}}` 变量
   - **Response Path**：JSONPath 表达式提取响应中的 API Key，如 `$.access_token`
2. 首次调用 LLM 时会弹出登录对话框，输入用户名和密码
3. 勾选 **Remember** 保存凭证到 IntelliJ PasswordSafe

---

## 功能详解

### 1. Test Generation（测试生成）

**入口**：
- 在编辑器中打开 `.yaml` / `.yml`（OpenAPI 合约）或 `.java`（Java 源文件）
- 编辑器顶部出现通知栏：`[OpenAPI contract / Java source] detected`
- 点击 **"Generate Tests By AI"** 打开配置对话框

**对话框参数**：

| 参数 | 说明 |
|------|------|
| **Framework** | `JUnit 5 + Rest Assured`（Java API 测试）或 `Karate`（Feature 文件） |
| **Prompt Profile** | 从 Prompt Manager 中选择预设的生成模板，影响生成风格和质量 |
| **Output Location** | 测试文件输出目录，默认自动推导（`src/main/java` → `src/test/java`） |
| **Class Name** | 生成的测试类名，默认 `<源类名>Test` |
| **Package Name** | Java package 名，Rest Assured 模式下自动从源文件路径推导 |
| **Base URL** | API 基础地址，用于 Rest Assured 的 `baseURI` |
| **Extra Notes** | 额外的生成指令，如 "包含边界测试"、"mock PaymentValidator" |

**生成内容**：
- 自动收集被测试方法调用的外部依赖方法签名，发送给 LLM 以便正确 mock
- 生成完整的 JUnit 5 测试类，包含 `@BeforeAll` 公共设置和每个操作的测试方法
- 结果写入项目文件系统，Context Box 中显示生成代码并支持跟进修改

---

### 2. Commit Message Generation（提交信息生成）

**入口**：
- VCS Commit 窗口 → 工具栏 **"Generate Commit Message"** 下拉菜单
- 选择一个 Prompt 配置文件

**所需上下文**：
- 自动收集当前暂存 + 未暂存的 git diff
- 如果分支名包含 JIRA Key 格式（如 `ABC-123`），自动作为前缀

**参数**（通过 Prompt Manager 配置）：
- Prompt 模板决定生成风格（Conventional Commits、详细描述等）

**输出**：
- 生成的 commit message 直接填充到 VCS Commit 输入框中

---

### 3. Branch Diff Analysis（分支差异分析）

**入口**：
- 打开 **Git Log**（VCS → Log）
- 右键点击要比较的目标分支或 commit
- 菜单顶部选择 **"Analyze Branch Diff"** → 选择一个 Prompt 配置文件

**自动解析**：
- `sourceBranch`：当前所在分支
- `targetBranch`：右键选中的分支（从 VCS Log 数据上下文中通过 `VCS_LOG_BRANCHES` 获取）
- 使用 `git diff target...source` 收集差异

**参数**（通过 Prompt Manager 配置）：
- Prompt 模板决定分析重点（安全审查、性能影响、架构风险等）

**输出**：
- Context Box 自动弹出，显示 USER 泡泡 `"Analyze changes on feature/A → main"`
- AI 回复以 "Branch Analysis" 标签显示分析摘要
- 底部 **"Create PR →"** 按钮，可直接基于分析结果创建 PR

---

### 4. Push & Create PR（推送并创建 PR）

**入口**：
- VCS Commit 窗口 → 工具栏 **"Push and Create PR"**

**对话框参数**：

| 参数 | 说明 |
|------|------|
| **Create Pull Request after push** | 勾选后推送并自动创建 PR |
| **Target Branch** | PR 要合入的分支，默认 `main` |

**自动流程**：
1. `git push origin <current-branch>`
2. 收集分支差异
3. AI 生成 PR 标题 + 描述
4. 调用 Bitbucket 或 GitHub REST API 创建 PR
5. Context Box 记录分析摘要
6. 通知显示 PR URL

**前置条件**：
- Git 远程仓库已配置
- 远程 URL 需为 Bitbucket 或 GitHub（自动识别）

---

### 5. Code Generate & Review（代码生成与审查）

**入口**：
- 在编辑器中选中代码 → 右键 → **"Code Generate & Review"**

**对话框内容**：
- 列出 Prompt Manager 中所有 Code Generate 和 Code Review 分类下的 prompt
- 选择一个 prompt，可选填额外需求文本

**参数**（通过 Prompt Manager 配置）：
- Prompt 模板定义生成/审查的目标和风格
- 额外需求：用户自由填写的补充指令

**输出**：
- Context Box 中以对应分类标签显示 LLM 的回应

---

### 6. SonarQube Coverage（SonarQube 覆盖率）

**入口**：
- **Tools 菜单 → SonarQube Coverage**

**对话框参数**：

| 参数 | 说明 |
|------|------|
| **Server URL** | SonarQube 服务器地址 |
| **Project Key** | SonarQube 项目标识 |
| **Token / Username / Password** | SonarQube 认证信息 |
| **Target Coverage %** | 目标覆盖率（用于筛选未达标的文件） |
| **Max Files** | 最多处理的文件数 |
| **Generate missing tests with AI** | 是否自动为未覆盖文件生成测试 |

**功能**：
1. 从 SonarQube API 获取项目覆盖率数据
2. 展示文件级覆盖率表格
3. 本地扫描模式（不依赖 SonarQube 服务器）：检测 TODO/FIXME、printStackTrace、空 catch、硬编码密钥
4. 右键 Issue：**Go to Line**、**AI Fix**、**Mark as fixed**

**输出**：
- Context Box 中以 "SonarQube Coverage" 标签显示报告
- Sonar Cube 标签页中可视化展示指标卡片和 Issue 列表

---

### 7. Context Box Chat（上下文盒子聊天）

**入口**：
- 右侧边栏 → **AI Context Box** 工具窗口 → **Context 标签页**

**功能**：
- 像正常 LLM 聊天一样输入消息
- 输入框文本 + Enter 发送
- 对话历史以气泡形式展示（绿色 = 用户，深色 = AI）
- 历史自动保存 10 条上下文，支持多轮对话
- 测试生成后可在聊天中追加 "增加边界测试" 等指令来重新生成
- 重新生成的代码带 **"Generate Tests →"** 按钮，点击直接覆写文件

**参数**：无（纯文本输入）

---

### 8. Prompt Manager（提示词管理器）

**入口**：
- AI Context Box 侧边栏 → **Prompt Manager 标签页**

**功能**：
- 管理 5 个分类下的 Prompt 模板：Test Generate、Commit Generate、Branch Compare、Code Generate、Code Review
- 创建 / 编辑 / 复制 / 删除 Prompt
- 从远程 Bitbucket/GitHub 仓库同步 Prompt
- 搜索、排序、按分类筛选
- 全局 Prompt（蓝色地球图标）vs 本地 Prompt（灰色文件夹图标）

**Prompt 模板语法**：
- 使用 `{{variable}}` 占位符注入上下文变量

---

### 9. Skill Manager（技能管理器）

**入口**：
- AI Context Box 侧边栏 → **Skill Manager 标签页**

**功能**：
- 管理 Skill 定义（YAML + Markdown 模板）
- 全局 Skill 和本地 `.md` 文件 Skill
- 创建 / 编辑 / 复制 / 删除 Skill
- 从远程仓库同步 Skill

---

### 10. Settings（设置）

**入口**：
- **File → Settings → Tools → Code Quality Improver**

**标签页**：

| 标签 | 配置项 |
|------|--------|
| **Login** | Bitbucket 仓库 URL、Token、登录模板配置 |
| **LLM** | Provider、Model、Endpoint、API Key、Timeout、Max Tokens、TLS 设置 |
| **Sonar Cube** | 服务器 URL、项目 Key、Token、覆盖率目标、本地/在线扫描模式 |
| **Prompts** | 各类 Prompt 默认模板、Prompt Profiles YAML 配置 |

---

## 配置目录

所有配置存储在 `~/.codeimprover/` 下：

```
~/.codeimprover/
├── .ai-test.yaml          # 主配置文件
├── usage.yaml             # 使用统计
├── prompts/               # 本地 Prompt 模板
├── skills/                # 本地 Skill 模板
└── projects/              # 项目级 SonarQube 配置
```

## 在线文档

最新文档和 Prompt/Skill 模板托管在 Bitbucket 仓库中，通过 Settings → Login → Import Repo Config 同步。
