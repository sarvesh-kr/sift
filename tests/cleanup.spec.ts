import { test, expect } from '@playwright/test';

for (const allFail of [false, true]) {
  test(`native cleanup preserves individual errors and ${allFail ? 'zero-success' : 'partial-success'} selections`, async ({ page }) => {
    const root = 'C:\\Users\\Alex\\Documents';
    const paths = [`${root}\\locked.txt`, `${root}\\okay.txt`];
    await page.addInitScript(({ root, paths, allFail }) => {
      const calls: string[][] = [];
      Object.assign(window, {
        isTauri: true,
        siftCleanupCalls: calls,
        __TAURI_INTERNALS__: {
          transformCallback: () => 1,
          unregisterCallback: () => {},
          invoke: async (command: string, args?: { paths: string[] }) => {
            if (command === 'plugin:event|listen') return 1;
            if (command === 'plugin:event|unlisten') return;
            if (command === 'choose_folder') return root;
            if (command === 'scan_directory') return { root, files: paths.map((path, index) => ({ path, name: index ? 'okay.txt' : 'locked.txt', size: 100 - index, category: 'Documents', modified: Date.now() })), skipped: 0, truncated: false, cancelled: false, elapsedMs: 10, volume: null };
            if (command === 'trash_files') {
              calls.push(args!.paths);
              if (calls.length === 1) return { removed: allFail ? [] : [paths[1]], failed: [{ path: paths[0], error: 'The file is open in another app. Close it and try again. Windows code 0x80070020.' }, ...(allFail ? [{ path: paths[1], error: 'Windows denied access. Windows code 0x80070005.' }] : [])], volume: null };
              return { removed: args!.paths, failed: [], volume: null };
            }
            throw new Error(`Unexpected command: ${command}`);
          },
        },
      });
    }, { root, paths, allFail });
    await page.goto('/');
    await page.getByRole('button', { name: 'Scan a folder' }).click();
    await expect(page.getByText('Local scan', { exact: true })).toBeVisible();
    await page.getByRole('checkbox', { name: 'Select visible files' }).check();
    await page.getByRole('button', { name: 'Review cleanup' }).click();
    await page.getByRole('button', { name: 'Move to trash', exact: true }).click();
    const results = page.getByRole('region', { name: 'Cleanup results' });
    await expect(results.getByRole('heading')).toHaveText(`${allFail ? 0 : 1} ${allFail ? 'files' : 'file'} moved to trash`);
    await expect(results).toContainText(paths[0]);
    await expect(results).toContainText('0x80070020');
    if (allFail) await expect(results).toContainText('0x80070005');
    await page.getByRole('button', { name: 'Dismiss notification' }).click();
    await expect(results).toBeVisible();
    await expect(page.getByText(`${allFail ? 2 : 1} files selected`, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Review cleanup' }).click();
    await page.getByRole('button', { name: 'Move to trash', exact: true }).click();
    await expect(results).toContainText('Cleanup completed.');
    expect(await page.evaluate(() => (window as unknown as { siftCleanupCalls: string[][] }).siftCleanupCalls)).toEqual([paths, allFail ? paths : [paths[0]]]);
  });
}
