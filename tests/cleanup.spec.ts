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
            if (command === 'cleanup_files') {
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
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
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

for (const mode of ['delete', 'wipe'] as const) {
  test(`${mode} needs the exact count, passes only reviewed paths, and requires rescan after a failed wipe`, async ({ page }) => {
    await page.addInitScript(({ mode }) => {
      const root = 'C:\\SiftFixtures';
      const paths = ['one.txt', 'two.txt'].map(name => `${root}\\${name}`);
      Object.assign(window, { isTauri: true, siftCleanupCalls: [], __TAURI_INTERNALS__: {
        transformCallback: () => 1, unregisterCallback: () => {},
        invoke: async (command: string, args: unknown) => {
          if (command === 'plugin:event|listen') return 1;
          if (command === 'plugin:event|unlisten') return;
          if (command === 'choose_folder') return root;
          if (command === 'scan_directory') return { root, files: paths.map((path, i) => ({ path, name: i ? 'two.txt' : 'one.txt', size: 100-i, category: 'Documents', modified: 0 })), skipped: 0, truncated: false, cancelled: false, elapsedMs: 1, volume: null };
          if (command === 'cleanup_files') {
            (window as unknown as {siftCleanupCalls: unknown[]}).siftCleanupCalls.push(args);
            return { mode, removed: mode === 'delete' ? [paths[0]] : [], failed: mode === 'wipe' ? [{path: paths[0], error: 'Overwrite interrupted after 20 bytes written.'}] : [], rescanRequired: mode === 'wipe', volume: null };
          }
          throw new Error(command);
        },
      }});
    }, { mode });
    await page.goto('/');
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
    await page.getByRole('button', {name: 'Scan a folder'}).click();
    await page.getByRole('checkbox', {name: 'Select visible files'}).check();
    await page.getByRole('button', {name: 'Review cleanup'}).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('radio', {name: /^Recycle Bin/})).toBeChecked();
    await dialog.getByRole('radio', {name: mode === 'delete' ? /^Delete permanently/ : /^Overwrite & delete/}).check();
    const action = dialog.getByRole('button', {name: mode === 'delete' ? 'Delete permanently' : 'Overwrite & delete', exact: true});
    await expect(action).toBeDisabled();
    await dialog.getByRole('textbox', {name: 'Typed cleanup confirmation'}).fill(`${mode.toUpperCase()} 2 FILES`);
    await expect(action).toBeEnabled();
    await dialog.getByRole('button', {name: 'Keep two.txt'}).click();
    await expect(dialog.getByRole('textbox', {name: 'Typed cleanup confirmation'})).toHaveValue('');
    await expect(action).toBeDisabled();
    await dialog.getByRole('textbox', {name: 'Typed cleanup confirmation'}).fill(`${mode.toUpperCase()} 1 FILE`);
    await action.click();
    expect(await page.evaluate(() => (window as unknown as {siftCleanupCalls: unknown[]}).siftCleanupCalls)).toEqual([{ paths: ['C:\\SiftFixtures\\one.txt'], mode, confirmation: `${mode.toUpperCase()} 1 FILE`, allowAllCopies: false }]);
    const results = page.getByRole('region', {name: 'Cleanup results'});
    await expect(results).toContainText(mode === 'delete' ? '1 file permanently deleted' : '0 files overwritten and deleted');
    if (mode === 'wipe') {
      await expect(results).toContainText('partially or fully overwritten');
      await page.getByRole('button', {name: 'Dismiss cleanup results'}).click();
      await expect(page.getByRole('button', {name: 'Review cleanup'})).toBeDisabled();
      await page.getByRole('button', {name: 'Scan a folder'}).click();
      await page.getByRole('checkbox', {name: 'Select visible files'}).check();
      await expect(page.getByRole('button', {name: 'Review cleanup'})).toBeEnabled();
    }
  });
}

test('all matches includes unloaded rows, respects search, and single-file cleanup replaces bulk selection', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
  await page.getByRole('button', {name: 'All files', exact: true}).click();
  const count = Number((await page.locator('.bulk-selection').innerText()).match(/Add all ([\d,]+)/)![1].replaceAll(',', ''));
  expect(count).toBeGreaterThan(30);
  await expect(page.locator('tbody tr')).toHaveCount(30);
  await page.getByRole('button', {name: `Add all ${count} matches to selection`}).click();
  await expect(page.getByText(`${count} files selected`, {exact: true})).toBeVisible();
  await page.getByRole('button', {name: 'Clear', exact: true}).click();
  await page.getByRole('textbox', {name: 'Search files'}).fill('Portugal');
  await page.getByRole('button', {name: 'Add all 1 matches to selection'}).click();
  await expect(page.getByText('1 files selected', {exact: true})).toBeVisible();
  await page.getByRole('textbox', {name: 'Search files'}).fill('');
  await page.getByRole('button', {name: 'Details for Design archive 2024.zip'}).click();
  await page.getByRole('button', {name: 'Clean up this file only'}).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('1 selected files', {exact: true})).toBeVisible();
  await expect(dialog.getByRole('button', {name: 'Keep Design archive 2024.zip'})).toBeVisible();
  await expect(dialog.getByRole('button', {name: 'Keep Summer in Portugal.mp4'})).toHaveCount(0);
  await dialog.getByRole('radio', {name: /^Delete permanently/}).check();
  await dialog.getByRole('textbox', {name: 'Typed cleanup confirmation'}).fill('DELETE 1 FILE');
  await dialog.getByRole('button', {name: 'Close review'}).click();
  await page.getByRole('button', {name: 'Review cleanup'}).click();
  await expect(page.getByRole('radio', {name: /^Recycle Bin/})).toBeChecked();
  await expect(page.getByRole('textbox', {name: 'Typed cleanup confirmation'})).toHaveCount(0);
});

test('removing all duplicate copies needs a separate acknowledgment and typed confirmation', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
  await page.getByRole('button', {name: 'Duplicates', exact: true}).click();
  await page.getByRole('button', {name: /^Add all \d+ matches/}).click();
  await page.getByRole('button', {name: 'Review cleanup'}).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', {name: 'Try demo cleanup'})).toBeDisabled();
  await dialog.getByRole('radio', {name: /^Delete permanently/}).check();
  const input = dialog.getByRole('textbox', {name: 'Typed cleanup confirmation'});
  await input.fill((await input.getAttribute('placeholder'))!);
  await expect(dialog.getByRole('button', {name: 'Try demo cleanup'})).toBeDisabled();
  await dialog.getByRole('checkbox', {name: /^Allow removal of every selected copy/}).check();
  await expect(dialog.getByRole('button', {name: 'Try demo cleanup'})).toBeEnabled();
  await dialog.getByRole('radio', {name: /^Recycle Bin/}).check();
  await expect(dialog.getByRole('button', {name: 'Try demo cleanup'})).toBeDisabled();
});
