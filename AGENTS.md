# 百度离线人脸照片检测工具开发规范

本规范由 `E:/PMSYSTEM/AGENTS.md` 修订而来，适用于本目录及其子目录。

## 项目与实现依据

- 项目根目录为 `D:/m.meeting_data/a.百度人脸SDK`。首版在 Windows x64 上离线分析 JPEG 静态照片。
- [详细设计](design/百度离线人脸照片检测工具详细设计.md)定义行为、数据和接口。[开发计划](design/百度离线人脸照片检测工具开发计划.md)定义阶段范围和验收条件。[总体方案](design/百度离线人脸照片检测工具总体方案.md)提供 SDK 实测和校准依据。[交互原型](design/百度离线人脸照片检测工具交互原型.html)提供界面布局与操作路径。
- Maven 聚合工程采用 JDK 21 构建。模块编译目标和依赖版本集中在各级 `pom.xml`。复现环境、启动与验收命令见 [README](README.md)。
- 编码、异常处理、UTF-8 和测试约定见 [code_rule.md](code_rule.md)。模块接入和开发顺序见 [CLAUDE.md](CLAUDE.md)。

## 模块边界

| 模块 | 编译目标 | 范围 |
| --- | --- | --- |
| `desktop` | Java 21 | JavaFX 21、FXML、CSS、ViewModel、程序装配、进程管理和 SQLite JDBC 实现 |
| `core` | Java 21 | 扫描、批次协调、规则、查询、人工复核和 CSV |
| `detector-protocol` | Java 11 | UTF-8 JSON Lines 消息、公共编解码和故障约定 |
| `detector-baidu-windows` | Java 11 | Windows x64 检测 Worker、百度 JNI 与配套 OpenCV 3.2 |

桌面和业务模块通过协议与 Worker 通信。原生库、模型、SDK 生效配置和离线授权由 Worker 部署层装配。测试替身放在测试源码。SDK 签名和返回语义依据随包源代码、手册和目标机实测。

## 开发与交付

- 按当前阶段实现完整、可运行的改动，复用已有接口和逻辑。保留调用方依赖的行为，改动影响面以需求为准。交付列出可定位的文件及实际验证结果。
- 修改协议时同步更新协议样例和跨 JVM 测试，并核对版本兼容。跨进程请求以版本、通道、操作、请求编号及进程代次关联。
- 访问 `FaceCapture` 时保持原图只读。授权文件、设备指纹、照片内容和现场私有路径按设计保护。样例与日志采用脱敏数据。
- 用 JDK 21 执行 Maven 验收并实际启动 Java 11 x64 测试替身。真实 SDK、离线授权与生产阈值以目标机阶段验收记录为准。
