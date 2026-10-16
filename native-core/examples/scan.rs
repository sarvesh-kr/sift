//! Read-only scanner smoke test: cargo run --release --example scan -- <folder>
use std::{collections::BTreeMap, path::PathBuf, sync::atomic::AtomicBool};

fn main() {
    let Some(folder) = std::env::args_os().nth(1) else {
        eprintln!("Usage: cargo run --release --example scan -- <folder>");
        std::process::exit(2);
    };
    match sift_core::scan(&PathBuf::from(folder), &AtomicBool::new(false), |_| {}) {
        Ok(report) => {
            let mut categories = BTreeMap::new();
            for file in &report.files {
                let group = categories
                    .entry(file.category.as_str())
                    .or_insert((0usize, 0u64));
                group.0 += 1;
                group.1 += file.size;
            }
            println!(
                "{} files indexed in {} ms; {} skipped; truncated: {}",
                report.files.len(),
                report.elapsed_ms,
                report.skipped,
                report.truncated
            );
            for (name, (count, bytes)) in categories {
                println!("{name}: {count} files, {bytes} logical bytes");
            }
        }
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
