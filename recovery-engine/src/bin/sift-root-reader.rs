//! Android root helper. It can ONLY read the mounted /data block device.
//! No write-open, arbitrary pathname, command execution, or recovery destination API.
#[cfg(target_os = "android")]
fn main() {
    if let Err(error) = run() {
        eprintln!("Read-only storage access failed: {error}");
        std::process::exit(1);
    }
}
#[cfg(not(target_os = "android"))]
fn main() {}
#[cfg(target_os = "android")]
fn run() -> std::io::Result<()> {
    use std::{
        fs::{self, OpenOptions},
        io,
        os::{
            fd::AsRawFd,
            unix::fs::{FileTypeExt, MetadataExt, OpenOptionsExt},
        },
    };
    if unsafe { libc::geteuid() } != 0 {
        return Err(io::Error::other("Root permission was not granted."));
    }
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 || args[1] != "--storage-id" {
        return Err(io::Error::other("A storage identifier is required"));
    }
    let id = &args[2];
    let card = id.len() == 9
        && id.as_bytes()[4] == b'-'
        && id
            .bytes()
            .enumerate()
            .all(|(n, b)| n == 4 || b.is_ascii_hexdigit());
    if id != "primary" && !card {
        return Err(io::Error::other("Unsupported storage identifier"));
    }
    let mounts = fs::read_to_string("/proc/mounts")?;
    let device = mounts
        .lines()
        .find_map(|line| {
            let fields: Vec<_> = line.split_whitespace().collect();
            let expected = if id == "primary" {
                fields.get(1) == Some(&"/data")
            } else {
                fields.get(1).is_some_and(|mount| {
                    *mount == format!("/mnt/media_rw/{id}") || *mount == format!("/storage/{id}")
                })
            };
            if fields.len() > 2
                && expected
                && matches!(
                    fields[2],
                    "ext4" | "f2fs" | "vfat" | "exfat" | "ntfs" | "ntfs3"
                )
            {
                Some(fields[0].to_owned())
            } else {
                None
            }
        })
        .ok_or_else(|| io::Error::other("No supported /data block-device mount found"))?;
    let path = fs::canonicalize(device)?;
    if !path.starts_with("/dev/block/") {
        return Err(io::Error::other("Unexpected userdata device"));
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(path)?;
    let stat = file.metadata()?;
    if !stat.file_type().is_block_device() {
        return Err(io::Error::other("Userdata source is not a block device"));
    }
    let mut size = 0u64;
    // SAFETY: read-only geometry ioctl with a correctly sized output buffer.
    if unsafe { libc::ioctl(file.as_raw_fd(), 0x80081272u64 as _, &mut size) } != 0 {
        return Err(io::Error::last_os_error());
    }
    sift_undelete::root_protocol::serve(
        file,
        size,
        stat.rdev(),
        io::stdin().lock(),
        io::stdout().lock(),
    )
}
