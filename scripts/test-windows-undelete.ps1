$ErrorActionPreference = 'Stop'
# Only a new, uniquely named disposable VHD is ever formatted or modified.
$image = Join-Path $env:RUNNER_TEMP ("sift-undelete-" + [guid]::NewGuid() + ".vhd")
$script = Join-Path $env:RUNNER_TEMP ("sift-diskpart-" + [guid]::NewGuid() + ".txt")
$output = Join-Path $env:RUNNER_TEMP ("sift-recovered-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $output | Out-Null
@("create vdisk file=`"$image`" maximum=64 type=expandable", "select vdisk file=`"$image`"", 'attach vdisk') | Set-Content $script
try {
    & diskpart /s $script
    if ($LASTEXITCODE -ne 0 -or !(Test-Path $image)) { throw 'Disposable VHD creation failed' }
    $disk = Get-DiskImage -ImagePath $image | Get-Disk
    if (!$disk -or $disk.IsBoot -or $disk.IsSystem -or $disk.Size -gt 100MB) { throw 'Refusing an unexpected disk' }
    $disk | Initialize-Disk -PartitionStyle MBR -PassThru | New-Partition -UseMaximumSize -AssignDriveLetter | Format-Volume -FileSystem NTFS -NewFileSystemLabel 'SIFT_UNDELETE_TEST' -Confirm:$false | Out-Null
    $partition = $disk | Get-Partition | Where-Object DriveLetter
    $letter = [string]$partition.DriveLetter
    if ($letter.Length -ne 1) { throw 'No test drive letter' }
    $root = "$letter`:\"
    $folder = Join-Path $root 'Sift recovery fixture'
    New-Item -ItemType Directory $folder | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $folder 'sift-permanently-deleted.txt'), [Text.Encoding]::UTF8.GetBytes("Permanently deleted from an NTFS volume; no recycle bin.`n"))
    $payload = New-Object byte[] 131072
    for ($i=0; $i -lt $payload.Length; $i++) { $payload[$i]=($i*17+31)%251 }
    [IO.File]::WriteAllBytes((Join-Path $folder 'sift-deleted-payload.bin'),$payload)
    [IO.File]::WriteAllText((Join-Path $folder 'sift-live-keep.txt'),'This file must not appear as deleted.')
    # Persist known original bytes before deleting. Otherwise an immediately deleted
    # dirty cache page may never have reached this raw test volume at all.
    foreach ($name in @('sift-permanently-deleted.txt','sift-deleted-payload.bin')) {
        $handle = [IO.File]::Open((Join-Path $folder $name),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
        try { $handle.Flush($true) } finally { $handle.Dispose() }
    }
    # DeleteFile bypasses Recycle Bin. The disposable VHD has no TRIM-backed SSD.
    [IO.File]::Delete((Join-Path $folder 'sift-permanently-deleted.txt'))
    [IO.File]::Delete((Join-Path $folder 'sift-deleted-payload.bin'))
    cargo run --manifest-path native-core/Cargo.toml --locked --example recovery-drive-check -- $letter $output
    if ($LASTEXITCODE -ne 0) { throw 'Direct-drive undelete checks failed' }
} finally {
    if (Test-Path $image) { Dismount-DiskImage -ImagePath $image -ErrorAction Continue | Out-Null }
    Remove-Item $script -ErrorAction SilentlyContinue
    Remove-Item $image -ErrorAction SilentlyContinue
}
