# AGENTS.md — AI 助手协作须知

任何 AI 助手参与本项目，**第一步先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)** 并严格遵守。
高频红线摘录（详细以 CONTRIBUTING.md 为准）。

> 🔁 **交付 / QA 流程统一以 [`CONTRIBUTING.md §0`](CONTRIBUTING.md) 为准**：唯一质量门 =「构建 → 推真机」，
> 三个环节（S1 实现 / S2 构建推真机 / S3 发版）的目标、退出条件与**流程禁令**都写在该节，本文不再复述，
> 以免两处规则打架。

1. **版本号语义化**：新功能 / 新交互 / 新依赖 → `x.y.0`；纯修复 → `x.y.z`；versionCode 只增不减。
   发版前自查「这版有没有新功能」，有过 v3.1.8 / v3.1.11 误用 patch 的教训。
2. **文档同步**：`docs/release-notes/vX.Y.Z.md`（每版一份**中文**发版说明）+ `README.md` 受影响章节，
   与发版是同一个流程；漏一处在发版自查清单里算不合格。
   （`DEVELOPMENT.md` 已被 `.gitignore` 忽略、**不入库**，仅本机留存，不计入公开交付。）
3. **构建 / 签名 / 发版用 `tools/release.ps1`**（用 **PowerShell 工具**执行；**不要在 Bash 里调 powershell**，
   会被安全策略拦截）。该脚本**默认会打 tag 并创建 GitHub Release**（依赖 `gh` 已认证，即先 `gh auth login`）；
   只出包不发版用 `-SkipRelease`。**每次版本号迭代必须打 tag + 发 GitHub Release**（挂正式签名 APK，
   正文取自 `docs/release-notes/vX.Y.Z.md`）。
   签名指纹必须校验 `cca83079…f8f976ce`；已发布 Release 永不改动。
   后台任务完成通知不可靠，产物要用 `ls` 主动确认。

   🔴 **发版完成 = 三件套齐全，缺一不可**：
   ① 签名 APK 落盘（根目录 `TimestampRecorder_vX.Y.Z.apk`）
   ② 远端 tag `vX.Y.Z` 存在（`git ls-remote --tags origin`）
   ③ GitHub Release 已建且**挂有该 APK**（`gh release list` / `gh release view vX.Y.Z`）

   **「改版本号 + commit + push」不算发版**：只要动了 `versionCode / versionName`，就必须在**同一轮**
   跑 `release.ps1` 把三件套补齐，并在结束前**用 3 条命令确认一次发版三件套**（不要只凭脚本输出或后台通知；
   **交付流程 —— 唯一质量门「构建 → 推真机」、各环节退出条件与流程禁令 —— 以 [`CONTRIBUTING.md §0`](CONTRIBUTING.md) 为准**）。
   `assembleDebug` / `lintDebug` 只证明「能编译」，**不等于发版、不产出签名包**。
   `-SkipRelease` 仅限「本机自测出包」；除非主人当轮明确说「不发版」，否则一律默认发版。
   历史教训：v4.1.3 / v4.3.0 / v4.4.0 都出现「版本号升了、代码推了，却无 tag / Release / 签名包」。
4. **ROM 兼容**：优先标准 AndroidX API，ROM 特殊路径只做增强；视觉 / 布局结论必须真机
   像素 / frame 举证；互斥与取舍写进 README「已知限制」。
5. **主人明确「由我测试」时立即停手**，不要再用他的设备切设置做验证。
6. **范围边界**：本仓只处理**安卓版**。鸿蒙版（`D:\AI\TimestampRecorderHarmony`）已收尾且独立成仓，
   **不在本仓处理任何鸿蒙事务**；两仓仅共享数据格式与设计规范，不交叉修改
   （仓库内的鸿蒙相关内容已于 2026-09-16 全部清除）。

每个版本周期至少自查一次以上各项；发现规则本身有遗漏，直接更新 CONTRIBUTING.md。
**交付 / QA 流程以 [`CONTRIBUTING.md §0`](CONTRIBUTING.md) 为准**（唯一质量门「构建 → 推真机」，
各环节目标、退出条件与流程禁令详见该节）。
