---
name: Tagloom 原生链接笔记
description: 从当前 Compose 实现及 Windows 宽窄窗口验收提取的局部视觉与交互规则
colors:
  primary: "#006B5F"
  onPrimary: "#FFFFFF"
  primaryContainer: "#D7EEE9"
  onPrimaryContainer: "#133C37"
  background: "#F8F9F8"
  surface: "#FCFDFC"
  onSurface: "#191C1B"
  onSurfaceVariant: "#52605C"
  surfaceContainerLowest: "#FFFFFF"
  surfaceContainerLow: "#F3F5F4"
  surfaceContainer: "#ECEFED"
  surfaceContainerHigh: "#E8EBEA"
  surfaceContainerHighest: "#E1E5E3"
  outline: "#737C79"
  outlineVariant: "#D7DEDB"
  inverseSurface: "#25312F"
  inverseOnSurface: "#E4E8E6"
  inversePrimary: "#87D5C9"
  error: "#B3261E"
  dark-primary: "#87D5C9"
  dark-onPrimary: "#003731"
  dark-primaryContainer: "#164F47"
  dark-onPrimaryContainer: "#B7EEE5"
  dark-background: "#151817"
  dark-surface: "#1B1E1D"
  dark-onSurface: "#E4E8E6"
  dark-onSurfaceVariant: "#BCC5C2"
  dark-surfaceContainerLow: "#181B1A"
  dark-surfaceContainer: "#202422"
  dark-surfaceContainerHigh: "#292E2C"
  dark-surfaceContainerHighest: "#333936"
  dark-outline: "#929C98"
  dark-outlineVariant: "#3C4441"
  dark-inverseSurface: "#E4E8E6"
  dark-inverseOnSurface: "#191C1B"
  dark-inversePrimary: "#006B5F"
  dark-error: "#FFB4AB"
typography:
  headlineLarge:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "28sp"
    fontWeight: 700
    lineHeight: "36sp"
  headlineMedium:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "22sp"
    fontWeight: 700
    lineHeight: "30sp"
  titleSmall:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
  bodyLarge:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "26sp"
  bodyMedium:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "22sp"
  bodySmall:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "18sp"
  labelLarge:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
  labelMedium:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "17sp"
  labelSmall:
    fontFamily: "FontFamily.SansSerif"
    fontSize: "11sp"
    fontWeight: 400
    lineHeight: "16sp"
rounded:
  extraSmall: "4dp"
  small: "6dp"
  medium: "8dp"
  large: "12dp"
  extraLarge: "14dp"
spacing:
  compact: "4dp"
  related: "6dp"
  control: "8dp"
  field: "10dp"
  row: "12dp"
  section: "16dp"
  pane: "24dp"
components:
  link-entry:
    backgroundColor: "{colors.surfaceContainerLow}"
    rounded: "{rounded.medium}"
    padding: "{spacing.section}"
  link-reference-preview:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.medium}"
    padding: "{spacing.section}"
  link-reference-inline:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.medium}"
    padding: "{spacing.control}"
  hierarchy-choice-selected:
    backgroundColor: "{colors.primaryContainer}"
    rounded: "{rounded.small}"
    typography: "{typography.bodyMedium}"
  hierarchy-choice-focused:
    backgroundColor: "{colors.surfaceContainerHigh}"
    rounded: "{rounded.small}"
    typography: "{typography.bodyMedium}"
  dropdown:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.medium}"
    padding: "{spacing.compact}"
  menu-item:
    textColor: "{colors.onSurface}"
    rounded: "{rounded.extraSmall}"
    typography: "{typography.bodyMedium}"
    height: "34dp"
  snackbar:
    backgroundColor: "{colors.inverseSurface}"
    textColor: "{colors.inverseOnSurface}"
---

# Design System: Tagloom 原生链接笔记

## Overview

**Creative North Star: "延续 Tagloom 原生知识工作台"**

链接笔记沿用现有 TagloomTheme 与 TagloomMenu：青绿标识可执行动作，中性画布承载正文，细轮廓区分引用和浮层。使用场景为 Operate，读写、选择与导航的可预测性优先。这里记录已落地的局部系统，没有重新选择视觉概念或引入 FORM seed。

本文件仅约束链接录入、引用、链接集合及共享层级单选的原生 Compose 实现。业务事实见[链接笔记说明](../link-note-design.md)；颜色、排版与形状以 [TagloomTheme.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/TagloomTheme.kt) 为源，公共菜单以 [TagloomMenu.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/TagloomMenu.kt) 为源。前置 YAML 是这些现有值的快照，单位保留原生 dp/sp，不把它们解释为 CSS px。

**Key Characteristics:**

- 青绿功能色、中性编辑画布与细轮廓共同建立层级。
- 居中正文、窄窗口单列与纵向管理列表延续现有工作台。
- 名称选择、箭头展开、内部笔记和外部网页分别响应。
- 搜索焦点、键盘游标和已保存选择分别表达，游标保持可见。

### 证据与验证边界

记录日期为 2026-10-21。本地原生评审材料包含 3 张 1280×900 宽窗口截图和 2 张 480×1000 窄窗口截图，均为 Windows JVM 生产 App 根节点：HTTP 503 后保留网址标题与空描述、直接手动编辑并保存、成功获取后的详情均没有常驻页脚。此前的 9 张宽窗口和 3 张窄窗口截图保留为本地历史材料，其中失败反馈、重试和详情反链页脚已经移除；窄窗口截图不代表 Android 设备。上述截图未纳入源码。

本轮局部修正的交接证据为 `:core:jvmTest` 全量 86 项、链接相关 `:app:shared:jvmTest` 10 项通过，以及 `:app:shared:compileAndroidMain` 通过。链接测试使用真实加密工作区／SQLite／HTTP，3 个测试方法包含 4 条生产 App 宽窄窗口、长目录和成功流程；失败后手动编辑已重读加密工作区确认保存。IDEA 编译调用超时后，使用仓库 Wrapper 隔离输出验收；本次没有执行 APK 或设备验收。当前五张截图经父线程与独立 reviewer 核对未发现实质 UI 问题；详细业务结果见[链接笔记说明](../link-note-design.md)。

此前 JVM core 86、shared 154、file-system 4、index 5，共 249 项，Android shared host 81 项与 assembleDebug，以及当时 3 条生产 App UI、7 条持久化与安全流程和 ship 结论均属于原版历史验收，不是本次全量结果。当时 resolved 的键盘游标可见性和公共 Snackbar 逆色语义保持原记录，不扩大本次验收平台或状态范围。

本次记录只核对代码与既有截图，没有重跑构建、重装或重新分发桌面发行物。当前 App 默认浅色；深色仅核对主题代码，没有独立深色或 hover 截图验收，没有连接 Android 设备，Windows 环境未验证 iOS。HTML/CSS detector 不适用于此原生界面。未纳入源码的历史网页原型只作行为参考，其 CSS、内存模拟与旧截图不能作为本文件的原生实现证据。

## Colors

主色是克制的青绿，正文、辅助文字、背景和分隔线采用带轻微绿倾向的中性色。表单、引用、列表和反馈读取 MaterialTheme.colorScheme，不在链接页面定义第二套配色。

### Primary

- **功能青绿**：`primary` 用于添加链接、可执行文字、输入光标及控件激活；`onPrimary` 承载实心动作文字。
- **浅青绿选中面**：`primaryContainer` 表示已经保存的分组选择；键盘游标单独使用中性的 `surfaceContainerHigh`，不是另一份已选值。

### Neutral

- `background` 与 `surface` 支持工作台和编辑画布；`surfaceContainerLow` 承载原位录入与搜索区，其他容器色表达已有导航、层次和状态。
- `onSurface` 承载正文，`onSurfaceVariant` 承载域名、分组路径、描述及辅助状态；`outlineVariant` 承载分隔线与引用边框，`outline` 用于菜单禁用内容。
- 公共 Snackbar 使用 `inverseSurface`／`inverseOnSurface`，操作使用 `inversePrimary`。深色对应前置 YAML 中的 `dark-` 角色；实际切换仍由 TagloomTheme 参数控制，本记录不宣称有已验收的界面切换入口。
- `error` 只表达无效 URL、创建或目录保存失败、危险菜单动作；网页获取失败不增加错误说明或颜色状态。

**The Semantic Role Rule.** 链接界面从公共主题读取颜色，Snackbar 从公共逆色角色读取背景、正文和动作色；不复制页面专属色值。

## Typography

字体统一使用 Compose `FontFamily.SansSerif` 的平台解析，不固定某个 Windows 字体，也没有新设展示字体。实际中文与拉丁字形由运行平台回退；尺寸和行高使用 sp，不能把截图像素反推为跨平台字号。

### Hierarchy

- `headlineLarge` 承载现有笔记标题，`headlineMedium` 承载链接集合标题；具体比例见前置 YAML。
- `titleSmall` 承载引用标题、集合条目及选择器标题；引用标题在组件内提升为 SemiBold（600），不改变公共 `titleSmall` 定义。
- `bodyLarge` 延续编辑正文，`bodyMedium` 承载 URL、目录名称和菜单；`bodySmall` 承载描述和目录辅助信息。
- `labelLarge` 承载录入区标题及分组动作，`labelMedium` 承载属性辅助标记，`labelSmall` 承载域名、分组路径与快捷键。
- 预览标题最多 2 行，行内引用标题最多 1 行；预览与集合描述最多 2 行并省略溢出。没有描述时自然收紧，不保留虚构占位文本。

## Layout

桌面工作台沿用固定侧栏（232dp）与居中正文最大宽（760dp）。工作区宽度达到 720dp 时显示侧栏，小于此值改为现有顶部导航；同一 720dp 门槛决定独立录入和分组选择的宿主形式。正文自身另有现有 600dp 内边距规则：窄栏两侧保留 Block 手柄槽加 8dp，宽栏两侧保留 48dp；不是新增的设备断点。出处为 [TagloomWorkspaceScreen.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/TagloomWorkspaceScreen.kt) 和 [EditorPane.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/EditorPane.kt)。

分组选择在宽窗口使用锚定浮层（344dp），窄窗口使用填宽底部面板，两者共用 HierarchicalPickerContent。选择列表最大高度为 300dp 并独立滚动，搜索与底部维护入口留在列表外。当前树形缩进每层 18dp，视觉缩进最多计算 8 层，不代表领域数据只允许 8 层。管理对话框最大宽度 560dp，滚动内容最大高度 460dp，保持缩进纵向列表。

链接集合是填充可用宽度的纵向列表，使用水平 24dp、垂直 20dp 外边距；不套用笔记正文的 760dp 上限。局部间距复用前置 YAML 中反复出现的值，该 spacing 表是现有使用节奏的提取，不是新建的 Kotlin Token 对象。

**The Shared Content Rule.** 宽窄窗口只替换选择器宿主，复用同一搜索、层级、选择和维护内容，不维护两套选择语义。

## Elevation & Depth

正文和引用默认平面化，用中性色差与细边框建立秩序。TagloomDropdownMenu 明确采用零 tonal elevation 与 8dp shadow elevation，外围 1dp `outlineVariant` 边框降低至 0.65 alpha，内衬 4dp；这是真实 Compose elevation，不翻译为网页 box-shadow。底部面板和 AlertDialog 沿用 Material3 默认遮罩与层次，本轮没有另设动效时长、缓动曲线或阴影 Token。

## Shapes

公共形状在前置 YAML 中保留 Theme 的 `extraSmall` 至 `extraLarge` 名称。原位录入和引用复用 `medium`，选择行和搜索面使用 `small`，菜单项使用 `extraSmall`；菜单容器显式圆角与 `medium` 相同。预览引用使用 1dp `outlineVariant` 边框，行内模式去掉该边框。Button、TextButton、OutlinedTextField、AlertDialog 和 ModalBottomSheet 沿用现有 Material3 组件默认形态，不能把它们统一改成小圆角矩形。

链接与外链图标使用[公共矢量资源](../../app/shared/src/commonMain/composeResources/drawable/)，24×24 viewport、1.7 stroke、圆端点／圆转角，界面按用途渲染为 16–18dp。现有六点 Block 手柄继续负责块菜单和拖动，与链接主体、外链按钮分别响应。

## Components

### Buttons and fields

实心 Button 承担确认与添加，TextButton 承担分组、导航及维护，OutlinedButton 承担管理表单的父级选择；公共样式由 Material3 和 TagloomTheme 决定。[LinkEntryForm.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/LinkEntryForm.kt) 的 URL 使用单行 OutlinedTextField，默认聚焦；输入法组合态不提交，Enter 确认，Esc 退出并恢复来源段落。创建中禁用输入、关闭和选择，确认按钮呈现轻量进度；错误保留草稿并在原位说明。

`/link` 在正文原位展开录入；分组选定后焦点回到 URL，可继续 Enter。独立新建或从集合添加复用此表单：宽窗口使用最大宽度 560dp 的 Dialog，窄窗口使用底部面板。有效确认前不创建空 Note。链接继承 Note、URL 必填、分组可空等约束属于业务规则，见业务说明。

### Shared menu

TagloomMenuItem 单行高度 34dp，带说明项高度 48dp；文字、图标与快捷键在一条紧凑横向结构中对齐。代码中的 hover 使用 `onSurface` 的 0.06 alpha，危险动作使用 `error` 的 0.08 alpha，禁用项不响应且使用 `outline`。这些是代码中已有状态定义，本轮没有单独 hover 截图验收。

### Hierarchical option picker

[HierarchicalOptionPicker.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/HierarchicalOptionPicker.kt) 的名称和单选标记选择本级，右侧独立箭头只展开／收起；父级和叶子均可选。搜索匹配名称或完整路径并在结果中显示完整路径，避免同名项含混；“未分组”位于顶部，对应 null。

重开先展开选中项的祖先，定位到选中项并聚焦搜索。上下键移动独立键盘游标，右键展开，左键收起或回到父级，Enter 选择，Esc 取消。游标变化、搜索和行集合变化均通过 BringIntoViewRequester 保持当前行可见；选中态优先于游标底色，单选标记仍表达已保存值。

### Group management

[PropertyOptionManagementDialog.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/PropertyOptionManagementDialog.kt) 使用缩进纵向列表、路径辅助文本和右箭头进入编辑，不使用表格。新建、重命名和移动保留稳定目录 ID；父级菜单排除自身和后代。已用选项或有子项的选项禁删并解释原因，已用分组可进入相关笔记筛选。编辑保存后返回列表，列表的结束动作为“完成”；新建入口可直接保存并应用新值。

### Link reference

[LinkReferenceBlock.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/LinkReferenceBlock.kt) 使用现有 NoteReferenceBlockContent 指向目标，不复制网页字段。预览展示域名、标题、最多两行描述和可选分组路径，没有图片时直接收紧；行内模式仅保留单行标题。主体进入内部 Note，独立外链按钮打开网页，更多菜单处理刷新、预览／行内与移除引用。移除引用不删除目标笔记，目标不可用时保留引用并提供重选。

### Link detail and collection

详情复用笔记标题、描述、类型身份、属性和正文；公共 EditorPageHeader 与 DocumentDescription 可直接编辑，URL 保留独立外链动作，分组复用共享选择器。稳定引用关系与双链索引保留，详情不再追加分隔线、获取反馈或“引用此链接的笔记”常驻页脚。[LinkCollectionPane.kt](../../app/shared/src/commonMain/kotlin/com/oruke/tagloom/ui/LinkCollectionPane.kt) 以纵向条目展示相同目标，搜索标题、URL 和描述；分组筛选支持全部、null 未分组、本级及包含下级。条目主体进入 Note，外链图标单独打开网页，空结果使用已有辅助文字反馈。

### Silent metadata fetch and Snackbar

创建时以完整网址 `url.value` 同时初始化笔记标题与自动标题来源，描述留空；确认 Note 与来源引用后，客户端静默获取网页信息，取得标题时才回填网页标题。失败保留网址标题、空描述和引用，直接使用现有标题／描述输入区编辑；获取过程不渲染进度、失败、重试或常驻刷新 UI。只有引用的更多菜单保留显式重新获取入口，复用同一 Note 与引用；手动字段保护与取消规则见业务说明。

保存／撤销沿用公共 Snackbar，其逆色背景、正文和动作在宽窄浅色截图中可见；深色对应角色仅经代码核对。本次没有添加页面专属 Snackbar 配色或主题切换控件。

## Do's and Don'ts

### Do:

- **Do** 从 TagloomTheme、TagloomMenu 和现有 Material3 组件取得颜色、排版、形状及菜单行为。
- **Do** 复用共享 720dp 宿主规则，保留 232dp 侧栏、760dp 正文上限和纵向维护列表的既有职责。
- **Do** 区分已选值、键盘游标和搜索焦点，并让键盘当前行滚入可见区域。
- **Do** 保持名称选择与箭头展开、内部 Note 与外部网页、块手柄与引用主体分别响应。
- **Do** 以完整路径区分同名分组，以 null 表示未分组，并保留稳定目录 ID。

### Don't:

- **Don't** 把网页原型的 CSS、内存保存或旧深色截图写成原生实现与验收事实。
- **Don't** 为链接页面复制独立色板、菜单、层级选择器或宽窄窗口选择语义。
- **Don't** 把获取失败变成删除链接、清空 URL、插入重复引用或追加获取状态、重试与常驻页脚。
- **Don't** 将普通 Card 恢复或尚未实现的 Callout 计入本轮原生能力。
- **Don't** 将 Windows 窄窗口、Android host 测试或构建成功等同于 Android/iOS 设备与全部交互状态验收。

未固化为规则：旧网页原型的视觉实现、单次截图数据与环境地址、未实测的深色／hover 效果，以及尚未实现的普通 Card／Callout；它们不能成为后续原生界面的既定设计体系。
