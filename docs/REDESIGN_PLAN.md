# 时间戳记录 App · UI 重构方案（目标版本 3.0.0）

> 状态：**方案已全部实施完毕** —— v3.0.0 重构主体 · 图标 v3.0.1 · 时间线 v3.1 ·
> 液态玻璃 v4.0 · 内置教程与开发者卡 v4.1。
> **唯一未落地项：备注 / 标签**（当时建议留到 3.1，实际一直顺延，待排期）。
> 本文档保留作设计决策存档；实施细节与踩坑记录见 `DEVELOPMENT.md`。
>
> 本文档基于现有代码现状 + 两份参考文档（小米澎湃 OS 小部件规范、开源仓库范式核查）编写。
> 设计目标：App 内按钮 / 桌面小组件单击 → 快速记录当前时间戳，可选简短备注与标签。

---

## 0. 现状诊断（设计层面）

| 维度 | 现状 | 评价 |
|---|---|---|
| 技术栈 | Kotlin + View 体系 + Material 3 DayNight + 毛玻璃风格 | 稳健，无需大换血 |
| 主题 | `Theme.Material3.DayNight.NoActionBar`，品牌蓝硬编码 + M3 动态取色已启用 | 动态取色已有；dark 仅渐变/液态背景有 night 版，卡片色板未适配 |
| 导航 | 3 个独立 Activity（Main / Detail / Settings）+ 编辑对话框 + widget 配置 Activity | 可用，但每页重复写 toolbar / 沉浸式 / inset |
| 数据模型 | `TimestampEvent(id,name,color,createdAt)` + `records:List<Long>`，SharedPreferences | 缺排序字段、缺备注/标签 |
| 卡片视觉 | 事件卡（色圆点+名称+信息+快捷记录按钮）；记录项（序号+时间+unix+复制） | 偏朴素，可更现代 |
| 小组件 | 多事件（彩色圆点+中性行）、单事件（大按钮）；圆角 4 档；一键添加（小米失效降级） | 多事件已修丑；缺 2×1 胶囊；圆角未对齐 MIUI 规范 |
| 图标 | **传统 PNG bitmap**（`mipmap-{h, m, xh, xxh, xxxh}dpi/ic_launcher.png`）| ⚠️ **非自适应图标**，圆角需手动裁；需重构 |
| 图表 | 无依赖 | 统计视图需现加（自绘或引库） |

**已具备的好基础（保留，不全推倒）**：动态取色、沉浸式状态栏/导航栏、`setSupportActionBar` 已修正、批量管理、FAB 位置切换、widget 圆角档位、多事件固定行渲染。

---

## 1. 设计目标与原则

### 来自文档 1（小米澎湃 OS 小部件规范）
- 圆角：2×2 用 38px / 1080p（≈12.7dp），MIUI 统一裁 46/62px（≈15.3/20.7dp）。→ 我们 widget 圆角档位改用 **12 / 16 / 20dp** 对齐规范。
- 安全区：内容距边 ≥ 8dp。
- 字体：主 Medium、次 Regular + 40% 透明（权重区分，不依赖 MiSans 打包）。
- 深浅色：widget 随系统 DayNight。
- 倾向方案：路线 A（标准安卓组件）保留；**新增 2×1 胶囊单事件 widget**；路线 B（上小米商店）成本过高，暂缓。

### 来自文档 2（开源参考，已核实真伪与许可证）
- ✅ 借鉴**交互范式**（仅学思路，不抄代码）：快速记录弹窗、时间线+标签分组、亮暗双主题、备份界面、极简单页。
- ⛔ **GPL 红线**：Simple Time Tracker / Everyday Tasks 为 GPL-3.0 强 Copyleft，**严禁复制其代码片段**；Super Productivity（MIT）可参考但它是 TS 跨平台，代码不可搬。所有实现一律自写。
- ⚠️ 已核实 5 个仓库中 TasClock 不存在、TimeTracer 描述虚假，均不参考。

### 通用
- 延续毛玻璃 + 渐变品牌调性；Material 3 组件一致性；零权限、纯离线。

---

## 2. 设计系统（Design Tokens）

统一到语义 token，消除散落硬编码：

| Token | 值 | 用途 |
|---|---|---|
| radius.xs / sm / md / lg / xl | 8 / 12 / 16 / 20 / 24 dp | 统一圆角 |
| radius.pill | 999 dp | 胶囊/FAB |
| space.1..6 | 4 / 8 / 12 / 16 / 20 / 24 dp | 8dp 栅格间距 |
| color.surfaceGlass / strokeGlass | 毛玻璃卡片/描边 | 延续现有 glass 体系 |
| color.accent | 跟随 M3 dynamic 或品牌蓝 | 强调色 |
| type.title / body / label | Medium 500 / Regular 400 / 次要 +60% 透明 | 字体层级 |

**深浅色补全**：在 `values-night/` 补充完整 M3 暗色色板（当前仅渐变/液态背景有 night 版，卡片 glass 仍是浅色硬编码）。

**字体策略**：用系统默认（Roboto/Noto Sans）+ 字重区分，**不引入 MiSans**（需打包字体文件、增体积、潜在授权风险；小米规范里的 MiSans 是针对 MIUI 系统 widget，App 内不强制）。

---

## 3. 架构方案（含决策点）

- **路线 P（推荐 · 渐进式）**：抽出 `BaseActivity` 统一工具栏（毛玻璃 toolbar + 返回键 + 标题）、`enableEdgeToEdge` + WindowInsets 处理、design token 接入；主页/详情/设置三页继承之；新增统计页/备份页同享。风险低、可维护性强。
- **路线 N（激进式）**：单 Activity + Navigation Component + Fragment 全量重组。结构最优雅，但回归面大、工作量高。

> 推荐路线 P。最终由主人拍板（见 §10）。

---

## 4. 各界面重构

### 4.1 主页（事件列表）
- Header：大标题 + 副标题，渐变/玻璃。
- 事件卡片升级：左侧 **4dp 事件色竖条**（替代小圆点，更现代）+ 名称 + 最近记录时间 + 快捷记录按钮（按钮样式统一 token，事件色填充）。
- **拖拽排序**：`ItemTouchHelper` 长按拖拽重排（事件排序功能载体）；手柄在卡片左侧。
- FAB：保留液态玻璃，位置可切（左/中/右）。
- 空状态：插画 + 引导文案。

### 4.2 事件详情页
- 顶部事件色 header（大色块 + 事件名 + 实时计数）。
- 大记录按钮保留。
- 记录列表升级为**时间线**：左侧竖线 + 圆点节点，右侧时间 + unix + 复制/勾选。
- 批量管理栏保留并优化。
- 新增「统计」入口（跳转统计页）。

### 4.3 统计页（新功能）
- 每事件记录数：横向柱状 / 饼图。
- 时间分布：按日 / 周 / 月柱状。
- 实现：**自绘 Canvas**（轻量、风格统一，推荐）vs 引 **MPAndroidChart**（Apache-2.0 合规，但体积大、风格难调）。

### 4.4 设置页
- 分组卡片：外观（FAB 位置、主题模式[跟随/浅/深]、**圆角档位按小米规范**）、小组件（圆角、一键添加）、**数据（备份/恢复——新）**、关于（版本号 / GitHub 仓库 / 字体出处 / 开源许可 / 反馈）。
- 全程 `NestedScrollView` 已具备。

### 4.5 编辑事件对话框
- 名称 + 颜色选择保留；布局用 token 重排。

---

## 5. 三个新功能融入

### 5.1 事件排序
- 模型加 `order: Int`；默认按 `createdAt`；开启「手动排序」后主页拖拽并持久化。
- 设置可切「手动 / 自动(时间)」；旧数据迁移 `order = 创建顺序`。

### 5.2 JSON 备份恢复
- 新增 `BackupRepository`：
  ```json
  {
    "app": "TimestampRecorder", "version": 1,
    "exportedAt": 1690000000000,
    "events": [
      {"id": 1, "name": "睡觉", "color": -123456, "createdAt": 1, "order": 0,
       "records": [1690000000000, 1690000060000]}
    ]
  }
  ```
- 导出到 `Download/TimestampRecorder/backup_YYYYMMDD_HHMM.json`；导入解析 + 校验 + **合并策略**（询问覆盖/合并）。
- 设置页「数据」区：备份 / 恢复（SAF 文件选择，**不申请存储权限**也能用 `ACTION_CREATE_DOCUMENT`/`OPEN_DOCUMENT`）。
- 备份 UI 范式借鉴 Super Productivity，代码自写。

### 5.3 统计视图
- 见 §4.3。数据源 = `EventRepository` 现有 `getRecords`。

---

## 6. 小米 widget 规范落地

- **圆角**：容器圆角档位改为 12 / 16 / 20dp（对齐 MIUI 38/46/62px @1080p）。
- **安全区**：widget 内边距统一 ≥ 8dp（现状 12dp 已合规）。
- **深浅色**：widget 随 `DayNight`；补 dark 配色。
- **字体**：系统默认 + 字重区分（主 Medium/次 Regular+透明）。
- **新增 2×1 胶囊单事件 widget**：横向胶囊，左事件色圆 + 名称，右大记录按钮；更省桌面空间、更美观。配置页复用现有 `WidgetSingleConfigureActivity`。

---

## 7. 图标重构（本次搁置 ⏸）

> **决策（2026-09-14）**：本次重构暂不替换图标，保持现有传统 PNG 位图（`mipmap-*dpi/ic_launcher.png`）。待 3.0 主体完成后再单独处理。以下为预留规格，供后续参考。

**现状**：当前是**传统 PNG 位图**（每张已是方形裁好形状），**不是自适应图标**。

→ 之前对话里我说「圆角交给系统自动裁」是**错的**：传统 PNG 不会自动裁，圆角需要你自己裁好。

**两条路，请主人选**：

| 路线 | 主人要交付 | 圆角 | 工作量 |
|---|---|---|---|
| **A. 保持传统 PNG（推荐起步）** | 5 张已裁好形状的方形 PNG（48/72/96/144/192 px，对应 mdpi~xxxhdpi） | **需自裁** | 小 |
| **B. 升级自适应图标（更现代）** | 两张 108dp 方图：①前景**透明背景**、主体居中(72dp 区域) ②背景**不透明**方块 | **系统自动裁**，你只画方图 | 中（我生成 xml + 密度图） |

无论哪条，你都只需画**方图**，区别仅在于「自己裁圆角」还是「交系统裁」。

---

## 8. 数据模型迁移

```kotlin
data class TimestampEvent(
    val id: Long,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val order: Int = 0          // 新增：手动排序
    // 备注/标签预留（建议留到 3.1，避免 3.0 范围膨胀）
)
```
- `records:List<Long>` 结构不变。
- 迁移：旧数据 `order` 默认按列表顺序赋值。

---

## 9. 版本与发布

- **目标大版本 3.0.0**（versionCode 从 11 起，接续当前 10）。
- Release 走「大版本完整重介绍」分支（已写进 `gh_release.py`）。
- 内部可拆里程碑（见 §11），但**最终一次性发 3.0.0**（主人要求随大版本）。

---

## 10. 需主人拍板的决策清单

1. **架构**：路线 P（BaseActivity 渐进，推荐） / 路线 N（单 Activity+Nav 激进）
2. **图表**：自绘 Canvas（推荐） / MPAndroidChart 库
3. **图标**：路线 A（传统 PNG 自裁圆角） / 路线 B（自适应图标，交系统裁）
4. **备注/标签**：3.0 做 / 留到 3.1（建议留，控制范围）
5. **2×1 胶囊 widget**：做（推荐，文档1建议） / 暂不做

---

## 11. 实施路线图（内部里程碑，最终合发 3.0.0）

- **Phase 0** 设计系统（tokens + night 色板 + **液态玻璃/高斯模糊材质探索** + **MiSans 字体集成**）+ `BaseActivity` 抽象 + **关于页/开源引导**骨架
- **Phase 1** 主页重构（卡片升级 + 拖拽排序 + order 持久化 + 迁移）
- **Phase 2** 详情页时间线 + 新增统计页（图表）
- **Phase 3** JSON 备份恢复（BackupRepository + 设置入口 + SAF）
- **Phase 4** widget：圆角对齐 MIUI 规范 + 新增 2×1 胶囊单事件
- **Phase 5** 图标替换（按主人选定路线）+ 大版本收尾、编译、装机、Release

> 每 Phase 完成后建议先本地编译验证，最终统一升 3.0.0 发版。
