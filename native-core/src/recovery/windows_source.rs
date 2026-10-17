//! Only local, enumerated NTFS drive letters can be opened. No frontend device paths.
use serde::Serialize;
use std::{ffi::c_void, os::windows::ffi::OsStrExt, path::Path};
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetLogicalDrives() -> u32;
    fn GetDriveTypeW(root: *const u16) -> u32;
    fn GetVolumeInformationW(
        root: *const u16,
        name: *mut u16,
        name_length: u32,
        serial: *mut u32,
        max_component: *mut u32,
        flags: *mut u32,
        filesystem: *mut u16,
        filesystem_length: u32,
    ) -> i32;
    fn GetVolumePathNameW(path: *const u16, root: *mut u16, length: u32) -> i32;
    fn GetVolumeNameForVolumeMountPointW(root: *const u16, volume: *mut u16, length: u32) -> i32;
    fn DeviceIoControl(
        handle: *mut c_void,
        code: u32,
        input: *const c_void,
        input_size: u32,
        output: *mut c_void,
        output_size: u32,
        returned: *mut u32,
        overlapped: *mut c_void,
    ) -> i32;
}
#[derive(Serialize)]
pub struct Drive {
    pub id: String,
    pub label: String,
    pub filesystem: String,
}
pub fn drives() -> Vec<Drive> {
    let mut result = vec![];
    // SAFETY: no arguments; returned bit mask covers letters A through Z.
    let mask = unsafe { GetLogicalDrives() };
    for index in 0..26 {
        if mask & (1 << index) == 0 {
            continue;
        }
        let letter = (b'A' + index) as char;
        let root: Vec<u16> = format!("{letter}:\\")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut name = [0u16; 261];
        let mut filesystem = [0u16; 261];
        // SAFETY: NUL-terminated root and writable arrays of the declared lengths.
        let ok = unsafe {
            matches!(GetDriveTypeW(root.as_ptr()), 2 | 3)
                && GetVolumeInformationW(
                    root.as_ptr(),
                    name.as_mut_ptr(),
                    261,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    filesystem.as_mut_ptr(),
                    261,
                ) != 0
        };
        let text = |v: &[u16]| {
            String::from_utf16_lossy(&v[..v.iter().position(|c| *c == 0).unwrap_or(v.len())])
        };
        if ok {
            result.push(Drive {
                id: letter.to_string(),
                label: format!("{letter}:  {} · {}", text(&name), text(&filesystem)),
                filesystem: text(&filesystem),
            });
        }
    }
    result
}
pub fn length(file: &std::fs::File) -> Result<u64, String> {
    use std::os::windows::io::AsRawHandle;
    let mut size = 0u64;
    let mut returned = 0;
    // SAFETY: read-only volume geometry request with a correctly sized output.
    let ok = unsafe {
        DeviceIoControl(
            file.as_raw_handle(),
            0x7405c,
            std::ptr::null(),
            0,
            &mut size as *mut _ as _,
            8,
            &mut returned,
            std::ptr::null_mut(),
        )
    };
    if ok == 0 || returned != 8 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(size)
}
pub fn validate_drive(id: &str) -> Result<char, String> {
    if id.len() != 1
        || !id.as_bytes()[0].is_ascii_uppercase()
        || !drives().iter().any(|d| d.id == id)
    {
        return Err("Choose an available local NTFS drive from the drive list.".into());
    }
    Ok(id.chars().next().unwrap())
}
pub fn same_volume(path: &Path, drive: char) -> Result<bool, String> {
    let path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let source: Vec<u16> = format!("{drive}:\\")
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let mut root = [0u16; 32768];
    let mut dest = [0u16; 64];
    let mut origin = [0u16; 64];
    // SAFETY: valid C strings and arrays with matching lengths. Canonical volume
    // identifiers detect junctions, mount points, and alternate drive-letter aliases.
    let ok = unsafe {
        GetVolumePathNameW(path.as_ptr(), root.as_mut_ptr(), root.len() as u32) != 0
            && GetVolumeNameForVolumeMountPointW(root.as_ptr(), dest.as_mut_ptr(), 64) != 0
            && GetVolumeNameForVolumeMountPointW(source.as_ptr(), origin.as_mut_ptr(), 64) != 0
    };
    if !ok {
        return Err(format!(
            "Cannot verify destination volume: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(dest == origin)
}
