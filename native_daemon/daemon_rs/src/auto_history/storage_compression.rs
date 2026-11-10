//! 只压缩已封存分段。gzip 内仍是未经修改的 v1 TSV 数据，
//! 保留全部核心操作与观测；采样、事件去重和读取上限
//! 都独立于这一存储容器。
use super::*;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use std::io::BufReader;

// 在有界采样数据之后追加尾部和入选标记。
const MAX_SOURCE_BYTES: u64 = MAX_BYTES + 4096;
const BUFFER_BYTES: usize = 64 * 1024;

pub(super) fn completed_path(source: &Path) -> PathBuf {
    source.with_extension("log.gz")
}

fn incomplete_path(source: &Path) -> PathBuf {
    source.with_extension("gz.part")
}

/// 仅用于读取短小的保留策略头部。完整归档读取方必须读至 EOF，
/// 以校验 gzip 尾部的 CRC 和解压长度。
pub(super) fn read_prefix(path: &Path, maximum: u64) -> io::Result<Vec<u8>> {
    let file = File::open(path)?;
    let mut bytes = Vec::new();
    if path
        .file_name()
        .is_some_and(|name| name.to_string_lossy().ends_with(".log.gz"))
    {
        GzDecoder::new(BufReader::with_capacity(BUFFER_BYTES, file))
            .take(maximum)
            .read_to_end(&mut bytes)?;
    } else {
        file.take(maximum).read_to_end(&mut bytes)?;
    }
    Ok(bytes)
}

fn unlink_if_present(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

fn sync_directory(directory: &Path) -> io::Result<()> {
    // 在 Android/Linux 上持久化目录项。与草稿发布相同，
    // Windows 无法用 std::fs::File 对目录执行 fsync。
    #[cfg(unix)]
    File::open(directory)?.sync_all()?;
    #[cfg(not(unix))]
    let _ = directory;
    Ok(())
}

fn retire_source(
    source: &Path,
    mut synchronize: impl FnMut(&Path) -> io::Result<()>,
) -> io::Result<()> {
    let directory = source
        .parent()
        .ok_or_else(|| io::Error::other("auto history source has no parent directory"))?;
    // 在已发布 gzip 的目录项持久化前，源文件必须保持可恢复。
    // 尤其不能将目录缺失视为 App 已确认导入。
    synchronize(directory)?;
    let completed = fs::symlink_metadata(completed_path(source))?;
    if !completed.is_file() || completed.file_type().is_symlink() {
        return Err(io::Error::other(
            "auto history archive disappeared before source retirement",
        ));
    }
    // App 可能在上述检查前移走已导入的归档；此时应保留源文件并重试，
    // 不能推测数据已经安全保存。
    unlink_if_present(source)?;
    // 此处失败仍需报告，但已同步的 gzip 依然持久有效。
    // 崩溃后要么能恢复 gzip，要么能同时恢复 gzip 与源文件。
    synchronize(directory)
}

/// 发布可能在崩溃前刚刚成功。仅在有界逐字节比较和 gzip CRC 校验通过后，
/// 才允许移除其明文源文件。
pub(super) fn remove_published_source(source: &Path) -> io::Result<bool> {
    let completed = completed_path(source);
    match fs::symlink_metadata(&completed) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error),
        Ok(metadata) if !metadata.is_file() || metadata.file_type().is_symlink() => {
            return Err(io::Error::other(
                "auto history archive is not a regular file",
            ));
        }
        Ok(_) => {}
    }
    let expected = fs::metadata(source)?.len();
    if expected > MAX_SOURCE_BYTES {
        return Err(io::Error::other(
            "auto history source exceeds segment limit",
        ));
    }
    let compressed = match File::open(&completed) {
        Ok(file) => file,
        // App 可能在读取元数据与打开文件之间确认并移走已发布文件。
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error),
    };
    let mut decoded = GzDecoder::new(BufReader::with_capacity(BUFFER_BYTES, compressed));
    let mut input = BufReader::with_capacity(BUFFER_BYTES, File::open(source)?);
    let mut raw = [0u8; BUFFER_BYTES];
    let mut unpacked = [0u8; BUFFER_BYTES];
    let mut count = 0u64;
    loop {
        let length = input.read(&mut raw)?;
        if length == 0 {
            break;
        }
        count += length as u64;
        if count > MAX_SOURCE_BYTES {
            return Err(io::Error::other(
                "auto history source grew during publication",
            ));
        }
        decoded.read_exact(&mut unpacked[..length])?;
        if raw[..length] != unpacked[..length] {
            return Err(io::Error::other(
                "auto history archive differs from retained source",
            ));
        }
    }
    // 读取到 EOF 同时校验 CRC 和长度；更长的归档不对应当前源文件。
    let mut extra = [0u8; 1];
    if decoded.read(&mut extra)? != 0 {
        return Err(io::Error::other(
            "auto history archive contains extra payload",
        ));
    }
    drop(input);
    drop(decoded);
    retire_source(source, sync_directory)?;
    Ok(true)
}

pub(super) fn publish(source: &Path) -> io::Result<()> {
    if !owned_file(source, "tmp") {
        return Err(io::Error::other(
            "auto history source is not a closed segment",
        ));
    }
    if remove_published_source(source)? {
        return Ok(());
    }
    let partial = incomplete_path(source);
    // 此工作线程是唯一发布方。不能跟随或覆盖
    // 固定暂存路径上的外部符号链接。
    if partial.exists() {
        if !owned_file(&partial, "gz.part") {
            return Err(io::Error::other(
                "auto history gzip staging path is not a regular file",
            ));
        }
        unlink_if_present(&partial)?;
    }
    let result = (|| -> io::Result<()> {
        let input = File::open(source)?;
        if input.metadata()?.len() > MAX_SOURCE_BYTES {
            return Err(io::Error::other(
                "auto history source exceeds segment limit",
            ));
        }
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&partial)?;
        let output = BufWriter::with_capacity(BUFFER_BYTES, file);
        // 记录线程已降低优先级，使用 1 级压缩进一步限制压缩耗时；
        // 亲和性和 FPS 都不等待这些文件操作。
        let mut encoder = GzEncoder::new(output, Compression::fast());
        let mut source_reader = input.take(MAX_SOURCE_BYTES + 1);
        let mut buffer = [0u8; BUFFER_BYTES];
        let mut written = 0u64;
        loop {
            let length = source_reader.read(&mut buffer)?;
            if length == 0 {
                break;
            }
            written += length as u64;
            if written > MAX_SOURCE_BYTES {
                return Err(io::Error::other(
                    "auto history source grew during compression",
                ));
            }
            encoder.write_all(&buffer[..length])?;
        }
        let mut output = encoder.finish()?;
        output.flush()?;
        output.get_ref().sync_data()?;
        drop(output);
        drop(source_reader);
        fs::rename(&partial, completed_path(source))?;
        retire_source(source, sync_directory)
    })();
    if result.is_err() {
        let _ = unlink_if_present(&partial);
    }
    result
}

/// 只有遗弃的 gzip 工作文件可直接删除。对应的 .tmp 才是可信源，
/// 仍由现有入选检查和恢复流程验证。
pub(super) fn remove_abandoned_parts(directory: &Path) -> io::Result<()> {
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !owned_file(&path, "gz.part") {
            continue;
        }
        let stem = path.file_name().unwrap().to_string_lossy();
        let source = path.with_file_name(format!("{}.tmp", stem.strip_suffix(".gz.part").unwrap()));
        if !is_open(&source) {
            unlink_if_present(&path)?;
        }
    }
    Ok(())
}
