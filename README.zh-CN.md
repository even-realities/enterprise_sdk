# Even 企业原生 SDK

用于连接企业授权 Even G2 眼镜的 Android Kotlin / iOS Swift SDK，不依赖 Flutter 运行时。[English](README.md)。

## 接入

当前原生版本为 **0.1.0-beta.14**。眼镜必须使用支持企业服务的固件，并持有与完整 SN 精确匹配的签名证明。

Android 从 Maven Central 引入：

```kotlin
implementation("com.evenrealities.devices:even-devices-native-android:0.1.0-beta.14")
```

iOS 在 Xcode 添加 SPM 地址，选择 **Exact Version 0.1.0-beta.14**、产品 **EvenDevicesSDK**：

```text
https://github.com/even-realities/enterprise_sdk.git
```

接入步骤和完整接口见 [Android 文档](docs/enterprise/enterprise-android-sdk.md)、[iOS 文档](docs/enterprise/enterprise-ios-sdk.md)。Flutter 项目使用 [flutter_enterprise_sdk](https://github.com/even-realities/flutter_enterprise_sdk)，由薄插件调用本仓库的原生 SDK。

## 能力与职责

原生 SDK 负责 G2 扫描、左右腿连接、License 全量校验及 SN 匹配、8403 企业验签、8401 业务 AUTH、时间同步、Even Hub 十个指令及页面心跳、输入和 IMU 事件、麦克风 LC3 解码与 PCM 处理、双腿 OTA 和升级后版本读回。

App 只需提供签名证明及业务内容，订阅状态和结果。缺少 SN 对应证明时拒绝新连接；左右腿全部完成授权及业务 AUTH 才进入 READY；OTA 传输完成不能当作安装成功。

本版仅支持 G2，不提供 R1/DFU、手机通知编排、独立可配置 LC3 解码器或自动图片转换。签名平台私钥和账号不进入 App。

## 示例与维护

- [Android 原生示例](examples/android/README.md)
- [iOS 原生示例](examples/ios/README.md)
- [本地构建及 Maven/SPM 发布](docs/PUBLISHING.md)
- [架构与边界](docs/ARCHITECTURE.md)
- [来源记录](PROVENANCE.md)

本仓库统一维护 Kotlin、Swift、共享协议和测试。首个 beta.14 tag 沿用原发布二进制，旧 Flutter 仓库 tag/文件保持可用；Android Maven 坐标、iOS 产品名不变。

Even Hub 内容与交互设计请阅读 [官方 Get Started / Overview](https://hub.evenrealities.com/docs/get-started/overview)；网页 SDK 的 npm 接入方式与这里的原生接入方式不同。

项目采用 [Apache 2.0](LICENSE)，第三方组件保留各自许可证。构建通过不等于完成真机验收。
