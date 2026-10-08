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
    // Windows 宿主测试无法用 std::fs::File 对目录执行 fsync。
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
    let mut entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    for entry in entries.by_ref().take(1000).flatten() {
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
    if entries.next().is_some() {
        return Err(io::Error::other(
            "auto history directory exceeds entry limit",
        ));
    }
    Ok(())
}

#[cfg(test)]
pub(super) fn read_text(path: impl AsRef<Path>) -> io::Result<String> {
    let path = path.as_ref();
    let bytes = read_prefix(path, MAX_SOURCE_BYTES + 1)?;
    if bytes.len() as u64 > MAX_SOURCE_BYTES {
        return Err(io::Error::other("history exceeds decoded limit"));
    }
    String::from_utf8(bytes).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn directory(tag: &str) -> PathBuf {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory =
            std::env::temp_dir().join(format!("qixia-gzip-{tag}-{}-{stamp}", std::process::id()));
        fs::create_dir_all(&directory).unwrap();
        directory
    }
    fn fixture() -> String {
        format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t1000\ncore_events\t1\nD\tmodel\t中文机型\nE\t2000\t1\t2\t3\t546872656164\tassign\tqixia\t0,1,2,3\t3\t3\t12.5000\tload\nF\t2000\t60.000\t16.700\nM\t2000\t0\tcpu_usage=12.500000\tbattery_ma=1200.000000\nT\t2000\t1\t2\t3\t546872656164\t0.1000\nend_ms\t3000\nduration_ms\t2000\ncore_events_dropped\t0\n")
    }
    #[test]
    fn level_one_round_trip_preserves_every_operation_and_sample_byte() {
        let dir = directory("lossless");
        let source = dir.join("auto_1000_1_0.tmp");
        let mut raw =
            format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t1000\ncore_events\t1\n");
        for index in 0..5000 {
            let stamp = 1000 + index * 1000;
            raw.push_str(&format!("E\t{stamp}\t2000\t2001\t123456\t52656e646572546872656164\tassign\tqixia\t4,5,6,7\t7\t7\t12.5000\tload_changed\n"));
            raw.push_str(&format!("E\t{stamp}\t2000\t2001\t123456\t52656e646572546872656164\trelease\tqixia\t7\t4,5,6,7\t6\t12.5000\tthread_idle\n"));
            raw.push_str(&format!("E\t{stamp}\t2000\t2001\t123456\t52656e646572546872656164\tobserve\tsystem\t4,5,6,7\t4,5,6,7\t6\t12.5000\tobserved\n"));
            raw.push_str(&format!("F\t{stamp}\t60.000\t16.700\nM\t{stamp}\t0\tcpu_usage=12.500000\tbattery_ma=1200.000000\nT\t{stamp}\t2000\t2001\t123456\t52656e646572546872656164\t0.1000\n"));
        }
        raw.push_str("end_ms\t5001000\nduration_ms\t5000000\ncore_events_dropped\t0\n");
        fs::write(&source, raw.as_bytes()).unwrap();
        publish(&source).unwrap();
        let archive = completed_path(&source);
        assert!(!source.exists() && !incomplete_path(&source).exists());
        assert_eq!(read_text(&archive).unwrap().as_bytes(), raw.as_bytes());
        assert!(archive.metadata().unwrap().len() < raw.len() as u64 / 2);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn interrupted_gzip_is_rebuilt_from_the_complete_plaintext_rows() {
        let dir = directory("partial");
        let source = dir.join("auto_1000_1_0.tmp");
        fs::write(&source, fixture()).unwrap();
        fs::write(incomplete_path(&source), [0x1f, 0x8b, 0x08]).unwrap();
        super::super::recover(&dir).unwrap();
        let restored = read_text(completed_path(&source)).unwrap();
        assert!(restored.contains("F\t2000\t60.000\t16.700\n"));
        assert!(restored.contains("\tassign\tqixia\t"));
        assert!(restored.contains("core_events_incomplete\t1\n"));
        assert!(!source.exists() && !incomplete_path(&source).exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn crash_after_atomic_publish_removes_source_without_changing_final_archive() {
        let dir = directory("published");
        let source = dir.join("auto_1000_1_0.tmp");
        let raw = fixture();
        fs::write(&source, &raw).unwrap();
        publish(&source).unwrap();
        let archive = completed_path(&source);
        let before = fs::read(&archive).unwrap();
        // 精确重建删除源文件前崩溃所留下的状态。
        fs::write(&source, &raw).unwrap();
        super::super::recover(&dir).unwrap();
        assert!(!source.exists());
        assert_eq!(before, fs::read(&archive).unwrap());
        assert_eq!(raw, read_text(&archive).unwrap());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn failed_publication_directory_sync_never_removes_the_plaintext_source() {
        let dir = directory("sync-before");
        let source = dir.join("auto_1000_1_0.tmp");
        let raw = fixture();
        fs::write(&source, &raw).unwrap();
        publish(&source).unwrap();
        fs::write(&source, &raw).unwrap();
        for kind in [io::ErrorKind::Other, io::ErrorKind::NotFound] {
            let error = retire_source(&source, |_| {
                Err(io::Error::new(kind, "directory sync failed"))
            })
            .unwrap_err();
            assert_eq!(error.kind(), kind);
            assert_eq!(fs::read_to_string(&source).unwrap(), raw);
            assert_eq!(read_text(completed_path(&source)).unwrap(), raw);
        }
        remove_published_source(&source).unwrap();
        assert!(!source.exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn source_retirement_sync_failure_keeps_the_already_durable_archive() {
        let dir = directory("sync-after");
        let source = dir.join("auto_1000_1_0.tmp");
        let raw = fixture();
        fs::write(&source, &raw).unwrap();
        publish(&source).unwrap();
        fs::write(&source, &raw).unwrap();
        let mut barriers = 0;
        let error = retire_source(&source, |parent| {
            assert_eq!(parent, dir);
            barriers += 1;
            if barriers == 1 {
                assert!(source.exists());
                Ok(())
            } else {
                assert!(!source.exists());
                Err(io::Error::other("retirement sync failed"))
            }
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(barriers, 2);
        assert_eq!(read_text(completed_path(&source)).unwrap(), raw);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn archive_disappearing_during_publication_sync_keeps_the_original() {
        let dir = directory("sync-ack-race");
        let source = dir.join("auto_1000_1_0.tmp");
        let raw = fixture();
        fs::write(&source, &raw).unwrap();
        publish(&source).unwrap();
        fs::write(&source, &raw).unwrap();
        let archive = completed_path(&source);
        let error = retire_source(&source, |_| {
            fs::remove_file(&archive)?;
            Ok(())
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::NotFound);
        assert_eq!(fs::read_to_string(&source).unwrap(), raw);
        // 保守地重试保留的源文件，不会丢失其中的样本。
        publish(&source).unwrap();
        assert_eq!(read_text(&archive).unwrap(), raw);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn bad_crc_or_different_payload_never_discards_the_recoverable_source() {
        for corrupt in [false, true] {
            let dir = directory("invalid");
            let source = dir.join("auto_1000_1_0.tmp");
            let raw = fixture();
            fs::write(&source, &raw).unwrap();
            publish(&source).unwrap();
            let archive = completed_path(&source);
            if corrupt {
                let mut bytes = fs::read(&archive).unwrap();
                let crc = bytes.len() - 8;
                bytes[crc] ^= 0x80;
                fs::write(&archive, bytes).unwrap();
                fs::write(&source, &raw).unwrap();
            } else {
                fs::write(&source, raw.replace("60.000", "59.000")).unwrap();
            }
            assert!(remove_published_source(&source).is_err());
            assert!(source.exists() && archive.exists());
            fs::remove_dir_all(dir).unwrap();
        }
    }
    #[test]
    fn compression_failure_leaves_plaintext_and_does_not_publish_or_leave_staging() {
        let dir = directory("bounds");
        let source = dir.join("auto_1000_1_0.tmp");
        File::create(&source)
            .unwrap()
            .set_len(MAX_SOURCE_BYTES + 1)
            .unwrap();
        assert!(publish(&source).is_err());
        assert!(source.exists());
        assert!(!completed_path(&source).exists() && !incomplete_path(&source).exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    #[ignore = "Set QIXIA_HISTORY_BENCHMARK to a copied segment; run release with --nocapture"]
    fn measure_real_segment_lossless_compression() {
        let input =
            PathBuf::from(std::env::var_os("QIXIA_HISTORY_BENCHMARK").expect("sample path"));
        let raw_bytes = input.metadata().unwrap().len();
        assert!(raw_bytes <= MAX_SOURCE_BYTES);
        let dir = directory("benchmark");
        let mut times = Vec::new();
        let mut archived_bytes = 0;
        for index in 0..5 {
            let source = dir.join(format!("auto_1000_1_{index}.tmp"));
            fs::copy(&input, &source).unwrap();
            let before = Instant::now();
            publish(&source).unwrap();
            times.push(before.elapsed().as_micros());
            archived_bytes = completed_path(&source).metadata().unwrap().len();
            // 通过有界流式读取核对全部源字节与 CRC。
            fs::copy(&input, &source).unwrap();
            assert!(remove_published_source(&source).unwrap());
        }
        times.sort_unstable();
        println!("raw_bytes={raw_bytes} gzip_bytes={archived_bytes} remaining_percent={:.3} median_publish_us={} range_us={}..{} verified_runs=5",
            archived_bytes as f64 * 100.0 / raw_bytes as f64, times[2], times[0], times[4]);
        fs::remove_dir_all(dir).unwrap();
    }
}
