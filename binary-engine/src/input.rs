// SPDX-License-Identifier: GPL-3.0-only
//! Positional acquisition for Binwalk's contiguous-slice parser API.
//!
//! This owns a bounded file-sized copy, not a streaming parser. File descriptors
//! are private CLOEXEC duplicates; positional reads never move the caller's seek
//! offset. Metadata checks detect ordinary concurrent changes, not adversarial
//! mutation with restored timestamps. Android's private snapshot is authoritative.
use std::fs::{File, Metadata, OpenOptions};
use std::io::ErrorKind;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::{FileExt, MetadataExt, OpenOptionsExt};

pub const MAX_BYTES: usize = 64 * 1024 * 1024;
pub const READ_BYTES: usize = 65536;

pub fn duplicate(fd: i32) -> Result<File, String> {
    // SAFETY: fcntl does not borrow memory. A successful duplicate is uniquely
    // owned by the returned File; failure never transfers the caller's fd.
    let copy = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if copy < 0 {
        return Err("Unable to duplicate granted input descriptor".into());
    }
    Ok(unsafe { File::from_raw_fd(copy) })
}

pub fn open_path(path: String) -> Result<File, String> {
    // A FIFO must not block in open() before the regular-file gate. This host
    // fixture entry point never opens a device for terminal control.
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOCTTY)
        .open(path)
        .map_err(|error| error.to_string())
}

pub struct Input {
    file: File,
    before: Metadata,
    pub bytes: Vec<u8>,
    pub read_operations: u64,
}

impl Input {
    pub fn acquire(
        file: File,
        mut check: impl FnMut() -> Result<(), String>,
    ) -> Result<Self, String> {
        check()?;
        let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
        if flags < 0 || flags & libc::O_ACCMODE == libc::O_WRONLY || flags & libc::O_PATH != 0 {
            return Err("Binwalk requires a readable data descriptor".into());
        }
        let before = file.metadata().map_err(|error| error.to_string())?;
        if !before.is_file() {
            return Err("Binwalk requires a readable regular-file descriptor".into());
        }
        if before.len() > MAX_BYTES as u64 {
            return Err("Binwalk input exceeds 64 MiB".into());
        }
        let mut bytes = Vec::new();
        bytes
            .try_reserve_exact(before.len() as usize)
            .map_err(|error| format!("Unable to allocate bounded Binwalk input copy: {error}"))?;
        bytes.resize(before.len() as usize, 0);
        let mut offset = 0;
        let mut read_operations = 0;
        while offset < bytes.len() {
            check()?;
            let end = bytes.len().min(offset + READ_BYTES);
            match file.read_at(&mut bytes[offset..end], offset as u64) {
                Ok(0) => return Err("Binwalk input ended before the captured length".into()),
                Ok(count) => {
                    offset += count;
                    read_operations += 1;
                }
                Err(error) if error.kind() == ErrorKind::Interrupted => continue,
                Err(error) => return Err(format!("Binwalk positional input read failed: {error}")),
            }
        }
        check()?;
        let input = Self {
            file,
            before,
            bytes,
            read_operations,
        };
        input.verify_identity()?;
        Ok(input)
    }

    pub fn verify_identity(&self) -> Result<(), String> {
        let after = self.file.metadata().map_err(|error| error.to_string())?;
        if self.before.dev() != after.dev()
            || self.before.ino() != after.ino()
            || self.before.len() != after.len()
            || self.before.mtime() != after.mtime()
            || self.before.mtime_nsec() != after.mtime_nsec()
            || self.before.ctime() != after.ctime()
            || self.before.ctime_nsec() != after.ctime_nsec()
        {
            return Err("Binwalk input identity/timestamps changed during analysis".into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Seek, SeekFrom, Write};
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::{Duration, SystemTime};

    struct Fixture {
        path: PathBuf,
        file: File,
    }
    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let path = std::env::temp_dir().join(format!(
                "die-binwalk-io-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .open(&path)
                .unwrap();
            Self { path, file }
        }
        fn acquire(&self) -> Result<Input, String> {
            Input::acquire(duplicate(self.file.as_raw_fd())?, || Ok(()))
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.path);
        }
    }

    #[test]
    fn position_exact_bytes_cloexec_and_independent_ownership() {
        let mut fixture = Fixture::new();
        let bytes: Vec<u8> = (0..2 * READ_BYTES + 17)
            .map(|value| (value % 251) as u8)
            .collect();
        fixture.file.write_all(&bytes).unwrap();
        fixture.file.seek(SeekFrom::Start(73)).unwrap();
        let copy = duplicate(fixture.file.as_raw_fd()).unwrap();
        assert_ne!(
            unsafe { libc::fcntl(copy.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
        let input = Input::acquire(copy, || Ok(())).unwrap();
        assert_eq!(input.bytes, bytes);
        assert_eq!(input.read_operations, 3);
        assert_eq!(fixture.file.stream_position().unwrap(), 73);
        drop(input);
        assert_eq!(fixture.file.stream_position().unwrap(), 73);
        let copy = duplicate(fixture.file.as_raw_fd()).unwrap();
        drop(fixture); // Input's owned lease survives the caller's close/unlink.
        let input = Input::acquire(copy, || Ok(())).unwrap();
        assert_eq!(input.bytes, bytes);
    }

    #[test]
    fn invalid_descriptors_regular_file_gate_and_size_bound() {
        assert!(duplicate(-1).is_err());
        let fixture = Fixture::new();
        let write_only = OpenOptions::new().write(true).open(&fixture.path).unwrap();
        assert!(Input::acquire(write_only, || Ok(())).is_err());
        let path_only = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_PATH)
            .open(&fixture.path)
            .unwrap();
        assert!(Input::acquire(path_only, || Ok(())).is_err());
        assert!(Input::acquire(File::open(std::env::temp_dir()).unwrap(), || Ok(())).is_err());
        fixture.file.set_len(MAX_BYTES as u64 + 1).unwrap();
        assert!(fixture.acquire().err().unwrap().contains("64 MiB"));
    }

    #[test]
    fn fifo_path_does_not_block_before_the_regular_file_gate() {
        let fixture = Fixture::new();
        std::fs::remove_file(&fixture.path).unwrap();
        let path = std::ffi::CString::new(fixture.path.to_str().unwrap()).unwrap();
        // SAFETY: path is a valid NUL-terminated fixture-owned pathname.
        assert_eq!(unsafe { libc::mkfifo(path.as_ptr(), 0o600) }, 0);
        let source = open_path(fixture.path.to_str().unwrap().to_owned()).unwrap();
        assert!(
            Input::acquire(source, || Ok(()))
                .err()
                .unwrap()
                .contains("regular-file")
        );
    }

    #[test]
    fn source_change_short_read_and_budget_fail_without_a_prefix() {
        let fixture = Fixture::new();
        fixture.file.set_len(2 * READ_BYTES as u64 + 1).unwrap();
        let input = fixture.acquire().unwrap();
        let times =
            std::fs::FileTimes::new().set_modified(SystemTime::now() + Duration::from_secs(60));
        fixture.file.set_times(times).unwrap();
        assert!(input.verify_identity().is_err());
        let mut calls = 0;
        let result = Input::acquire(duplicate(fixture.file.as_raw_fd()).unwrap(), || {
            calls += 1;
            if calls == 3 {
                fixture.file.set_len(1).unwrap();
            }
            Ok(())
        });
        assert!(result.err().unwrap().contains("ended before"));
        fixture.file.set_len(2 * READ_BYTES as u64 + 1).unwrap();
        calls = 0;
        let result = Input::acquire(duplicate(fixture.file.as_raw_fd()).unwrap(), || {
            calls += 1;
            if calls == 3 {
                Err("test acquisition budget".into())
            } else {
                Ok(())
            }
        });
        assert_eq!(result.err().unwrap(), "test acquisition budget");
        // Errors close only the owned duplicate; the original is still readable.
        let mut byte = [1];
        assert_eq!(fixture.file.read_at(&mut byte, 0).unwrap(), 1);
    }
}
