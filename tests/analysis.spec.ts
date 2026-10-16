import { test, expect } from '@playwright/test';
import { cleanupInsights, duplicateGroups, folderContents, inFolder, lastCopyConflicts, removeEntries, suggestedKeeper } from '../src/analysis';
import type { FileEntry, ScanReport } from '../src/model';

const file = (path: string, size = 2e9, category: FileEntry['category'] = 'Documents', days = 200): FileEntry => ({ path, name: path.split(/[\\/]/).at(-1)!, size, category, modified: Date.now() - days * 86400000 });
const report = (files: FileEntry[]): ScanReport => ({ root: '/data', files, skipped: 0, truncated: false, cancelled: false, elapsedMs: 10 });

test('folder analysis uses path boundaries and handles Windows separators', () => {
  const entries = [file('/data/photos/a.jpg', 10), file('/data/photos/trips/b.jpg', 20), file('/database/private.txt', 99), file('/data/readme.txt', 5)];
  const scope = folderContents(entries, '/data');
  expect(scope.size).toBe(35);
  expect(scope.count).toBe(3);
  expect(scope.folders).toEqual([{ name: 'photos', path: '/data/photos', size: 30, count: 2 }]);
  expect(folderContents(entries, '/data/photos').files).toHaveLength(1);
  expect(inFolder('C:\\Users\\Alex\\Downloads\\x.zip', 'C:\\Users\\Alex')).toBe(true);
  expect(inFolder('/database/x', '/data')).toBe(false);
});

test('cleanup explanations exclude credentials and unknown ages and keep backups as personal-file reviews', () => {
  const entries = [file('/data/Downloads/old.exe', 100, 'Applications'), file('/data/Downloads/new.exe', 100, 'Applications', 5), file('/data/Downloads/password.key'), file('/data/.git/Downloads/private.zip', 2e9, 'Archives'), file('/data/backups/only-backup.zip', 2e9, 'Archives'), { ...file('/data/Downloads/unknown.exe', 100, 'Applications'), modified: 0 }];
  const insights = cleanupInsights(report(entries));
  expect(insights.find(insight => insight.id === 'old-installers')?.files.map(file => file.name)).toEqual(['old.exe']);
  expect(insights.flatMap(insight => insight.files).some(file => file.name === 'password.key' || file.path.includes('.git'))).toBe(false);
  expect(insights.find(insight => insight.id === 'old-archives')?.risk).toBe('Personal files');
  expect(insights.find(insight => insight.id === 'old-archives')?.consequence).toContain('only backup');
});

test('duplicate selection preserves a keeper and clears singleton match markers after removal', () => {
  const entries = [{ ...file('/data/report.pdf'), duplicateGroup: 'match' }, { ...file('/data/report copy.pdf'), duplicateGroup: 'match' }];
  const groups = duplicateGroups(entries);
  expect(suggestedKeeper(groups[0]).name).toBe('report.pdf');
  expect(lastCopyConflicts(groups, new Set(entries.map(file => file.path)))).toHaveLength(1);
  const remainder = removeEntries(entries, new Set(['/data/report copy.pdf']));
  expect(remainder).toHaveLength(1);
  expect(remainder[0].duplicateGroup).toBeNull();
});
