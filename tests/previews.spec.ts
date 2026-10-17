import { test, expect } from '@playwright/test';

async function nativeFiles(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    const root = 'C:\\Pictures';
    const calls: string[] = [];
    const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
    Object.assign(window, { isTauri: true, previewCalls: calls, __TAURI_INTERNALS__: {
      transformCallback: () => 1, unregisterCallback: () => {},
      invoke: async (command: string, args?: { path: string }) => {
        if (command === 'plugin:event|listen') return 1;
        if (command === 'plugin:event|unlisten') return;
        if (command === 'choose_folder') return root;
        if (command === 'scan_directory') return { root, files: Array.from({length: 80}, (_, i) => ({ path: `${root}\\portrait-${String(i).padStart(3,'0')}.${i === 0 ? 'html' : i === 1 ? 'dat' : 'png'}`, name: `portrait-${String(i).padStart(3,'0')}.${i === 0 ? 'html' : i === 1 ? 'dat' : 'png'}`, category: i === 0 ? 'Documents' : i === 1 ? 'Other' : 'Images', size: 10000 - i, modified: 1000 })), skipped: 0, elapsedMs: 5, cancelled: false, truncated: false };
        if (command === 'file_preview') { calls.push(args!.path); await new Promise(resolve => setTimeout(resolve, 30)); return args!.path.endsWith('html') ? { image: null, text: '<script>window.previewExecuted = true</script>\nA real local note.' } : args!.path.endsWith('dat') ? null : { image: png, text: null }; }
        throw Error(`Unexpected ${command}`);
      },
    } });
  });
  await page.goto('/');
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
  await page.getByRole('button', {name:'Scan a folder'}).click();
  await expect(page.getByText('Local scan',{exact:true})).toBeVisible();
}

test('real previews are lazy, switching layouts retains selection, and all review surfaces show content', async ({page}) => {
  await nativeFiles(page);
  await page.getByRole('button',{name:'All files',exact:true}).click();
  await expect(page.getByRole('img',{name:'Preview of portrait-002.png'})).toBeVisible();
  await expect.poll(() => page.getByRole('img',{name:'Preview of portrait-002.png'}).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
  expect(await page.evaluate(() => (window as unknown as {previewCalls:string[]}).previewCalls.some(path => path.includes('079')))).toBe(false);
  const initialReads = await page.evaluate(() => (window as unknown as {previewCalls:string[]}).previewCalls.filter(path => path.includes('002')).length);
  await page.locator('.file-name').filter({hasText:'portrait-029.png'}).scrollIntoViewIfNeeded();
  await expect(page.getByRole('img',{name:'Preview of portrait-002.png'})).toHaveCount(0);
  await page.locator('.file-name').filter({hasText:'portrait-002.png'}).scrollIntoViewIfNeeded();
  await expect(page.getByRole('img',{name:'Preview of portrait-002.png'})).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as {previewCalls:string[]}).previewCalls.filter(path => path.includes('002')).length)).toBe(initialReads);
  await page.getByRole('checkbox',{name:'Select portrait-002.png',exact:true}).check();
  await page.getByRole('button',{name:'Grid view',exact:true}).click();
  await expect(page.locator('.file-card')).toHaveCount(30);
  await expect(page.getByRole('checkbox',{name:'Select portrait-002.png',exact:true})).toBeChecked();
  await page.getByRole('textbox',{name:'Search files'}).fill('portrait-071');
  await expect(page.locator('.file-card')).toHaveCount(1);
  await expect(page.getByRole('img',{name:'Preview of portrait-071.png'})).toBeVisible();
  await page.getByRole('checkbox',{name:'Select portrait-071.png',exact:true}).check();
  await page.getByRole('button',{name:'Cleanup review',exact:false}).first().click();
  await expect(page.locator('.file-card')).toHaveCount(2);
  await page.getByRole('button',{name:'Review cleanup',exact:true}).click();
  const dialog=page.getByRole('dialog');
  await expect(dialog.getByRole('img',{name:'Preview of portrait-002.png'})).toBeVisible();
  await dialog.getByRole('button',{name:'List view',exact:true}).click();
  await expect(dialog.getByRole('img',{name:'Preview of portrait-071.png'})).toBeVisible();
  await dialog.getByRole('button',{name:'Keep portrait-071.png',exact:true}).click();
  await expect(dialog).toContainText('1 selected files');
  await page.getByRole('button',{name:'Close review'}).click();
  await page.getByRole('button',{name:'Grid view',exact:true}).click();
  await page.reload();
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
  await expect(page.getByRole('button',{name:'Grid view',exact:true})).toHaveAttribute('aria-pressed','true');
});

test('text previews remain inert and unavailable content keeps a type fallback', async ({page}) => {
  await nativeFiles(page);
  await page.getByRole('button',{name:'All files',exact:true}).click();
  await expect(page.locator('.thumbnail-text').first()).toContainText('<script>window.previewExecuted = true</script>');
  expect(await page.evaluate(() => (window as unknown as {previewExecuted?:boolean}).previewExecuted)).toBeUndefined();
  await page.getByRole('button',{name:'Grid view',exact:true}).click();
  await expect(page.locator('.file-card').filter({hasText:'portrait-001.dat'}).getByLabel('DAT file')).toBeVisible();
  await page.getByRole('button',{name:'Details for portrait-002.png',exact:true}).click();
  await expect(page.getByRole('dialog').getByRole('img',{name:'Preview of portrait-002.png'})).toBeVisible();
  await page.getByRole('button',{name:'Close file details'}).click();
  await page.setViewportSize({width:600,height:900});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('folder and duplicate views share the saved layout', async ({page}) => {
  await page.goto('/');
  await page.getByRole('button', {name:'Clean data', exact:true}).click();
  await page.getByRole('button',{name:'Grid view',exact:true}).click();
  await page.getByRole('button',{name:'Storage explorer',exact:true}).click();
  await expect(page.getByRole('button',{name:'Grid view',exact:true})).toHaveAttribute('aria-pressed','true');
  await page.getByRole('button',{name:'Downloads',exact:false}).first().click();
  await expect(page.locator('.explorer-grid .file-thumbnail').first()).toBeVisible();
  await page.getByRole('button',{name:'Duplicates',exact:false}).first().click();
  await expect(page.locator('.duplicate-layout-grid .keeper-files .file-thumbnail').first()).toBeVisible();
  await page.getByRole('button',{name:'List view',exact:true}).first().click();
  await expect(page.locator('.duplicate-layout-list')).toBeVisible();
});
