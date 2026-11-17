# Silverphase v1.2.5 动效规格（从 iOS 源码逐条提取）

来源：sources/silverphase-v1.2.5/ios/Silverphase/Sources（锁定 SHA 061fc495）。全部为观察到的源码事实；Compose 对应列为移植方案。

## 关键结论
- Silverphase 动效风格是**克制型**：几乎全部为 easeOut 0.14–0.22 s 与 `.smooth` 0.28–0.30 s；**全工程没有任何 spring/bouncy 调用**。
- 刻度尺明确写明 "Precision release deliberately has no velocity projection or spring overshoot"（ToolTickRuler.swift:1-3）。移植时**不得**加惯性/回弹。
- 所有动画都尊重 `accessibilityReduceMotion`（为 nil 或改 opacity）；玻璃尊重 `accessibilityReduceTransparency`（改实色 canvas #181818）。
- 唯一"华丽"效果：①系统 UISegmentedControl 的 Liquid Glass 选中镜片（七工艺 56pt 标签栏，UIKit 自带追踪/拖拽动画）；②显影动画（Metal 着色器，相纸 2.2 s、扫描 0.35 s，逐帧 progress 线性 0→1）。

## 逐项
| 位置 | iOS | Compose 移植 |
|---|---|---|
| 工艺面板出现/消失 SilverphaseApp:434,457 | transition move(bottom)+opacity；`.smooth(duration:0.28)` | slideInVertically+fadeIn，`spring(dampingRatio=1, stiffness≈(2π/0.28)²≈503)`（smooth=无回弹弹簧，response 0.28） |
| 材料浏览器切换 :458 | `.smooth(0.28)` | 同上 |
| 工艺切换 stage :459, :842 | `.easeInOut(0.18)`；内容 `.id(stage).transition(.opacity)` | Crossfade(tween(180, FastOutSlowIn 近似 easeInOut)) |
| 通知卡 MobileOperation:104-112 | move(top)+opacity，`.smooth(0.3)`；减少动态为 easeOut 0.15 opacity | slideIn(top)+fade spring(1, ≈439) |
| 刻度尺 ToolTickRuler | 拖动跟手；松手 easeOut 0.14 归位到量化值；随后高亮 easeOut 0.18 淡出；双击重置 easeOut 0.14；越界橡皮筋 `excess/(1+|excess|/(2·step))·0.2`；每跨 1 tick 触感：主刻度 intensity 1.0、次刻度 0.6，松手 0.4，light 风格 | 已在 SpRuler.kt 按此实现；触感需映射：tick→`HapticFeedbackConstants.SEGMENT_FREQUENT_TICK`(API34+)/CLOCK_TICK，主刻度→SEGMENT_TICK，松手→`VibrationEffect.Composition.PRIMITIVE_TICK` scale 0.4 |
| 刻度尺绘制 | 刻度 12pt 间距；次 12×1.2，主 20×1.8；高亮渐变到 22×3、accent；smoothstep 尾迹 1.25 间距；两端 18%/82% 渐隐遮罩；指针 3×22 accent 胶囊 + 阴影 r4 α0.3 | Canvas 1:1 |
| 数值滚动 MobileCameraChrome:63 | `.contentTransition(.numericText)` easeOut 0.16 | AnimatedContent 逐位上下滚动 tween(160, EaseOut) |
| 按住对比 SilverphaseApp:947 | LongPress 0.25 s，maxDistance 8 | 已在 SpEditorChrome 250 ms |
| 面板拖拽高度 :756 | dockFraction 0.25–0.72 跟手，无动画、无吸附 | 不加吸附 |
| 画布缩放 :940 | 捏合 1…8×、松手直接定值，≤1 时偏移归零，无回弹动画 | 不加回弹 |
| 相机/其他 | easeOut 0.16–0.22、easeInOut 0.22/0.34；对焦框 keyframe；scale 0.85+opacity | 相机属后续切片 |
| 玻璃 ChromeSurface | iOS26 `.glassEffect(.regular[.interactive])`，ConcentricRectangle 16pt；不加暗底板；`GlassEffectContainer(spacing:12)` 合并相邻玻璃；iOS<26 `.regularMaterial` | Android：API31+ 背景模糊+折射（候选库见调研），否则半透明实色；减少透明度→#181818 |
| 显影 MobileDevelopment | Metal 片元着色器，2.2 s / 0.35 s | AGSL RuntimeShader（API33+）逐帧驱动，否则 crossfade；待读 SilverphaseUI.metal 源 |

## 换算
SwiftUI `.smooth(duration:d)` = `Spring(duration:d, bounce:0)`；stiffness = (2π/d)²（mass=1），damping = 4π/d → Compose `spring(dampingRatio = 1f, stiffness = (2π/d)²)`。d=0.28 → 503.5；d=0.30 → 438.6。
easeOut/easeInOut：SwiftUI 默认三次贝塞尔 easeOut (0,0,0.58,1)、easeInOut (0.42,0,0.58,1) → `CubicBezierEasing`。（换算公式与贝塞尔值待调研报告核实来源）

## 预览表面核查（2026-11-14）
- 默认预览是 Compose `Image(bitmap)`；`LutGpuPreview` 的 GLSurfaceView 路径在 AppSettings.kt:69 默认关闭。→ 默认路径下 Haze/backdrop 可以采到照片，玻璃成立。若开启 GPU 预览，需等 TextureView 重写，否则玻璃只模糊 UI。
- 已加 SpDevelopShader.kt（AGSL 逐行移植 developPaper，API33+，编译通过，未真机验证）。
- 库调研报告：/workspace/projects/2604a0bf-9094-48f6-9f91-7cd99e69d3f9/work/motion-visual-libs-research-2026-11.md

## 曲线核实结论（覆盖上文"待核实"）
- `.easeInOut` → CubicBezierEasing(0.42,0,0.58,1)（= androidx EaseInOut），**不是** FastOutSlowInEasing。
- `.easeOut` → (0,0,0.58,1)（= EaseOut）；`.easeIn` → (0.42,0,1,1)。
- `.smooth(d)` → spring(dampingRatio=1, stiffness=(2π/d)²)；0.28→503.5，0.30→438.6。与 Apple 文档示例 157.9 及 androidx SpringSimulation 一致。
- 禁用 Compose 默认 spring()（k1500）与 M3 MotionScheme / ModalBottomSheet 默认 tween。
- 本工程 iOS 源码无 spring/bouncy，故不需负 bounce 公式。
- Reduce Motion：Android 系统关动画时 Compose 直接跳终态；iOS 是改淡入淡出。**用户已确认（2026-11-14）**：检测 ANIMATOR_DURATION_SCALE==0 时改用 0.2 s 透明度转场（在 MotionDurationScale(1f) 上下文中运行），已在 SpMotion.kt / SpMorph.kt 实现。
- 选库（均 Apache-2.0/MIT，GPL-3.0 兼容，未引入）：haze 2.1.0、Kyant shapes 1.2.1（连续圆角）、telephoto 0.19.0（固定版本）、SharedTransitionLayout、JankStats 1.0.0 / Baseline Profile 1.5.0。排除：Glasmio（含 Apple 模拟器图像、逆向数据）、iOS2Android skill（CC BY-NC-ND）。

## C01 实现（2026-11-14）
- 面板/材料浏览器/更多工具抽屉/所有菜单：SpMorph（单 Animatable 容器形变，`.smooth(0.28)` 弹簧），从触发控件的根坐标矩形与圆角展开，收回到当前选中的触发控件。为何不用 sharedBounds 见 CODE_REVIEW.md。
- 下滑关闭与预测性返回直接驱动形变进度，松手把速度（进度/秒）交给弹簧的 initialVelocity。
- 工艺切换内容：Crossfade(easeInOut 180)。chrome 隐藏：easeOut 180 透明度。参数说明展开：expandVertically(.smooth 0.28)+fadeIn(easeOut 180)。
- 刻度尺：松手 easeOut 140 归位、高亮 easeOut 180 淡出，无惯性无回弹；触感按 API 级别映射（SpHaptics）。
