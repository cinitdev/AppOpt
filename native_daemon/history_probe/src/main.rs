fn main() {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if let Err(error) = qixia_history_probe::stream::run() {
        if error.kind() != std::io::ErrorKind::BrokenPipe {
            eprintln!("history probe: {error}");
        }
    }
}
