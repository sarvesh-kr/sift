//! Narrow binary protocol for the read-only Android helper. Tested without root.
use std::{
    fs::File,
    io::{self, Read, Seek, SeekFrom, Write},
};
pub fn serve(
    mut file: File,
    size: u64,
    device: u64,
    mut input: impl Read,
    mut output: impl Write,
) -> io::Result<()> {
    if size == 0 || size > (1u64 << 53) - 1 {
        return Err(io::Error::other("Unsupported storage size"));
    }
    output.write_all(b"SIFTRD01")?;
    output.write_all(&size.to_be_bytes())?;
    output.write_all(&device.to_be_bytes())?;
    output.flush()?;
    let mut request = [0u8; 13];
    loop {
        match input.read_exact(&mut request) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(e) => return Err(e),
        }
        let offset = u64::from_be_bytes(request[1..9].try_into().unwrap());
        let count = u32::from_be_bytes(request[9..13].try_into().unwrap()) as u64;
        if request[0] != 1
            || count > 1024 * 1024
            || offset.checked_add(count).is_none_or(|end| end > size)
        {
            return Err(io::Error::other("Invalid read-only request"));
        }
        // Devices require sector-aligned physical reads. Return only requested bytes.
        let aligned = offset / 4096 * 4096;
        let end = (offset + count).div_ceil(4096) * 4096;
        let length = (end.min(size) - aligned) as usize;
        let mut buffer = vec![0; length];
        file.seek(SeekFrom::Start(aligned))?;
        file.read_exact(&mut buffer)?;
        let start = (offset - aligned) as usize;
        output.write_all(&buffer[start..start + count as usize])?;
        output.flush()?;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn serves_only_bounded_read_ranges_and_never_modifies_source() {
        let mut source = tempfile::NamedTempFile::new().unwrap();
        let expected: Vec<_> = (0..8192).map(|i| (i % 251) as u8).collect();
        source.write_all(&expected).unwrap();
        let mut request = vec![1];
        request.extend_from_slice(&4090u64.to_be_bytes());
        request.extend_from_slice(&20u32.to_be_bytes());
        let mut output = vec![];
        serve(
            File::open(source.path()).unwrap(),
            8192,
            55,
            &request[..],
            &mut output,
        )
        .unwrap();
        assert_eq!(&output[..8], b"SIFTRD01");
        assert_eq!(&output[24..], &expected[4090..4110]);
        assert_eq!(std::fs::read(source.path()).unwrap(), expected);
        request[0] = 2;
        assert!(serve(
            File::open(source.path()).unwrap(),
            8192,
            55,
            &request[..],
            &mut vec![]
        )
        .is_err());
        request[0] = 1;
        request[1..9].copy_from_slice(&8190u64.to_be_bytes());
        assert!(serve(
            File::open(source.path()).unwrap(),
            8192,
            55,
            &request[..],
            &mut vec![]
        )
        .is_err());
    }
}
