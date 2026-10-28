#!/system/bin/sh
# 共用进程启动与身份检查，前台助手和刷新率服务各自独立运行。
exec sh "${0%/*}/qixia_java_service.sh" foreground "$@"
