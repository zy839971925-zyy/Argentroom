# UI_SCREEN_MAP — Silverphase iOS 视图 → Android Composable（C01，2026-11-14）

范围：手机竖屏编辑器（`EditorFrame` 判定 `wide == false` 的窗口）。宽屏/平板/横屏手机仍走原 `EditorTopBar` + `EditorBody`，未改。
iOS 真源：`sources/silverphase-v1.2.5/ios/Silverphase/Sources`（SHA 061fc495）。Android 代码：`workspace/android-app/app/src/main/java/com/argentroom/app/silverphase/`。
状态：**已接**＝接到 ParamsState/引擎并可操作；**禁用**＝位置保留、灰显、标“引擎暂缺”，点按显示原因；**缺失**＝本轮未做。
所有结论均为源码/编译层面；**DEVICE_NOT_AVAILABLE**：没有任何一项在真机或模拟器上运行过。

## 1. 逐视图对照

| iOS 视图（文件:行） | Android Composable（文件） | 状态 | 备注 |
|---|---|---|---|
| `compactWorkspace`（SilverphaseApp.swift:586） | `SpPhoneEditorBody`（SpEditorChrome.kt），由 `EditorScreen.kt` 的 `spPhoneBody` 接入 | 已接 | 仅窄屏分支；`EditorFrame` 顶栏槽在窄屏不再画原 `EditorTopBar` |
| 顶栏 `.topBarLeading` 返回 / `.topBarTrailing` 导出 + `NativeEditorMenu` | `SpEditorTopBar` | 已接 | 返回＝原 `onOpenLibrary`（先 checkpoint + flush 配方）；导出＝原 `requestExport()`（Android 导出表单，导出路径未改） |
| `NativeEditorMenu`（导入照片/撤销/重做/重置当前照片/适合窗口/1:1/设置/关于） | `SpOverlayHost` 菜单（SpOverlay.kt），条目在 `EditorScreen.kt` `menuItems` | 已接（1:1 缺） | 1:1 原始像素：Android `ViewerZoom` 只有 IN/OUT/FIT，未提供；“重置当前照片”打开原 Android「照片」面板（含重置与“应用上次编辑”） |
| `canvasStatus`（文件名/像素/状态） | `SpStatusOverlay` | 已接 | 像素尺寸由 `spReadPixelSize` 读原图；状态文字为 适屏预览/原图/处理中/正在读取/正在导出/显影失败 |
| `PhotoCanvas` | 原 `PreviewRegion`（未改）放在 `slots.canvas` | 已接 | 适配区域＝安全区内、顶栏之下、标签栏之上，**不随面板变化**（iOS 语义）。与 iOS 差异：iOS 照片画在透明栏后面，Android 让出顶栏与标签区，避免画布按钮/文件夹条被标签栏挡住 |
| 按住对比（LongPress 0.25 s，≤8pt） | `Modifier.spHoldToCompare` → `EditorViewerAction.SetOriginalPeek` | 已接 | Initial pass 观察、不消费，不抢捏合/平移 |
| `emptyEditor` | `SpEmptyEditor` | 已接 | 无照片时显示；按钮＝系统照片选择器 |
| `CompactProcessTabs` / `ProcessSegmentsHost`（七项 56pt） | `SpProcessTabs` | 已接 | 镜片跟手、抬起选择、重复点按重新发送；曲线改为 `.smooth(0.28)`（原先是 Compose 默认系弹簧，已移除） |
| `processPanel(side:false)` 抓手 28×3 / 44pt 标题区拖高 / ⋯ 重置本工艺 / 收起 / 1pt 分隔线 | `SpFloatingPanel` + `SpPanelDrag`（SpPanel.kt） | 已接 | ChromeSurface 半径 24；高度 25%–72%、最低 176dp、最高 520dp；`重置本工艺` 对应原 `categoryDefaults` 分组（见 §4） |
| `CompactPhoneLayout` | `SpPhoneLayout.of` | 已接 | 1:1 公式 |
| `ProcessControls(stage:)` 七个面板 | `SpProcessControls`（SpProcessControls.kt） | 已接/部分禁用 | 参数映射见 worklog/ENGINE_MAPPING.md；禁用项见 §2 |
| `PhoneMaterialModule` | `SpPhoneMaterialModule` | 已接 | 封面 96×54（iOS 64×48，按用户要求放大，封面为主） |
| `MaterialChooser(vertical:true, locksMaterialKind:true)` | `SpMaterialList` | 已接 | 分类菜单、搜索、收藏（长按）、最近、“不使用相纸”工作流卡；行封面 112×63（iOS 72×40，按用户要求放大） |
| `RulerParameterRow` / `ToolTickRuler` | `SpRulerRow` / `SpTickRuler` | 已接 | 12dp 刻度、40 区间、无惯性无回弹、越界阻尼 `excess/(1+|excess|/(2·step))·0.2`、双击复位、点数值输入 |
| `ToolSectionTitle` / `flag()` / `choice()` / `text()` | `SpSectionTitle` / `SpFlag` / `SpChoice` / `SpTextField` | 已接 | `choice()` 的弹出改为从数值处展开的 SpOverlay 菜单（iOS 为 UIMenu） |
| `MobileCropCanvas`（stage == .geometry） | 原 `CropCanvas` + `CropPanelContent`（进入“构图”自动 `OpenCrop`，离开 `closeCrop()` 一次写入历史） | 已接（等效） | Android 裁剪工具，非 iOS MobileGeometry 逐像素复刻 |
| 片边 `filmEdge` 面板 | `SpProcessControls(FILM_EDGE)`：Android 导出片框 + 日期背 | 部分（等效+禁用说明） | 物理片边（齿孔/片门/帧号/雾化/漏光）引擎缺；用 Android 导出片框作最接近替代，并显示画框预览 |
| `MobileExportPanel`（面板内导出） | 原 `EditorExportSheetHost`（Android 导出表单） | 等效 | 导出路径不改；视觉仍是 Android 原表单（M3 ModalBottomSheet，见 CODE_REVIEW） |
| `MobileFilmstrip`（编辑器内照片条） | 原 `FolderStrip`/左右滑动换片（在 PreviewRegion 内） | 等效 | iOS 为面板式照片条；Android 保留原画布上的文件夹条 |
| `MobileOperation` 通知卡 | 原 Snackbar | 缺失（等效） | 未做 Silverphase 通知卡样式 |
| `MobileDevelopment` 显影动画 | `SpDevelopReveal`（SpDevelopShader.kt，AGSL，API33+） | 已写未接 | 未接入画布（需要负片中间图，引擎未暴露）；系统关闭动画时直接调用 `finished()` 显示终图——已核对代码 |
| `MobileLibrary`（首屏图库） | 原 `LibraryScreen` | 未改 | 下一切片 |
| `MobileSettings` / `MobileIntroduction` / `MobileCamera*` | 原 Settings / Welcome；相机未移植 | 缺失 | 后续切片 |
| 宽屏 `WorkspaceLayout`（iPad） | 原 `EditorWideLayout` | 未改 | 平板布局后续切片（C06） |

## 2. 禁用（引擎暂缺）与缺失参数

| 工艺 | 参数 | 处理 |
|---|---|---|
| 拍摄 | 宽容度测量图 | 文字说明：需 Silverphase 场景测光探针 |
| 拍摄 | 影调：高光/阴影/白色色阶/黑色色阶（camera_tone_*） | 禁用 |
| 拍摄 | 光谱滤镜（camera_filter） | 禁用 |
| 胶片 | 颗粒强度 · 倍率（grain_amount 混合） | 禁用 |
| 胶片 | 抗光晕 高/中/低 预设 | 只给“跟随胶片/关闭”，并说明 |
| 胶片 | CineStill 高光余量 · EV（headroom） | 仅 CineStill 800T 时出现，禁用 |
| 印相 | 数字中间片（DI） | 禁用；材料列表中 DI 卡不显示 |
| 扫描 | 高光层次展开 | 禁用 |
| 扫描/输出 | EDR | **缺失**：本轮未放置占位行（下一轮按 ProcessControls.swift 补占位） |
| 片边 | 物理片边、日期样式、片边位置、毫米边距 | 说明文字 + Android 片框替代 |
| 菜单 | 1:1 原始像素 | 缺失（ViewerZoom 无此档） |

## 3. Android 原版独有功能及其新位置

以实际代码为准（EditorScreen.kt / EditorTopBar.kt / Category 枚举 / EditorExportSheetHost）。全部保留，未删除任何入口。

| Android 原功能 | 原位置 | 新位置（手机竖屏） |
|---|---|---|
| 系统照片选择器导入 | 顶栏 ⋯ › 打开照片 / 照片面板 | 菜单 › 从照片导入；空编辑器“打开照片” |
| 文件选择器导入（RAW/DNG/任意类型，含动态照片路由） | 照片面板 | 菜单 › 从文件导入（含 RAW） |
| 分享/打开方式/启动器快捷方式/拖放导入 | 全局效果 | 未改（`EditorIncomingImageEffects`、`dragAndDropTarget` 照常） |
| 撤销 / 重做 | 顶栏按钮 | 菜单 › 撤销、重做；硬件键盘快捷键照常 |
| 编辑历史列表（长按撤销） | 顶栏长按 | 菜单 › 编辑历史… |
| 分割对比视图 | 顶栏对比按钮 | 菜单 › 原图对比（勾选态）；按住照片看原图 |
| 直方图 / 溢出警告 | ⋯ 菜单 | 菜单 › 直方图 / 溢出警告（勾选态） |
| 100% 放大镜、ROI 局部高清 | ⋯ 菜单 / 捏合 | 菜单 › 100% 放大镜；捏合照常 |
| 点照片隐藏界面 | 画布单击 | 画布单击：Silverphase 顶栏/标签/面板 0.18 s 淡出并屏蔽触摸 |
| 左右滑动换片、文件夹条 | 画布 | 未改（PreviewRegion） |
| 照片信息（EXIF） | ⋯ 菜单 | 菜单 › 照片信息 |
| 导出表单（格式、位深、色彩空间、尺寸、预设、GPS、片框）、批量导出、半格双联、LUT 导出 | 导出胶囊 | 顶栏 导出（同一 `requestExport`）；批量/半格在导出表单内 |
| 导出后分享 | 导出胶囊“分享” | 菜单 › 分享（仅导出完成后可用） |
| 预设（内置 Looks、已存预设、强度、导入/导出、复制/粘贴设置） | Looks 模式 | 菜单 › 更多工具 › 预设 |
| 局部蒙版（几何、取色/取亮度） | Masks 模式 | 更多工具 › 蒙版（全屏几何/取样叠层照常） |
| 色调曲线、场景宽容度、反差蒙版 | Edit › Light | 更多工具 › 对应条目 |
| 饱和度/自然饱和度、印放色彩、成色剂细项 | Edit › Color | 更多工具 |
| 胶片画幅、颗粒细项、光晕细项、拍摄柔光细项 | Edit › Film | 更多工具（七项工艺里另有 Silverphase 粗粒度版本，同一参数） |
| 印放曝光/预闪/放大机镜头 | Edit › Print | 更多工具 |
| 眩光细项、镜头模糊、日期背细项 | Edit › Effects | 更多工具 |
| 扫描、RAW 降噪/坏点（仅 RAW） | Edit › Detail | 更多工具 |
| 片框（Frame） | Frame | 片边工艺内 + 更多工具 › 片框 |
| 输出色彩空间/编码/色域压缩、显示 | Output | 更多工具 |
| 光谱输入、实验项 | Lab | 更多工具 |
| 胶片/相纸选择 + 特性曲线查看器 | Looks › Film stock / Paper | 七项工艺材料浏览器；曲线查看器在 更多工具 › 胶片 |
| 照片面板（重置编辑、应用上次编辑、配方） | Looks › Photo | 菜单 › 重置当前照片 / 更多工具 › 照片 |
| GPU 预览开关、语言、主题等 | 设置 | 菜单 › 设置（原 Settings 页，未改） |
| 快速帮助 | ⋯ 菜单 | 菜单 › 帮助（`LocalQuickHelp` 存在时） |
| 关于 | ⋯ 菜单 | 菜单 › 关于 |
| 会话恢复、配方旁车文件、导出恢复 | 全局 | 未改 |

## 4. “重置本工艺”映射

拍摄＝EXPOSURE+RAW_WB+CAMERA_DIFFUSION；胶片＝FILM_FORMAT+GRAIN+HALATION；显影＝filmGammaFactor+COUPLERS；印相＝PRINT_EXPOSURE+PRINT_COLOR+PREFLASH+ENLARGER+morph；扫描＝SCANNER+GLARE；构图＝crop/cropCenter/cropSize/straighten（先丢弃裁剪草稿）；片边＝DATE_STAMP（导出片框属导出选项，不重置）。

## 5. 为单手操作所做的补强（与 iOS 的差异记录）

| 项 | iOS | Android | 原因 |
|---|---|---|---|
| 触摸目标 | 44pt | `SpDesign.hitSize = 48dp`（按钮、行、标题区、ⓘ、刻度尺条） | Android 最小触摸目标 48dp |
| 抓手条 | 14pt | 触摸高度 20dp（视觉仍 28×3） | 便于拇指拖 |
| 面板下滑关闭 | 无（只调高度） | 拖到最小高度后继续下拉／内容滚到顶后继续下拉 → 面板跟手折回标签；松手按速度决定关闭或回弹 | 用户要求 |
| 系统返回 | — | PredictiveBack 跟手驱动折回进度；取消则带速度回弹 | 用户要求 |
| 刻度尺 | 拖动刻度尺本体 | 整条 48dp 条带都接收横向拖动 | 用户要求，语义不变 |
| 参数说明 | macOS 版有 tooltip，iOS 无 | 点标题（带 ⓘ）或长按行展开 1–2 句说明 | 用户要求；文本来源见 tools/silverphase/sp_strings_extra.py 注释 |
| 拇指区 | 导出在右上 | **保持 iOS**：导出/菜单在顶部；材料、标签、刻度尺、按住对比都在下半屏 | 用户规则：冲突时以 iOS 布局为主 |
| 避让 | safe area | `WindowInsets.safeDrawing` 包住工作区（手势条、刘海） | — |
| 材料封面 | 72×40 / 64×48 | 112×63 / 96×54 | 用户要求封面为主 |
| 菜单 | UIMenu | SpOverlay 自绘菜单（从按钮展开） | 避免 M3 DropdownMenu 默认动画 |

## 6. 文字与本地化

- 新增 `res/values/strings_silverphase.xml`（英文，取 iOS `T(en, zh-Hans)` 的英文）与 `res/values-zh-rCN/strings_silverphase.xml`（简体中文，218 条）。其他语言回退英文。胶片名（如 Kodak Portra 400）不翻译，直接来自 Silverphase `StockCatalog.json`。
- Silverphase 组件内已无中文硬编码字面量（注释除外）。
- 参数说明 54 条中约 40 条来自 iOS/macOS Silverphase 源码（.help / Strings.swift），其余改写自 Spektrafilm-for-Android 同一引擎参数的 tooltip；Silverphase 行做了比例换算的，说明按其比例改写并保守表述。
