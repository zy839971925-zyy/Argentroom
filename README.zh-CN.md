<div align="center">

<img src="docs/brand/argentroom-icon.png" width="112" alt="银间 Argentroom 应用图标">

# 银间 · Argentroom

**装进口袋的暗房。用数码拍，用胶片冲。**

[English](README.md) · 简体中文

[![下载 APK](https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD_APK-v1.0.2-c9b38a?style=for-the-badge&logo=android&logoColor=1b1813&labelColor=c9b38a&color=1b1813)](../../releases/latest)

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-c9b38a?labelColor=2a2620)](#安装)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-c9b38a?labelColor=2a2620)](#安装)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-c9b38a?labelColor=2a2620)](LICENSE)
[![Engine](https://img.shields.io/badge/engine-C%2B%2B_%C2%B7_Vulkan-c9b38a?labelColor=2a2620)](#原理)

<sub>基于 <a href="https://github.com/thetechgeekko/Spektrafilm-android">Spektrafilm for Android</a> 的非官方独立衍生版，界面移植自 <a href="https://github.com/akinal5765-byte/Silverphase">Silverphase</a>。<br>
胶片建模由 <a href="https://github.com/andreavolpato/spektrafilm">spektrafilm</a> 驱动。无需账号，无广告，无追踪。</sub>

</div>

---

## 为什么是银间

多数胶片滤镜只是一张颜色 LUT，把像素往某种氛围上推。银间做的是暗房本身：每个像素先变成一段光谱，
曝光到实测的胶片上，再经放大机投射到实测的相纸上，最后扫描输出。于是 LUT 做不到的东西都留了下来：
胶片高光怎样柔和地滚降，路灯周围怎样泛起红色光晕，颗粒怎样落在暗部。

「银间」取意银盐之间，也就是成像的那间暗房。

## 亮点

<table>
  <tr>
    <td width="33%" valign="top">
      <h4>🎞️ 25 款胶片 · 9 款相纸</h4>
      Portra、Ektar、Gold、Vision3、Velvia、Provia、Kodachrome、Tri-X、HP5、CineStill 800T 等，
      全部基于实测光谱数据模拟。任意彩色胶片可配任意相纸。
    </td>
    <td width="33%" valign="top">
      <h4>🖤 真正的暗房界面</h4>
      完整移植 Silverphase v1.2.5 界面：胶片条选片、安静的深色工作区、连续形变的面板动效，
      以及画面四周的物理片边。
    </td>
    <td width="33%" valign="top">
      <h4>✨ 光晕与颗粒，忠于原作</h4>
      采用 Silverphase 的颗粒参数与光晕高光余量，CineStill 的红晕会出现在它该出现的地方。
      见<a href="#还原度说明">还原度说明</a>。
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <h4>📷 内置相机</h4>
      直接拍进暗房，每一张都按你选好的胶片冲洗。
    </td>
    <td width="33%" valign="top">
      <h4>🗂️ 图库与完整编辑器</h4>
      曝光、白平衡、曲线、蒙版（径向、渐变、色彩、端侧人像皮肤）、裁切、画框、历史记录。
      RAW / DNG 经 LibRaw 解码，原图永不修改。
    </td>
    <td width="33%" valign="top">
      <h4>🛠️ 可分享的诊断报告</h4>
      设置 → 诊断与错误日志，可导出一份文本报告（设备、GPU、进程退出记录、近期日志），
      方便附在 Issue 里。除非你主动发送，任何数据都不离开手机。
    </td>
  </tr>
</table>

## 安装

1. 在 [最新 Release](../../releases/latest) 下载 **`Argentroom-v1.0.2.apk`**。
2. 打开安装；系统询问时，允许浏览器或文件管理器安装应用。
3. 选一张照片，点一款胶片，导出。

要求：Android 7.0（API 24）及以上，64 位 ARM 手机（`arm64-v8a`），推荐支持 Vulkan 1.1 的 GPU
（高通 Adreno 与 Arm Mali / Immortalis 均已适配）。

> [!IMPORTANT]
> 正式版使用银间自己的发布密钥签名。如果你装过**预览版 / 测试版**，请先卸载：Android 不允许跨签名覆盖安装。
> 卸载会清除该测试版里的本地编辑记录。

可用每个 Release 附带的 `SHA256SUMS.txt` 校验下载：

```bash
sha256sum -c SHA256SUMS.txt
```

## 原理

```
 你的照片 ─▶ 光谱上采样 ─▶ 胶片曝光 ─▶ 显影 ─▶ 放大机 ─▶ 相纸 ─▶ 扫描 ─▶ 导出
            (RGB→光谱)   (实测感光度)  (成色剂、    (CMY 滤光)  (实测染料) (到目标色彩空间)
                                       颗粒、光晕)
```

引擎是 Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 的原生 C++ 移植，
继承自 Spektrafilm for Android，并逐级与 Python 原版核对。重计算阶段经 Vulkan compute 在 GPU 上运行，
CPU 引擎作为参照。

## 还原度说明

银间以 spektrafilm 与 Silverphase v1.2.5 为基准，凡有偏离都会写明：

| 项目 | 行为 | 来源 |
|---|---|---|
| 胶片与相纸档案 | 34 份档案，原样再分发 | spektrafilm / Silverphase |
| 颗粒 | Silverphase v1.2.5 `GrainParams` | Silverphase |
| 光晕高光余量 | 静态照片默认 4 EV | Silverphase |
| 余量边缘羽化 | **银间独有。** 对余量增益的边缘做羽化，亮区自然泛光，不再出现断层。应用内帮助已标注。 | 银间 |

## 从源码构建

```bash
git clone https://github.com/zy839971925-zyy/Argentroom.git
cd Argentroom
./gradlew :app:assembleOptimized -Pspk.abis=arm64-v8a   # 日常构建，公开调试密钥
./gradlew :app:assembleRelease  -Pspk.abis=arm64-v8a    # 无 keystore.properties 时不签名
```

需要 JDK 21、Android SDK platform 37、build-tools 36.0.0、NDK 28.2.13676358 与 CMake 3.22.1。本地签名发布版，请在项目根目录创建 `keystore.properties`
（已被 git 忽略，切勿提交）：

```properties
storeFile=/absolute/path/argentroom-release.p12
storePassword=…
keyAlias=argentroom
keyPassword=…
```

更多见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 反馈问题

请提交 [Issue](../../issues/new/choose)，并附上 **设置 → 诊断与错误日志 → 导出** 的报告，
写明手机型号和出问题前做了什么。

## 致谢

银间建立在许多人的工作之上，请也去给他们点个 Star。

- **[spektrafilm](https://github.com/andreavolpato/spektrafilm)**，作者 **Andrea Volpato**：光谱胶片科学、档案与 LUT。
- **[Spektrafilm for Android](https://github.com/thetechgeekko/Spektrafilm-android)**，作者 **Akshay Sharma**：银间所基于的安卓应用与 C++/Vulkan 引擎。
- **[Silverphase](https://github.com/akinal5765-byte/Silverphase)** 及其贡献者（akinal5765-byte）：暗房界面、片边、颗粒与光晕行为，以及胶片封面（CC BY-SA 4.0）。
- **[SpektraLab](https://github.com/JamesQiu2005/SpektraLab)** 与 **[SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android)**：Vulkan 参考实现。
- **[colour-science](https://www.colour-science.org/)**、**[LibRaw](https://www.libraw.org/)**、Google **Material Symbols**，以及 **[pixls.us](https://pixls.us)** 社区。

完整声明见 [NOTICE.md](NOTICE.md) 与应用内「关于」页。

## 许可

代码：**GPL-3.0-or-later**（[LICENSE](LICENSE)）。spektrafilm 档案数据与 LUT、Silverphase 封面：**CC BY-SA 4.0**。
内置字体：SIL OFL。详见 [NOTICE.md](NOTICE.md)。

银间是**非官方衍生版**，与 spektrafilm、Spektrafilm for Android、Silverphase 的作者无隶属或背书关系。
胶片名称为其各自所有者的商标，仅用于说明模拟对象。

依据 GPLv3 §7(b)，任何基于本项目的作品都须保留以下署名：

> **Spektrafilm for Android** by **Akshay Sharma** — <https://github.com/thetechgeekko/Spektrafilm-android>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — <https://github.com/andreavolpato/spektrafilm>

<div align="center"><sub>以耐心、银盐与光写成。</sub></div>
