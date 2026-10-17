//! Read-only, bounded previews for files from the current scan.
use crate::{validate_candidate, FileEntry};
use base64::Engine;

use serde::Serialize;
use std::io::{BufReader, Cursor, Read};
use std::path::Path;

const EDGE: u32 = 384;
const MAX_INPUT: u64 = 64 * 1024 * 1024;
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FilePreview {
    pub image: Option<String>,
    pub text: Option<String>,
}
fn picture(image: image::DynamicImage) -> Result<FilePreview, String> {
    let image = image.thumbnail(EDGE, EDGE);
    let mut encoded = Cursor::new(Vec::new());
    image
        .write_to(&mut encoded, image::ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    Ok(FilePreview {
        image: Some(format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(encoded.into_inner())
        )),
        text: None,
    })
}
fn open_snapshot(path: &Path, entry: &FileEntry) -> Result<std::fs::File, String> {
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(1).custom_flags(0x00200000);
    }
    let file = options.open(path).map_err(|e| e.to_string())?;
    let metadata = file.metadata().map_err(|e| e.to_string())?;
    if !metadata.is_file()
        || metadata.len() != entry.size
        || crate::modified_nanos(&metadata) != entry.modified_nanos
        || crate::cleanup::handle_identity(&file)?.0 != entry.identity
    {
        return Err("Opened file differs from the scan.".into());
    }
    Ok(file)
}
pub fn file_preview(root: &Path, entry: &FileEntry) -> Result<Option<FilePreview>, String> {
    let path = validate_candidate(root, entry)?;
    // Never hydrate cloud placeholders or traverse Windows reparse points.
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        let attributes = std::fs::symlink_metadata(&path)
            .map_err(|e| e.to_string())?
            .file_attributes();
        if attributes & (0x400 | 0x1000 | 0x40000 | 0x400000) != 0 {
            return Ok(None);
        }
    }
    let file = open_snapshot(&path, entry)?;
    let extension = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    let result = if matches!(
        extension.as_str(),
        "jpg" | "jpeg" | "png" | "webp" | "gif" | "bmp" | "ico"
    ) && entry.size <= MAX_INPUT
    {
        let mut limits = image::Limits::default();
        limits.max_image_width = Some(32768);
        limits.max_image_height = Some(32768);
        limits.max_alloc = Some(64 * 1024 * 1024);
        let decoded = if matches!(extension.as_str(), "jpg" | "jpeg") {
            let mut decoder = jpeg_decoder::Decoder::new(BufReader::new(file));
            decoder.set_max_decoding_buffer_size(64 * 1024 * 1024);
            decoder
                .scale(EDGE as u16, EDGE as u16)
                .map_err(|e| e.to_string())?;
            let pixels = decoder.decode().map_err(|e| e.to_string())?;
            let info = decoder.info().ok_or("Missing JPEG dimensions")?;
            let mut image = match info.pixel_format {
                jpeg_decoder::PixelFormat::RGB24 => image::DynamicImage::ImageRgb8(
                    image::RgbImage::from_raw(info.width as u32, info.height as u32, pixels)
                        .ok_or("Invalid JPEG")?,
                ),
                jpeg_decoder::PixelFormat::L8 => image::DynamicImage::ImageLuma8(
                    image::GrayImage::from_raw(info.width as u32, info.height as u32, pixels)
                        .ok_or("Invalid JPEG")?,
                ),
                _ => return Ok(None),
            };
            if let Some(orientation) = decoder
                .exif_data()
                .and_then(image::metadata::Orientation::from_exif_chunk)
            {
                image.apply_orientation(orientation);
            }
            image
        } else {
            let mut reader = image::ImageReader::new(BufReader::new(file))
                .with_guessed_format()
                .map_err(|e| e.to_string())?;
            reader.limits(limits);
            reader.decode().map_err(|e| e.to_string())?
        };
        Some(picture(decoded)?)
    } else if matches!(
        extension.as_str(),
        "txt"
            | "md"
            | "csv"
            | "log"
            | "json"
            | "yaml"
            | "yml"
            | "toml"
            | "rs"
            | "ts"
            | "tsx"
            | "js"
            | "jsx"
            | "py"
            | "css"
            | "html"
            | "xml"
            | "ini"
            | "sh"
            | "sql"
    ) {
        let mut contents = Vec::new();
        file.take(4096)
            .read_to_end(&mut contents)
            .map_err(|e| e.to_string())?;
        if contents.contains(&0) {
            None
        } else {
            Some(FilePreview {
                image: None,
                text: Some(
                    String::from_utf8_lossy(&contents)
                        .chars()
                        .take(2000)
                        .collect(),
                ),
            })
        }
    } else {
        #[cfg(windows)]
        {
            if matches!(
                extension.as_str(),
                "mp4"
                    | "mov"
                    | "mkv"
                    | "webm"
                    | "avi"
                    | "m4v"
                    | "pdf"
                    | "docx"
                    | "pptx"
                    | "xlsx"
                    | "heic"
                    | "heif"
                    | "avif"
            ) {
                {
                    let result = windows_picture(&path).ok().flatten();
                    drop(file);
                    result
                }
            } else {
                None
            }
        }
        #[cfg(not(windows))]
        {
            None
        }
    };
    validate_candidate(root, entry)?;
    Ok(result)
}

#[cfg(windows)]
fn windows_picture(path: &Path) -> Result<Option<FilePreview>, String> {
    let path = path.to_path_buf();
    std::thread::Builder::new()
        .name("sift-thumbnail-sta".into())
        .spawn(move || windows_picture_sta(&path))
        .map_err(|e| e.to_string())?
        .join()
        .map_err(|_| "Windows thumbnail worker failed".to_string())?
}

#[cfg(windows)]
fn windows_picture_sta(path: &Path) -> Result<Option<FilePreview>, String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::core::PCWSTR;
    use windows::Win32::{Foundation::SIZE, Graphics::Gdi::*, System::Com::*, UI::Shell::*};
    // An isolated STA owns every Shell/GDI object; all resources are released
    // before uninitializing COM. No files are launched and no icons are executed.
    unsafe {
        CoInitializeEx(None, COINIT_APARTMENTTHREADED)
            .ok()
            .map_err(|e| e.to_string())?;
        struct Com;
        impl Drop for Com {
            fn drop(&mut self) {
                unsafe {
                    CoUninitialize();
                }
            }
        }
        let _com = Com;
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let factory: IShellItemImageFactory =
            SHCreateItemFromParsingName(PCWSTR(wide.as_ptr()), None).map_err(|e| e.to_string())?;
        let bitmap = factory
            .GetImage(
                SIZE {
                    cx: EDGE as i32,
                    cy: EDGE as i32,
                },
                SIIGBF_THUMBNAILONLY | SIIGBF_RESIZETOFIT,
            )
            .map_err(|e| e.to_string())?;
        struct Bitmap(HBITMAP);
        impl Drop for Bitmap {
            fn drop(&mut self) {
                unsafe {
                    let _ = DeleteObject(self.0.into());
                }
            }
        }
        let _bitmap = Bitmap(bitmap);
        let mut descriptor = BITMAP::default();
        if GetObjectW(
            bitmap.into(),
            std::mem::size_of::<BITMAP>() as i32,
            Some(&mut descriptor as *mut _ as _),
        ) == 0
        {
            return Ok(None);
        }
        let (width, height) = (descriptor.bmWidth, descriptor.bmHeight.abs());
        if width <= 0 || height <= 0 || width > 2048 || height > 2048 {
            return Ok(None);
        }
        let dc = CreateCompatibleDC(None);
        if dc.is_invalid() {
            return Ok(None);
        }
        struct Dc(HDC);
        impl Drop for Dc {
            fn drop(&mut self) {
                unsafe {
                    let _ = DeleteDC(self.0);
                }
            }
        }
        let _dc = Dc(dc);
        let mut info = BITMAPINFO::default();
        info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = width;
        info.bmiHeader.biHeight = -height;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB.0;
        let mut pixels = vec![0u8; width as usize * height as usize * 4];
        if GetDIBits(
            dc,
            bitmap,
            0,
            height as u32,
            Some(pixels.as_mut_ptr().cast()),
            &mut info,
            DIB_RGB_COLORS,
        ) != height
        {
            return Ok(None);
        }
        // Shell bitmaps may have undefined alpha; a thumbnail is opaque artwork.
        for pixel in pixels.chunks_exact_mut(4) {
            pixel.swap(0, 2);
            pixel[3] = 255;
        }
        let image = image::RgbaImage::from_raw(width as u32, height as u32, pixels)
            .ok_or("Invalid Shell bitmap")?;
        picture(image::DynamicImage::ImageRgba8(image)).map(Some)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::GenericImageView;
    use std::sync::atomic::AtomicBool;
    fn scan(root: &Path) -> crate::ScanReport {
        crate::scan(root, &AtomicBool::new(false), |_| {}).unwrap()
    }
    #[test]
    fn local_image_is_bounded_and_changed_files_are_refused() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("photo.png");
        image::RgbImage::from_pixel(1000, 500, image::Rgb([12, 100, 20]))
            .save(&path)
            .unwrap();
        let report = scan(temp.path());
        let entry = &report.files[0];
        let preview = file_preview(Path::new(&report.root), entry)
            .unwrap()
            .unwrap();
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(preview.image.unwrap().split_once(',').unwrap().1)
            .unwrap();
        let decoded = image::load_from_memory(&bytes).unwrap();
        assert_eq!(decoded.dimensions(), (384, 192));
        std::fs::write(path, b"changed").unwrap();
        assert!(file_preview(Path::new(&report.root), entry).is_err());
    }
    #[test]
    fn text_is_plain_bounded_and_binary_is_not_rendered() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::write(
            temp.path().join("note.html"),
            "<script>never execute</script>".repeat(1000),
        )
        .unwrap();
        std::fs::write(temp.path().join("binary.txt"), b"header\0private").unwrap();
        let report = scan(temp.path());
        for entry in &report.files {
            let preview = file_preview(Path::new(&report.root), entry).unwrap();
            if entry.name == "binary.txt" {
                assert!(preview.is_none());
            } else {
                let text = preview.unwrap().text.unwrap();
                assert!(text.starts_with("<script>"));
                assert!(text.len() <= 2000);
            }
        }
    }
    #[test]
    fn corrupt_image_is_an_error_without_changing_the_file() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("broken.png");
        std::fs::write(&path, b"not an image").unwrap();
        let report = scan(temp.path());
        assert!(file_preview(Path::new(&report.root), &report.files[0]).is_err());
        assert_eq!(std::fs::read(path).unwrap(), b"not an image");
    }
    #[test]
    fn camera_jpeg_orientation_is_preserved_after_scaled_decode() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("camera.jpg");
        let mut jpeg = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(
            800,
            400,
            image::Rgb([30, 120, 60]),
        ))
        .write_to(&mut jpeg, image::ImageFormat::Jpeg)
        .unwrap();
        let exif = b"Exif\0\0II*\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0";
        let mut tagged = vec![0xff, 0xd8, 0xff, 0xe1];
        tagged.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
        tagged.extend_from_slice(exif);
        tagged.extend_from_slice(&jpeg.into_inner()[2..]);
        std::fs::write(path, tagged).unwrap();
        let report = scan(temp.path());
        let data = file_preview(Path::new(&report.root), &report.files[0])
            .unwrap()
            .unwrap()
            .image
            .unwrap();
        let decoded = image::load_from_memory(
            &base64::engine::general_purpose::STANDARD
                .decode(data.split_once(',').unwrap().1)
                .unwrap(),
        )
        .unwrap();
        assert_eq!(decoded.dimensions(), (192, 384));
    }
    #[cfg(windows)]
    #[test]
    fn windows_shell_can_supply_a_real_bitmap_without_launching_a_file() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("shell-preview.png");
        image::RgbImage::from_pixel(400, 200, image::Rgb([25, 130, 70]))
            .save(&path)
            .unwrap();
        let preview = windows_picture(&path)
            .unwrap()
            .expect("Windows PNG thumbnail handler");
        let decoded = image::load_from_memory(
            &base64::engine::general_purpose::STANDARD
                .decode(preview.image.unwrap().split_once(',').unwrap().1)
                .unwrap(),
        )
        .unwrap();
        assert!(decoded.width() <= 384 && decoded.height() <= 384);
        assert!(path.exists());
    }
}
