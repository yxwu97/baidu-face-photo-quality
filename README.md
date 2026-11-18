# 百度离线人脸照片检测工具

Windows x64 离线 JPEG 人脸检测工具，产品版本 **0.2.2026111800003**。

已实现批次、SQLite、质量规则、JavaFX 主窗口和四个弹窗、人工复核、CSV 与 Windows 打包。当前电脑已使用百度补发的序列号完成 SDK 联网激活。真实 SDK 处理现场单日 264 张照片，119 张合格、134 张不合格、11 张待复核，检测失败 0 张。现场阈值校准与正式断网验收待完成。

- [开发计划](design/百度离线人脸照片检测工具开发计划.md)
- [自动接续验收记录](design/自动接续开发验收记录.md)
- [测试输入](design/测试输入清单.md)
- [200 张校准候选清单](design/calibration-seed.csv)

## 构建

环境为 Temurin JDK 21.0.10+7 x64、Java 11.0.28+6 x64 和 Maven 3.9.14。工程用 JDK 21 构建，协议与 Worker 的字节码目标为 Java 11。JavaFX 21.0.10、SQLite JDBC 3.51.3.0 与 Jackson 2.21.4 已锁定。

项目根目录执行以下命令。

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
$env:JAVA11_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-11.0.28.6-hotspot'
$faceMavenRepo = "-Dmaven.repo.local=$((Get-Location).Path)/.local/maven-repository"
mvn -o -B -ntp $faceMavenRepo '-Djavafx.cachedir=.local/fx-cache' clean verify
~~~

首次构建需要下载依赖，移除 -o 可联网获取。测试会实际启动 Java 11 替身与 JavaFX 窗口。

## 开发运行

~~~powershell
./packaging/Stage-WindowsWorker.ps1 -Java11Home $env:JAVA11_HOME -LicenseDirectory '.local/activation/active-license' -Offline
mvn -o -B -ntp $faceMavenRepo install
mvn $faceMavenRepo -pl desktop javafx:run
~~~

开发 Worker 授权放在 .local/worker/sdk/license。装配脚本从 SDK 源码重编译 Java 包，从 -LicenseDirectory 复制本机已通过验证的 license.ini 和 license.key。当前验证使用 .local/activation/active-license，原始 License 和 License2 两个目录保留供历史排查。

## Windows 开发包

使用 WiX 3 的 candle.exe 与 light.exe 所在目录生成应用镜像及 MSI。

~~~powershell
./packaging/Build-WindowsPackage.ps1 -Java11Home $env:JAVA11_HOME -Offline -WixBin '.local/build-tools/wix314'
~~~

成功后替换项目固定 release 目录，失败保留已有交付。仅构建应用镜像使用 -ImageOnly。-SkipMaven 适用于已完成同版本验证后的装配。

双击 release/BaiduFacePhotoQuality/BaiduFacePhotoQuality.exe 启动程序。选择抓拍目录并开始检测。选择已有批次的目录会恢复历史，缺少引擎时仍可查看、复核和导出已有结果。默认数据、缓存与轮转日志存放于 %LOCALAPPDATA%/BaiduFacePhotoQuality。

打包默认保留固定 release 目录中的本机授权；首次打包从项目 License 目录装配。可通过 -LicenseDirectory 指定新版授权目录，缺少文件或空文件时中止打包。授权会进入本机 MSI，请仅用于对应设备部署。程序附带独立 Java 11 Worker 运行时和桌面运行时。生产交付须完成授权、现场校准及正式断网验收。

## 校准

design/calibration-seed.csv 包含 200 张候选种子的相对路径、SHA-256、组别及校准/验证分区。相近连续抓拍按五分钟组进入同一分区。人工标注字段留空，需现场填写并补入已确认正常抓拍。初始光照规则保持关闭，待目标机实测确认量程与方向后启用。

