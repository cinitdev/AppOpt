# AppOpt v1.7.2

## 远程更新

- 新增 App 内模块更新检查, 自动读取 `/data/adb/modules/AppOpt/module.prop` 中的 `updateJson`。
- 支持解析远程 `version`、`versionCode`、`zipUrl` 和 `changelog`, 使用 `versionCode` 判断是否需要更新。
- 设置页新增独立的“远程更新”板块, 显示本地版本和云端版本, 支持自动获取和手动检查。
- App 启动后会静默检查远程更新, 发现新版本时弹出底部更新面板。
- 检测到 `/data/adb/modules_update/AppOpt/module.prop` 中已有待生效新版本时, 提示“新版本已刷入，重启后生效”。

## 下载与刷入

- 使用系统 `DownloadManager` 下载模块 zip, 更新弹窗内显示下载状态和进度。
- 下载完成后依次提示准备刷入、检测模块管理器、检测到的 Root 管理器。
- 支持自动检测并调用 Magisk、KernelSU、APatch 刷入模块。
- 新增刷写日志页面, 以流式方式追加显示模块管理器输出的刷入日志。
- 刷写过程中禁用返回, 成功后显示“重启系统”按钮; 失败时保留 zip 并提示手动刷入路径。
- 刷写成功后自动清理原始模块 zip 和 App 内临时 zip; 刷写失败时删除 App 内临时 zip, 并把原始模块转移到系统 Download 目录供手动刷入。

## App 内更新兼容

- App 内刷入模块时写入专用更新标记, 避免刷写过程中覆盖安装 App 导致闪退。
- 模块脚本识别 App 内更新标记后保留 `config/app` 内容, 重启后由 `service.sh` 自动安装/更新 App。
- 自动清理 `AppOpt_app_info.prop`、`AppOpt_app_install.prop` 等临时文件。

## 更新日志渲染

- 更新日志改用 Markwon 渲染 Markdown, 支持表格、任务列表、删除线、HTML、链接等常用语法。
- changelog 拉取失败时不阻塞下载刷入, 仅提示“更新日志读取失败，可继续下载模块”。

## 其他优化

- 优化自动校准规则写回逻辑, 同一应用/子进程规则按块替换并规整空行。
- 优化 FPS 监测在目标 PID 变化和长时间无帧时的状态处理。
- 优化悬浮球、前台检测、历史记录和设置页交互细节。
- 构建脚本支持编译并打包 App 安装辅助工具, 用于模块重启后自动安装 App。
