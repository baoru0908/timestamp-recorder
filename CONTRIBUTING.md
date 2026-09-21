# 开发与发布规范

> 任何人（包括 AI 助手）参与本项目都必须遵守本规范；**每次发版前**过一遍文末的自查清单，
> 每个版本周期中途**至少自查一次**版本号与文档同步情况。

## 0. 交付流程 v2（2026-09-21 用户细化：唯一质量门 =「构建 → 推真机」）

> ⚠️ **自 v4.7.0 起按本版（v2）执行**。用户指出上一版流程「过于繁琐、易陷入自我循环、反复验证却
> 难有进展」，要求重新设计为**精简高效、只做一次、只看结论**的流程。
> 本版据此**重写** —— **不是取消验证，而是只保留一道质量门**：真机验证由可选项**回归为必经环节**。

**核心原则：全程只保留一个质量门 ——「构建 → 推真机」。其余环节只做「存在性确认」，不做复核。**
同一项**只验证一次**；每个环节的判定只有「过 / 不过」两种结果，**没有「再确认一下」**。

### 0.1 三个环节（含目标与退出条件）

| 环节 | 目标 | 退出条件（二值：过 / 不过） | 时间盒 |
|:---|:---|:---|:---|
| **S1 实现** | 代码写完、能编译 | 构建 `BUILD SUCCESSFUL`，无新增错误 | — |
| **S2 构建 → 推真机（不可省略）** | 证明「在真机上真的跑得起来、功能真的对」 | ① `adb install -r` 成功 ② App 启动无崩溃 ③ 本次改动的交互在真机走通 **且留有 1~3 张截图** | ≤ 10 分钟 |
| **S3 发版** | 把已验证的包发出去 | 发版三件套齐备（签名包 / tag / Release），用 3 条命令确认一次 | ≤ 5 分钟 |

### 0.2 S2 标准步骤（逐步照做）

1. `export JAVA_HOME=<JDK17 路径>`；构建 `./gradlew.bat assembleRelease`
   （或直接 `tools/release.ps1 -SkipRelease`）。
2. `adb devices` 确认设备在线 —— **离线就停下报告用户，不得跳过 S2**。
3. `adb install -r <签名 APK>`。
   ⚠️ 设备上装着正式版时，debug 包会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
   **绝不能卸载重装（会清空用户真实数据）** —— 必须签一个同签名的 release 包来装。
4. `adb shell am start -n com.timestamp.recorder/.MainActivity` 启动。
5. 走一遍本次改动涉及的功能点，截图 1~3 张（存临时目录，**不入库**）。
6. 退出条件：装机成功 + 无崩溃 + 功能符合预期（**有截图为证**）。

### 0.3 流程禁令（治「自我循环」的关键）

1. **不设独立 QA 复核角色** —— 取消「第二双眼睛」环节。
2. **同一项不重复验证**：S2 通过后不再重跑；发版后不回头验功能。
3. **只重跑失败项**：某步失败 → 修 → **只重跑那一步**，禁止全流程重跑。
4. **同一问题连续 3 次修不过 → 停下上报用户**，不在小圈子里打转。
5. **不做「为报告好看」的验证**（重复 lint、反复编译、多轮截图）。
6. 每个环节的判定只有「过 / 不过」两种结果，**没有「再确认一下」**。

### 0.4 仍然强制、不因精简而放宽

- **发版三件套**（签名包 / tag / Release）是「发版」的定义，**缺一即未发版**（逐条实测，见 §5）。
- **版本号语义**正确、`versionCode` 恰好 +1（§1 三段名 + 档位决策树）。
- **文档三同步**（release-notes / DEVELOPMENT.md / README，见 §2）。
- **签名指纹红线** + keystore 口令只从 `$env:TSR_KEYSTORE_PASS` 读取、**严禁写进任何入库文件**。

> 历史与纠偏：v4.6.0 / v4.6.1 曾走「独立复核 + 真机回归」；v4.7.0 一度把用户意图误读为
> **「取消真机取证」**。v2 流程**纠正**该误读：**真机验证（S2）是本流程的必经质量门**，
> 被取消的只是「独立 QA 角色」与「重复复核 / 反复自验收」，而非验证本身。

## 1. 版本号（语义化，versionCode 只增不减）

| 变更内容 | 版本动作 | 例 |
|:---|:---|:---|
| 用户能做以前做不到的事：新功能 / 新入口 / 新设置项 / 新依赖 / 新交互方式 | **minor** `x.y.0` | 3.1 → 3.2.0 |
| 完整换掉 UI 或架构（如 4.0.0 液态玻璃统一） | **major** `x.0.0` | 3.x → 4.0.0 |
| 以上都不是：只是让既有功能**更对 / 更快 / 更稳 / 更好看** | **patch** `x.y.z` | 4.6.0 → 4.6.1 |

**档位判定决策树**（按顺序问，命中即停）：

1. 用户能做以前做不到的事吗？（新功能 / 新入口 / 新设置项 / 新依赖 / 新交互方式）→ 是 → **`x.y.0`**
2. 整体换掉了 UI 或架构吗？→ 是 → **`x.0.0`**
3. 都不是？只是让既有功能更对 / 更快 / 更稳 / 更好看 → **`x.y.z`**

> 关键分界句：**「同一交互变快变准」≠ 新交互**。

**判例**（日后参照）：

| 版本 | 内容 | 档位 |
|:---|:---|:---|
| `v4.6.0` | 事件色前景阈值调整，全 App 显示策略变更 | **minor** |
| `v4.6.1` | 色盘跟手性 + 手柄出界：修复体验 / 视觉缺陷、**不引入新能力**（曾被误判为 minor） | **patch** ← 本次教训 |

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
- [ ] **真机回归（S2 必经，单机型即可）**：`adb install -r` 成功 + App 启动无崩溃 + 本次改动功能走通，**留有 1~3 张截图**（§0.2）
- [ ] 双机型（小米 HyperOS / realme UI）回归 —— **按需，非每版必做**（仅排查特定机型问题时才做）
- [ ] 签名指纹校验通过；Release 说明为中文
