# 程序图标

- 通用图标来源：`D:/Owner/识微/ico/app-cinnabar.ico`，原文件保持不变。
- `app-icon.png`：使用内置 imagegen 制作的 BD 应用图标，右下角深灰斜带、白色 BD 字样。
- `app-icon.ico`：Windows EXE 和 MSI 使用，包含 16、20、24、32、40、48、64、128、256 像素的 32 位图标。
- JavaFX 使用 `desktop/src/main/resources/cn/zhixing/facephoto/desktop/app-icon.png`。

在项目根目录执行 `./packaging/Convert-AppIcon.ps1`，从本目录 PNG 同步生成 ICO 和 JavaFX 的 256 像素 PNG。构建不依赖外部素材目录。

生成提示词：

> Preserve the original cinnabar rounded square, ivory abstract logo and small sparkle. Add a straight dark charcoal diagonal ribbon across the lower-right corner, rising at 45 degrees, with white bold uppercase “BD” centered and rotated along the ribbon. Keep the main logo mostly unobscured, outside corners transparent; output one Windows application icon.

## 本次验证

2026-11-18，产品版本 `0.2.2026111800004`（MSI 版本 `0.2.4`）：

- JDK 21 执行 `Build-WindowsPackage.ps1 -Offline` 中的 Maven `clean verify`，26 项测试全部通过，包含实际启动 Java 11 替身和 JavaFX 界面的测试。
- 使用 `Build-WindowsPackage.ps1 -Offline -SkipMaven` 完成 EXE 和 MSI 打包，替换项目固定 `release` 目录。
- 校验 ICO 包含九档尺寸，从成品 EXE 提取图标并目视确认深色斜带及 BD 字样。
- 实际启动发布 EXE，确认应用子进程的窗口标题包含新版本号，随后正常关闭。jpackage 父启动器无窗口，启动验证需检查应用子进程。
