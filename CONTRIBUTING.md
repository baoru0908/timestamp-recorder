# 开发与发布规范

> 任何人（包括 AI 助手）参与本项目都必须遵守本规范；**每次发版前**过一遍文末的自查清单，
> 每个版本周期中途**至少自查一次**版本号与文档同步情况。

## 1. 版本号（语义化，versionCode 只增不减）

| 变更内容 | 版本动作 | 例 |
|:---|:---|:---|
| 新功能、新交互、新依赖、UI 结构重构 | 中版本 **x.y.0** | 3.1 → 3.2.0 |
| 纯 bug 修复、ROM 适配加固、文案调整 | 小版本 **x.y.z** | 3.2.0 → 3.2.1 |
| UI / 架构级别的整体重构 | 大版本 **x.0.0** | 3.x → 4.0.0 |

- 每个版本发一个 GitHub Release，说明用**中文**（`docs/release-notes/vX.Y.Z.md`，`release.ps1` / `gh_release.py` 优先读取）。
- **打 tag + 建 GitHub Release 是发版的必做步骤**，由 `tools/release.ps1` **默认完成**（依赖 `gh` 已认证）；
  只出包不发版时才加 `-SkipRelease`。**改版本号 → 必须同一轮跑 `release.ps1` 把「签名包 + tag + Release」补齐；
  只 commit + push 不算发版**（`-SkipRelease` 仅限本机自测出包，且需主人当轮明确同意不发版）。
  历史教训：v4.1.1 / v4.1.2 曾因脚本开关默认跳过发布；v4.1.3 / v4.3.0 / v4.4.0 则
  「版本号升了、代码推了，却始终没跑 `release.ps1`」——二者同样算**未发版**。
- **已发布的 Release / tag 永不改动**；需要重出必须升版本号，走全新 Release。
- ⚠️ **发版前自查**：问一句「这版有没有新功能 / 新交互 / 新依赖？」——有就必须 x.y.0。
  历史教训：v3.1.8（Tab 图标）、v3.1.11（ViewPager2 + 新依赖）都是功能版却按 patch 发了，引以为戒。

## 2. 文档三同步（发版的同一个流程，不是可选项）

1. **`docs/release-notes/vX.Y.Z.md`** — 中文发版说明；含已知取舍 / 已知限制。
2. **`DEVELOPMENT.md`** — 版本史追加：这版做了什么、根因是什么、踩了什么坑（给接手的人）。
3. **`README.md`** — 用户可见处同步：核心能力表、教程步骤、兼容性表、已知限制。

自查口令：`git log` 里发版 commit 之后，这三个文件是否都有对应变更？缺哪个补哪个。

## 3. ROM 兼容原则（通杀优先，不放弃任何一台已支持机型）

- 优先标准 AndroidX API；ROM 特殊路径（如 MIUI 权限页）只做**增强**，必须有标准兜底。
- 对「视觉 / 布局」下的结论必须**真机举证**（截图像素分析、`dumpsys window` 的 frame），
  不接受目测；跨机型结论至少两台设备验证。
- ROM 间的互斥 / 取舍（如状态栏配色 vs 实时模糊）写进 README「已知限制」与 DEVELOPMENT.md，
  并在 App 内做对应引导（如小米权限按钮）。

## 4. 构建与发布

### 一键发版（推荐）

改完版本号后跑**一条命令**，构建 → 对齐签名 → 自动校验指纹 → 打 tag → 建 GitHub Release 全部完成：

```powershell
# 需 PowerShell 前台执行；先允许本会话运行脚本
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process -Force
.\tools\release.ps1                # 默认即发版：tag + GitHub Release（挂签名 APK）
.\tools\release.ps1 -SkipRelease   # 只出签名包，不发版（本机自测用）
```

- **发布正文取 `docs/release-notes/vX.Y.Z.md`**；文件缺失会回退到简短正文，并在输出里**明确告警**。
- **发版依赖 `gh` 已认证**：先 `gh auth status` 确认（未登录跑 `gh auth login`）；
  `gh` 不可用时脚本自动回退到 `$env:GITHUB_TOKEN` + REST API。
- **幂等 + 友好失败**：tag / Release 已存在时提示后跳过（不致命）；`gh` 未认证时给出修复指引文字。
- 指纹红线：`cca83079a87053a579262dfd8db5191af36349aacd2db26b5c5c67daf8f976ce`
  （`CN=TimestampRecorder`），发布的 APK 必须是 release.keystore 签名（脚本签完自校验，不符即 **拒绝发布**）。
- keystore 口令只从 `$env:TSR_KEYSTORE_PASS` 读取（或运行时输入），**严禁写进任何入库文件**。

### 手动兜底（脚本不可用时）

```bash
export JAVA_HOME="C:/Users/Baoru Lee/AppData/Local/Programs/Microsoft/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
# 1) 编译自检（跑完立刻 ls 产物，不等通知）
./gradlew.bat assembleRelease --console=plain -q
# 2) 对齐 + 签名后核对指纹
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --print-certs <apk> | grep SHA-256
# 3) 装机确认版本 → 4) push + tag → 5) 建 Release（挂签名 APK，正文用中文 release-notes）
git tag vX.Y.Z && git push origin vX.Y.Z
gh release create vX.Y.Z --notes-file docs/release-notes/vX.Y.Z.md <apk>
```

- 沙箱代理端口会变：网络不通先探测（7897/5429/…），多为 Clash 未开。
  本机 git 全局配了 `http.proxy=127.0.0.1:7897`，Clash 未开时网络不通；手动 git 命令可加
  `-c http.proxy= -c https.proxy=` 直连，**别改 git 全局代理配置**（`release.ps1` 的 git 操作已内置「失败即禁用代理重试」）。
- ⚠️ **勿用 PowerShell 后台任务跑构建**，产物要用 `ls` 主动确认，别等完成通知。

## 5. 发版自查清单

> 🔴 **先过「发版三件套」硬关卡** —— 这是「发版是否完成」的唯一判据，缺一即视为**未发版**：
>
> 1. **签名包**：根目录有 `TimestampRecorder_vX.Y.Z.apk`（release.keystore 签名，指纹 `cca83079…f8f976ce`）
> 2. **tag**：`git ls-remote --tags origin` 能看到 `vX.Y.Z`
> 3. **Release**：`gh release list` 有该版本，且 `gh release view vX.Y.Z` 的 assets 里**挂着上面的 APK**
>
> **只 commit + push（哪怕版本号已升）不算发版**；只要动了版本号，就必须在**同一轮**跑
> `tools/release.ps1` 把三件套补齐。历史教训：v4.1.3 / v4.3.0 / v4.4.0 版本号升了、代码推了，
> 却漏了 tag / Release / 签名包。

- [ ] **发版三件套齐全**（上面 1 / 2 / 3 逐条**实测**确认，不看脚本输出、不等后台通知）
- [ ] 版本号语义正确、versionCode 恰好 +1
- [ ] release-notes / DEVELOPMENT.md / README 三处已更新并推送
- [ ] 真机回归：开 / 关高级材质两种形态布局一致，App 可正常启动
- [ ] 双机型（小米 HyperOS / realme UI）至少一台完成回归
- [ ] 签名指纹校验通过；Release 说明为中文
