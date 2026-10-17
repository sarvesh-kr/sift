//! OS-enumerated devices only. Direct disk access still requires OS permissions.
use serde::Serialize;
use std::{
    fs::File,
    io::Read,
    os::unix::fs::MetadataExt,
    path::Path,
    process::{Command, Stdio},
};
#[derive(Clone, Serialize)]
pub struct Drive {
    pub id: String,
    pub label: String,
    pub filesystem: String,
    #[serde(skip)]
    pub size: u64,
    #[serde(skip)]
    pub family: Vec<u64>,
    #[serde(skip)]
    pub whole: String,
}
fn command(program: &str, args: &[&str]) -> Result<Vec<u8>, String> {
    let mut child = Command::new(program)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;
    let mut bytes = vec![];
    child
        .stdout
        .take()
        .ok_or("No device information")?
        .take(2 * 1024 * 1024)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    let status = child.wait().map_err(|e| e.to_string())?;
    if !status.success() || bytes.len() >= 2 * 1024 * 1024 {
        return Err("Device enumeration failed".into());
    }
    Ok(bytes)
}
#[cfg(target_os = "linux")]
pub fn drives() -> Vec<Drive> {
    fn nodes(value: &serde_json::Value, family: &mut Vec<u64>) {
        if let Some(path) = value["path"].as_str() {
            if let Ok(m) = std::fs::metadata(path) {
                family.push(m.rdev());
            }
        }
        if let Some(children) = value["children"].as_array() {
            for child in children {
                nodes(child, family);
            }
        }
    }
    fn collect(value: &serde_json::Value, family: &[u64], whole: &str, out: &mut Vec<Drive>) {
        let path = value["path"].as_str().unwrap_or("");
        let kind = value["type"].as_str().unwrap_or("");
        if path.starts_with("/dev/") && matches!(kind, "disk" | "part" | "crypt" | "lvm") {
            let fs = value["fstype"].as_str().unwrap_or("unknown");
            let size = value["size"]
                .as_u64()
                .or_else(|| value["size"].as_str().and_then(|s| s.parse().ok()))
                .unwrap_or(0);
            if size >= 4096 {
                out.push(Drive {
                    id: path.into(),
                    label: format!(
                        "{path} · {fs} · {}",
                        value["mountpoint"].as_str().unwrap_or("unmounted")
                    ),
                    filesystem: fs.into(),
                    size,
                    family: family.into(),
                    whole: whole.into(),
                });
            }
        }
        if let Some(children) = value["children"].as_array() {
            for child in children {
                collect(child, family, whole, out);
            }
        }
    }
    let mut out = vec![];
    if let Ok(bytes) = command(
        "lsblk",
        &[
            "--json",
            "--bytes",
            "--output",
            "PATH,TYPE,FSTYPE,SIZE,MOUNTPOINT",
        ],
    ) {
        if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            if let Some(roots) = value["blockdevices"].as_array() {
                for root in roots {
                    let mut family = vec![];
                    nodes(root, &mut family);
                    collect(root, &family, root["path"].as_str().unwrap_or(""), &mut out);
                }
            }
        }
    }
    out
}
#[cfg(target_os = "macos")]
pub fn drives() -> Vec<Drive> {
    fn collect(value: &plist::Value, whole: &str, out: &mut Vec<Drive>) {
        let Some(dict) = value.as_dictionary() else {
            return;
        };
        if let (Some(id), Some(size)) = (
            dict.get("DeviceIdentifier").and_then(|v| v.as_string()),
            dict.get("Size").and_then(|v| v.as_unsigned_integer()),
        ) {
            if id.starts_with("disk")
                && id
                    .bytes()
                    .all(|b| b.is_ascii_digit() || b"disk".contains(&b))
            {
                let fs = dict
                    .get("Content")
                    .and_then(|v| v.as_string())
                    .unwrap_or("unknown");
                out.push(Drive {
                    id: format!("/dev/{id}"),
                    label: format!("{id} · {fs}"),
                    filesystem: fs.into(),
                    size,
                    family: vec![],
                    whole: whole.into(),
                });
            }
        }
        if let Some(parts) = dict.get("Partitions").and_then(|v| v.as_array()) {
            for part in parts {
                collect(part, whole, out);
            }
        }
    }
    let mut out = vec![];
    if let Ok(bytes) = command("/usr/sbin/diskutil", &["list", "-plist"]) {
        if let Ok(value) = plist::Value::from_reader(std::io::Cursor::new(bytes)) {
            if let Some(roots) = value
                .as_dictionary()
                .and_then(|v| v.get("AllDisksAndPartitions"))
                .and_then(|v| v.as_array())
            {
                for root in roots {
                    let whole = root
                        .as_dictionary()
                        .and_then(|v| v.get("DeviceIdentifier"))
                        .and_then(|v| v.as_string())
                        .unwrap_or("");
                    collect(root, whole, &mut out);
                }
            }
        }
    }
    out
}
pub fn selected(id: &str) -> Result<Drive, String> {
    drives()
        .into_iter()
        .find(|d| d.id == id)
        .ok_or("Choose an available device from the storage list.".into())
}
pub fn destination_allowed(source: &Drive, path: &Path) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let device = std::fs::metadata(path).map_err(|e| e.to_string())?.dev();
        if source.family.contains(&device) {
            return Err("Choose a destination on a different physical device.".into());
        }
    }
    #[cfg(target_os = "macos")]
    {
        let bytes = command(
            "/usr/sbin/diskutil",
            &[
                "info",
                "-plist",
                path.to_str().ok_or("Unsupported destination path")?,
            ],
        )?;
        let value =
            plist::Value::from_reader(std::io::Cursor::new(bytes)).map_err(|e| e.to_string())?;
        let dict = value
            .as_dictionary()
            .ok_or("Destination device information unavailable")?;
        let external = dict.get("Internal").and_then(|v| v.as_boolean()) == Some(false);
        let whole = dict
            .get("APFSContainerReference")
            .or_else(|| dict.get("ParentWholeDisk"))
            .or_else(|| dict.get("PartOfWhole"))
            .and_then(|v| v.as_string())
            .ok_or("Cannot verify destination disk")?;
        let mut same_device = whole == source.whole;
        let is_apfs = dict
            .get("FilesystemType")
            .and_then(|v| v.as_string())
            .is_some_and(|s| s.eq_ignore_ascii_case("apfs"))
            || dict.contains_key("APFSContainerReference")
            || source.filesystem.contains("APFS");
        if is_apfs {
            let bytes = command("/usr/sbin/diskutil", &["apfs", "list", "-plist"])?;
            let apfs = plist::Value::from_reader(std::io::Cursor::new(bytes))
                .map_err(|e| e.to_string())?;
            let containers = apfs
                .as_dictionary()
                .and_then(|v| v.get("Containers"))
                .and_then(|v| v.as_array())
                .ok_or("Cannot verify APFS physical stores")?;
            let physical = |disk: &str| -> Vec<String> {
                for item in containers {
                    if let Some(container) = item.as_dictionary() {
                        if container
                            .get("ContainerReference")
                            .and_then(|v| v.as_string())
                            == Some(disk)
                        {
                            if let Some(stores) =
                                container.get("PhysicalStores").and_then(|v| v.as_array())
                            {
                                return stores
                                    .iter()
                                    .filter_map(|v| {
                                        v.as_dictionary()
                                            .and_then(|d| d.get("DeviceIdentifier"))
                                            .and_then(|v| v.as_string())
                                    })
                                    .map(|id| {
                                        let rest = &id[4.min(id.len())..];
                                        format!("disk{}", rest.split('s').next().unwrap_or(rest))
                                    })
                                    .collect();
                            }
                        }
                    }
                }
                vec![disk.to_owned()]
            };
            let from = physical(&source.whole);
            let to = physical(whole);
            if from.is_empty() || to.is_empty() {
                return Err("Cannot verify APFS source/destination devices".into());
            }
            same_device = from.iter().any(|disk| to.contains(disk));
        }
        if !external || same_device {
            return Err("For direct recovery on macOS, choose a different external physical disk as the destination.".into());
        }
    }
    Ok(())
}
pub fn open(source: &Drive) -> Result<File, String> {
    use std::os::unix::fs::{FileTypeExt, OpenOptionsExt};
    let path = std::fs::canonicalize(&source.id).map_err(|e| e.to_string())?;
    let file=std::fs::OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open(&path).map_err(|e|format!("Cannot read {}: {e}. Direct devices require disk-read permissions. Launch the recovery-only application with your system's administrator tools if needed.",source.id))?;
    let meta = file.metadata().map_err(|e| e.to_string())?;
    if !meta.file_type().is_block_device()
        && !(cfg!(target_os = "macos") && meta.file_type().is_char_device())
    {
        return Err("Source is not an OS-enumerated device".into());
    }
    Ok(file)
}
