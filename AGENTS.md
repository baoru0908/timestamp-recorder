# AGENTS.md — AI 助手协作须知

任何 AI 助手参与本项目，**第一步先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)** 并严格遵守。
高频红线摘录（详细以 CONTRIBUTING.md 为准）：

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
4. **ROM 兼容**：优先标准 AndroidX API，ROM 特殊路径只做增强；视觉 / 布局结论必须真机
   像素 / frame 举证；互斥与取舍写进 README「已知限制」。
5. **主人明确「由我测试」时立即停手**，不要再用他的设备切设置做验证。
6. **范围边界**：本仓只处理**安卓版**。鸿蒙版（`D:\AI\TimestampRecorderHarmony`）已收尾且独立成仓，
   **不在本仓处理任何鸿蒙事务**；两仓仅共享数据格式与设计规范，不交叉修改
   （仓库内的鸿蒙相关内容已于 2026-09-16 全部清除）。

每个版本周期至少自查一次以上各项；发现规则本身有遗漏，直接更新 CONTRIBUTING.md。
