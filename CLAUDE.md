# 百度离线人脸照片检测工具协作指南

本指南由 `E:/PMSYSTEM/CLAUDE.md` 修订而来。项目约束见 [AGENTS.md](AGENTS.md)，编码和验证约定见 [code_rule.md](code_rule.md)。

## 目录与启动

| 路径 | 内容 |
| --- | --- |
| `desktop/src/main` | JavaFX 入口、FXML、CSS 及后续桌面适配 |
| `core/src/main` | 应用及领域逻辑 |
| `detector-protocol/src/main` | 共用协议 |
| `detector-baidu-windows/src/main` | Windows Worker 适配 |
| `detector-baidu-windows/src/test` | Java 11 可控替身及 Java 21 父进程测试 |
| `design` | 详细设计、总体方案、开发计划、交互原型和协议样例 |
| `fixtures/jpeg` | 公开来源和人工构造的 JPEG 对照输入 |

按 [README](README.md) 设置 `JAVA_HOME` 和 `JAVA11_HOME`，在根目录运行 `mvn clean verify`。启动 JavaFX 框架前执行 `mvn install`，再运行 `mvn -pl desktop javafx:run`。测试启动的子进程须在正常结束、超时和断言失败时回收。

## 模块接入

1. 明确本次阶段及详细设计中对应的接口、状态和边界。先检查已有模块和测试数据。
2. 协议变更同步维护 `design/protocol-v1.jsonl`、协议说明和跨进程用例。保留未测量值、缺失原因、照片级和批次级错误的语义。
3. 新增业务行为时，在 `core` 落实批次协调和纯 Java 规则，由桌面装配本地存储和进程适配实现。SQLite 结构变更使用版本化迁移，按详细设计验证备份和事务。
4. JavaFX 页面使用 FXML、CSS 和 ViewModel。磁盘、数据库、解码和进程调用在后台执行。JavaFX Application Thread 只负责更新视图。
5. 真实 Worker 接入前在目标机验证 JDK、DLL、OpenCV、模型、生效配置及断网授权，保留脱敏的探针和错误码记录。按开发计划验证样本矩阵后再进入质量校准。

## 运行数据

用户可写目录承载数据库、设置和轮转日志。原图目录供只读扫描和预览。Worker 的 stdout 承载 JSON Lines，诊断走 stderr。SDK 原生日志句柄在目标机联调验证。授权文件在目标机单独部署。
