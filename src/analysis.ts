import type { FileEntry, ScanReport } from './model';

export type InsightId = 'large-media' | 'old-installers' | 'old-archives' | 'downloads' | 'build-output';
export type Insight = { id: InsightId; title: string; reason: string; consequence: string; evidence: string; risk: 'Personal files' | 'App impact' | 'Review needed'; files: FileEntry[]; size: number };
export type DuplicateStats = { candidateFiles: number; sampledFiles: number; hashedFiles: number; verifiedPairs: number; bytesRead: number; elapsedMs: number };
export const normalizePath = (path: string) => path.replace(/\\/g, '/').replace(/\/$/, '');
export function inFolder(path: string, folder: string) { return normalizePath(path).startsWith(normalizePath(folder) + '/'); }

export function duplicateGroups(files: FileEntry[]): FileEntry[][] {
  const groups = new Map<string, FileEntry[]>();
  for (const file of files) if (file.duplicateGroup) {
    const group = groups.get(file.duplicateGroup);
    if (group) group.push(file); else groups.set(file.duplicateGroup, [file]);
  }
  return [...groups.values()].filter(group => group.length > 1).sort((a, b) => b[0].size * (b.length - 1) - a[0].size * (a.length - 1));
}
export function suggestedKeeper(group: FileEntry[]): FileEntry {
  const penalty = (file: FileEntry) => (/\bcopy\b|\(\d+\)/i.test(file.name) ? 10000 : 0) + file.path.length;
  return [...group].sort((a, b) => penalty(a) - penalty(b) || a.path.localeCompare(b.path))[0];
}
export function lastCopyConflicts(groups: FileEntry[][], selected: Set<string>): FileEntry[][] {
  return groups.filter(group => group.every(file => selected.has(file.path)));
}
export function removeEntries(files: FileEntry[], removed: Set<string>): FileEntry[] {
  const next = files.filter(file => !removed.has(file.path));
  const groups = new Set(duplicateGroups(next).map(group => group[0].duplicateGroup));
  return next.map(file => file.duplicateGroup && !groups.has(file.duplicateGroup) ? { ...file, duplicateGroup: null } : file);
}

export function cleanupInsights(report: ScanReport, now = Date.now()): Insight[] {
  const buckets: Record<InsightId, FileEntry[]> = { 'large-media': [], 'old-installers': [], 'old-archives': [], downloads: [], 'build-output': [] };
  for (const file of report.files) {
    const path = normalizePath(file.path);
    // Suggestions never classify credentials, source-control data, or backups as disposable.
    if (/(^|\/)\.git(\/|$)|(^|\/)\.env($|\.)|\.(pem|key|kdbx)$|(^|\/)(id_rsa|id_ed25519|wallet\.dat)$/i.test(path)) continue;
    const days = file.modified > 0 ? Math.max(0, (now - file.modified) / 86400000) : 0;
    if (file.category === 'Videos' && file.size >= 1e9) buckets['large-media'].push(file);
    if (file.category === 'Applications' && /\.(exe|msi|dmg|pkg|deb|rpm|appimage)$/i.test(file.name) && days >= 180) buckets['old-installers'].push(file);
    if (file.category === 'Archives' && file.size >= 100e6 && days >= 180) buckets['old-archives'].push(file);
    if (/(^|\/)downloads\//i.test(path) && days >= 90 && file.size > 0) buckets.downloads.push(file);
    if (/(^|\/)(__pycache__|\.parcel-cache)(\/|$)|(^|\/)(\.next|node_modules)\/\.?(cache)(\/|$)|(^|\/)target\/debug\//i.test(path) && days >= 30 && file.size > 0) buckets['build-output'].push(file);
  }
  const rules: Omit<Insight, 'files' | 'size'>[] = [
    { id: 'old-installers', title: 'Older installers', reason: 'Install packages unchanged for at least 180 days.', evidence: 'Installer extension + modification date', consequence: 'You may need the package again to reinstall an app. Age does not prove it was installed.', risk: 'Review needed' },
    { id: 'build-output', title: 'Build output & cache locations', reason: 'Files in recognized build/cache locations, unchanged for 30+ days.', evidence: 'Known path pattern + modification date', consequence: 'Close related tools first. Removal can trigger a rebuild or lose generated output; these paths do not prove a file is disposable.', risk: 'App impact' },
    { id: 'old-archives', title: 'Archives worth revisiting', reason: 'Archives over 100 MB, unchanged for at least 180 days.', evidence: 'Archive extension + size + modification date', consequence: 'An archive may be your only backup. Inspect its contents and keep an independent copy of anything important.', risk: 'Personal files' },
    { id: 'downloads', title: 'Downloads from another season', reason: 'Files in a Downloads folder, unchanged for 90+ days.', evidence: 'Downloads path + modification date', consequence: 'Downloads can contain irreplaceable documents. Review each file; modification age is not last-use age.', risk: 'Personal files' },
    { id: 'large-media', title: 'Your largest videos', reason: 'Individual video files at least 1 GB in size.', evidence: 'Video extension + logical file size', consequence: 'These are personal media, not junk. Move valuable originals to another disk rather than deleting them.', risk: 'Personal files' },
  ];
  return rules.map(rule => ({ ...rule, files: buckets[rule.id].sort((a, b) => b.size - a.size), size: buckets[rule.id].reduce((sum, file) => sum + file.size, 0) })).filter(rule => rule.files.length > 0);
}

export function folderContents(files: FileEntry[], folder: string) {
  const root = normalizePath(folder);
  const folders = new Map<string, { name: string; path: string; size: number; count: number }>();
  const direct: FileEntry[] = [];
  let total = 0, count = 0;
  for (const file of files) {
    const path = normalizePath(file.path);
    if (!path.startsWith(root + '/')) continue;
    total += file.size; count++;
    const relative = path.slice(root.length + 1);
    const slash = relative.indexOf('/');
    if (slash < 0) { direct.push(file); continue; }
    const name = relative.slice(0, slash);
    const group = folders.get(name);
    if (group) { group.size += file.size; group.count++; }
    else folders.set(name, { name, path: root + '/' + name, size: file.size, count: 1 });
  }
  return { folders: [...folders.values()].sort((a, b) => b.size - a.size), files: direct.sort((a, b) => b.size - a.size), size: total, count };
}
