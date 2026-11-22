# 可行性评估 — 支持小米 CarWith / ICCOA CarLink / CarLife 作为视频源

> 2026-11-22 调研结论存档。**结论：技术上可行，推荐走 mcarplay 的 CarLife 通道桥接路线接 CarWith；本文档仅为评估，未实施。**
> 调研背景：国内用户无 Google 服务 / Android Auto 不可用，希望用国产手机协议把导航投到 MotoPlay/EasyConnect 仪表。

## 0. 为什么架构上可行

本项目管道两端解耦（见 `docs/03-PLAN-ANDROID-AUTO.md`）：

```
[视频源]                          [仪表侧]
Android Auto 接收端 (aa/)  →  VideoPipeline (H.264 转码)  →  PXC/EasyConn 协议  →  仪表
```

- `VideoPipeline.encoderInputSurface()` 是视频注入点（`VideoPipeline.kt`），接受任意外部 H.264 源。
- 仪表侧 PXC 协议已完整逆向（`docs/01-REVERSE-ENGINEERING.md`），**加新视频源不需要动任何仪表侧代码**。
- `aa/` 包（headunit-revived 移植）只是当前的视频源，可并存新增其他源。

## 1. 路线①：小米 CarWith（推荐，唯一有同机先例）

**关键先例：mcarplay**（<https://github.com/wangxso/mcarplay>，MIT，2026-10 起活跃维护）

- 已证明**在同一台小米手机上**可消费 CarWith 投屏：CarWith 底层走本地 CarLife 协议
  （七通道 + RSA/AES 握手 + H.264 视频 + 原生 PCM 音频 + 触控），经普通应用 intent + 本地
  socket 建链（CarWith 2.3.1+ 有"公开入口"；旧版本无入口，需 root 起受保护服务）。
- 输出正好是 H.264 + PCM + 触控事件 —— 与 `encoderInputSurface()` / 现有 Controls 管道
  天然对接。
- 作者实测了 50+ 组合的兼容矩阵（HyperOS OS1–OS4 × CarWith 2.x–4.x）。

**限制与风险：**
- 仅限小米/Redmi 手机（依赖 CarWith + 内置 Xiaomi CarLife 组件）。
- 版本碎片化：CarWith `2.3.1-20240712` 是第一个有持续视频流的版本；
  `4.0.20-20261009` 在部分 HyperOS 上被 APK 级 SDK 36 门槛挡住（App 无法绕过）。
- 依赖 CarWith 内部入口，HyperOS / CarWith 更新随时可能破坏（mcarplay 自述
  "unofficial experimental"）。

## 2. 路线②：ICCOA CarLink（技术可行，形态不匹配）

**先例：MiAuto**（<https://github.com/PiggyBackBayonet/MiAuto>，AGPL-3.0）— 车机端 ICCOA
CarLink 接收器，覆盖小米 CarWith / OPPO Car+ / vivo Jovi InCar，无 root。

**卡点：**
- 依赖 ICCOA 联盟官方 SDK（仓库不含，需从 release 取，许可存疑；正式接入需向联盟申请，
  面向车企/Tier 1 且有认证流程，未认证设备可能无法被手机识别）。
- ICCOA 无线建链走 Wi-Fi P2P / SoftAP 广播（MiAuto 运行在独立车机上），**同一台手机
  自连的回环场景无先例、未验证**。
- 适合"第二台设备装在车上"的产品形态，不适合本项目"手机单设备"定位。

注：CarWith 说的就是 ICCOA CarLink 协议（小米/OPPO/vivo 联合标准），无需单独实现。

## 3. 路线③：百度 CarLife（协议开放，手机侧无解）

- 车机端协议实现开源：Apollo-DuerOS `CarLife-Android-Vehicle` / `CarLife-Vehicle-Lib`
  （Apache-2.0，<https://github.com/674809/carlife> 等），协议本身（七通道、protobuf、
  H.264）没有秘密。
- 卡点在手机侧：CarLife 无线模式要求手机加入车机热点，同一台手机做不到自连；
  USB AOA 同机同样不可行。目前无任何公开项目让百度 CarLife App 投到本机 localhost。
  （V2EX mcarplay 帖下仍有人在求，尚无实现。）
- 唯一被打通的是小米手机内置 "Xiaomi CarLife" 组件（mcarplay 用的就是它）——
  这也是路线①能走通的原理。

## 4. 建议方案（未来若实施）

移植 mcarplay 的 CarLife 通道桥接作为新视频源：

```
CarWith（小米手机）
   → 本地 CarLife 七通道（intent + 本地 socket，RSA/AES）
   → H.264 + PCM + 触控/UIBC
   → VideoPipeline.encoderInputSurface()（现有转码）
   → PXC 数据通道 → 仪表
```

- 触控回传：仪表触屏 / 车把按键 → 现有 Controls 管道 → mcarplay 式触控通道。
- MVP 建议只做视频 + 音频，触控第二步；仅支持有"公开入口"的 CarWith 版本。
- 工程量量级 ≈ 当年 AA 接收端 M1–M5（见 `docs/03`）；主要风险是 CarWith 版本兼容
  矩阵维护与厂商更新破坏。
- 参考实现：mcarplay（MIT，协议桥接）、MiAuto（AGPL-3.0，仅作 ICCOA 参考）。
  注意本项目 AGPL-3.0 与 mcarplay MIT 的兼容性（MIT 可并入 AGPL，保留版权声明即可）。
