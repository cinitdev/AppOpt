use std::{env, io};

// 生命周期脚本传入已打开的锁文件，不依赖 ROM 或 Root 管理器提供的命令。
// 0 表示取得锁，75 仅表示被其他进程占用，其他错误必须立即停止服务变更。
pub(super) fn run() -> i32 {
    let mut args = env::args().skip(2);
    let fd = args.next().and_then(|value| value.parse::<libc::c_int>().ok());
    let Some(fd) = fd.filter(|fd| *fd >= 3) else {
        eprintln!("文件锁参数无效：需要继承的文件描述符（至少为 3）");
        return 2;
    };
    if args.next().is_some() {
        eprintln!("文件锁参数过多：用法为 --try-lock-fd <文件描述符>");
        return 2;
    }

    loop {
        // 不重新打开文件，也不主动解锁。父 Shell 仍持有同一打开文件描述，
        // 此短命进程退出后锁继续有效，直到父 Shell 关闭句柄或退出。
        if unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) } == 0 {
            return 0;
        }
        let error = io::Error::last_os_error();
        match error.kind() {
            io::ErrorKind::Interrupted => continue,
            io::ErrorKind::WouldBlock => return 75,
            _ => {
                eprintln!("文件锁操作失败（fd={fd}）：{error}");
                return 1;
            }
        }
    }
}
