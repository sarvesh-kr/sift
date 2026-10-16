import { test, expect } from '@playwright/test';

test('overview, category navigation, and file search work', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your space, simplified.' })).toBeVisible();
  await expect(page.getByText('Demo preview', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /Explore Videos,/ }).click();
  await expect(page.getByRole('heading', { name: 'Everything in its place.' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(16);
  await page.getByRole('textbox', { name: 'Search files' }).fill('Portugal');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.getByText('Summer in Portugal.mp4', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('cleanup requires review, allows keeping a file, and only changes demo data', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('checkbox', { name: 'Select Summer in Portugal.mp4', exact: true }).check();
  await page.getByRole('checkbox', { name: 'Select Design archive 2024.zip', exact: true }).check();
  await page.getByRole('button', { name: 'Review cleanup' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('2 selected files')).toBeVisible();
  await dialog.getByRole('button', { name: 'Keep Design archive 2024.zip' }).click();
  await expect(dialog.getByText('1 selected files')).toBeVisible();
  await dialog.getByRole('button', { name: 'Try demo cleanup' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('status')).toContainText('No files on your computer were changed');
  await expect(page.getByRole('checkbox', { name: 'Select Summer in Portugal.mp4', exact: true })).toHaveCount(0);
  await expect(page.getByText('Design archive 2024.zip', { exact: true })).toBeVisible();
});

test('duplicates are grouped, preferences persist, and browser scan explains desktop access', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Duplicates', exact: false }).first().click();
  await expect(page.getByRole('heading', { name: 'One is enough.' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(6);
  await page.getByRole('button', { name: 'Preferences' }).click();
  await page.getByRole('checkbox', { name: /Reduce motion/ }).check();
  await page.getByRole('button', { name: 'Close preferences' }).click();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-reduced-motion', 'true');
  await page.getByRole('button', { name: 'Scan a folder' }).click();
  await expect(page.getByRole('dialog')).toContainText('browser preview');
});

test('layout remains usable at desktop and compact sizes', async ({ page }) => {
  await page.goto('/');
  await page.screenshot({ path: 'artifacts/sift-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 900, height: 800 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.setViewportSize({ width: 600, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.screenshot({ path: 'artifacts/sift-compact.png', fullPage: true });
});

test('overview select-all only selects the five displayed rows and dialogs retain keyboard focus', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('checkbox', { name: 'Select visible files' }).check();
  await expect(page.getByText('5 files selected', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Review cleanup' }).click();
  const dialog = page.getByRole('dialog');
  await page.keyboard.press('Shift+Tab');
  expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBeTruthy();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Review cleanup' })).toBeFocused();
});

test('cleanup center explains recommendations and opens their exact candidate list', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Cleanup center', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Make room, with a reason.' })).toBeVisible();
  const card = page.locator('.insight-card').filter({ has: page.getByRole('heading', { name: 'Older installers', exact: true }) });
  await expect(card).toContainText('Age does not prove it was installed');
  await card.getByRole('button', { name: 'Inspect these files' }).click();
  await expect(page.locator('.insight-detail-banner')).toContainText('What removal changes');
  await expect(page.locator('tbody tr')).not.toHaveCount(0);
  await expect(page.locator('tbody')).toContainText('Applications');
  await page.screenshot({ path: 'artifacts/sift-insights.png', fullPage: true });
});

test('folder explorer drills into folders and scopes the file list', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Storage explorer', exact: true }).click();
  await page.getByRole('button', { name: /^Downloads \d+ files in this branch/ }).click();
  await expect(page.locator('.explorer-breadcrumbs')).toContainText('Downloads');
  await expect(page.locator('.direct-file-grid')).toContainText('Design archive 2024.zip');
  await page.getByRole('button', { name: 'See all files here' }).click();
  await expect(page.locator('.folder-scope')).toContainText('/Users/alex/Downloads');
  await expect(page.getByText('Summer in Portugal.mp4', { exact: true })).toHaveCount(0);
});

test('duplicate review keeps one copy, updates chosen keeper, and blocks selecting every copy', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Duplicates', exact: false }).first().click();
  const group = page.locator('.duplicate-group').first();
  await group.getByRole('button', { name: 'Select extra copies' }).click();
  await expect(page.getByText('2 files selected', { exact: true })).toBeVisible();
  await group.getByRole('radio').nth(1).check();
  await expect(page.getByText('2 files selected', { exact: true })).toBeVisible();
  await page.getByRole('checkbox', { name: 'Select visible files' }).check();
  await page.getByRole('button', { name: 'Review cleanup' }).click();
  await expect(page.getByRole('alert')).toContainText('Every copy is selected');
  await expect(page.getByRole('button', { name: 'Try demo cleanup' })).toBeDisabled();
});

test('advanced screens stay within compact layouts', async ({ page }) => {
  await page.goto('/');
  await page.setViewportSize({ width: 600, height: 900 });
  for (const label of ['Cleanup center', 'Storage explorer', 'Duplicates']) {
    await page.getByRole('button', { name: label, exact: label !== 'Duplicates' }).first().click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  }
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.getByRole('button', { name: 'Cleanup center', exact: true }).click();
  await page.screenshot({ path: 'artifacts/sift-cleanup-center.png', fullPage: true });
});
