export type Category = 'Videos' | 'Images' | 'Documents' | 'Audio' | 'Archives' | 'Applications' | 'Code' | 'Other';
export type FileEntry = { path: string; name: string; size: number; modified: number; category: Category; duplicateGroup?: string | null };
export type VolumeSpace = { totalBytes: number; availableBytes: number };
export type ScanReport = { root: string; files: FileEntry[]; skipped: number; truncated: boolean; elapsedMs: number; cancelled: boolean; volume?: VolumeSpace | null };
export type Progress = { files: number; bytes: number; phase: string };
export const categories: Category[] = ['Videos', 'Images', 'Documents', 'Audio', 'Archives', 'Applications', 'Code', 'Other'];
export const colors: Record<Category, string> = { Videos: '#5d8b76', Images: '#91bba4', Documents: '#b7cdb2', Audio: '#d6bc91', Archives: '#a6b5c9', Applications: '#b8a7c8', Code: '#8db9bc', Other: '#d8dfdb' };
export function bytes(n: number, decimals = 1): string {
  if (!n) return '0 B';
  const unit = Math.min(Math.floor(Math.log(n) / Math.log(1000)), 4);
  return `${(n / 1000 ** unit).toLocaleString('en-US', { maximumFractionDigits: decimals })} ${['B', 'KB', 'MB', 'GB', 'TB'][unit]}`;
}
export function relativeDate(value: number): string {
  if (!value) return 'Unknown';
  const days = Math.max(0, Math.floor((Date.now() - value) / 86400000));
  if (!days) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 30) return `${days} days ago`;
  return new Date(value).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}
const GB = 1e9;
const sample: [string, number, Category, number, string?][] = [
  ['Summer in Portugal.mp4', 8.42, 'Videos', 47], ['Design archive 2024.zip', 5.18, 'Archives', 493],
  ['Product launch — final.mov', 4.76, 'Videos', 12], ['Photo library backup.zip', 3.24, 'Archives', 210],
  ['Weekend in the mountains.mp4', 2.87, 'Videos', 95], ['Studio session.wav', 1.62, 'Audio', 38],
  ['Figma-setup.exe', 0.31, 'Applications', 231, 'demo-installer'], ['Figma-setup (1).exe', 0.31, 'Applications', 231, 'demo-installer'],
  ['Figma-setup (2).exe', 0.31, 'Applications', 231, 'demo-installer'], ['Brand guidelines.pdf', 0.084, 'Documents', 24, 'demo-brand'],
  ['Brand guidelines copy.pdf', 0.084, 'Documents', 24, 'demo-brand'], ['Brand guidelines final.pdf', 0.084, 'Documents', 24, 'demo-brand'],
  ['Project proposal.docx', 0.018, 'Documents', 3], ['Portfolio source.zip', 0.78, 'Archives', 150],
  ['macOS-wallpaper.jpg', 0.036, 'Images', 43], ['Read me.txt', 0.00002, 'Documents', 1],
];
export function demoReport(): ScanReport {
  const files: FileEntry[] = sample.map(([name, size, category, age, duplicateGroup]) => ({ name, size: Math.round(size * GB), category, modified: Date.now() - age * 86400000, path: `/Users/alex/${category === 'Images' ? 'Pictures' : category === 'Videos' ? 'Movies' : 'Downloads'}/${name}`, duplicateGroup }));
  for (let i = 0; i < 104; i++) {
    const category = categories[i % categories.length];
    const extension = { Videos: 'mp4', Images: 'jpg', Documents: 'pdf', Audio: 'mp3', Archives: 'zip', Applications: 'msi', Code: 'ts', Other: 'dat' }[category];
    const prefix = { Videos: 'Travel diary', Images: 'DSC', Documents: 'Project notes', Audio: 'Sunday playlist', Archives: 'Project backup', Applications: 'App installer', Code: 'workspace', Other: 'Local data' }[category];
    const size = category === 'Videos' ? (0.65 + (i % 8) * 0.12) * GB : category === 'Images' ? (0.13 + (i % 8) * 0.034) * GB : (0.025 + (i % 9) * 0.012) * GB;
    files.push({ name: `${prefix} ${String(i + 1).padStart(3, '0')}.${extension}`, path: `/Users/alex/Workspace/${category}/${prefix} ${i + 1}.${extension}`, size: Math.round(size), category, modified: Date.now() - (i * 11 % 730) * 86400000 });
  }
  return { root: '/Users/alex', files, skipped: 0, truncated: false, elapsedMs: 1240, cancelled: false, volume: { totalBytes: 1e12, availableBytes: 680e9 } };
}
