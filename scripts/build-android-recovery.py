#!/usr/bin/env python3
"""Build the shared, read-only undelete engine for every APK ABI (NDK r28c)."""
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SDK = pathlib.Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
NDK = SDK / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
if not NDK.is_dir():
    sys.exit("Install NDK 28.2.13676358; the shared recovery build requires Linux.")
ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android30-clang"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi30-clang"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android30-clang"),
    "x86": ("i686-linux-android", "i686-linux-android30-clang"),
}
for abi, (target, compiler) in ABIS.items():
    env = dict(os.environ)
    env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(NDK / compiler)
    env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
    subprocess.run(["cargo", "build", "--manifest-path", str(ROOT / "recovery-engine/Cargo.toml"), "--locked", "--release", "--features", "android-jni", "--target", target], env=env, check=True)
    out = ROOT / "android/app/build/generated/recoveryJniLibs" / abi
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "recovery-engine/target" / target / "release/libsift_undelete.so", out)
    # Android extracts this packaged PIE into its executable, read-only native-lib
    # directory. It never executes code downloaded into writable app storage.
    shutil.copy2(ROOT / "recovery-engine/target" / target / "release/sift-root-reader", out / "libsift_root_reader.so")
