import { test, expect } from '@playwright/test';

test('downloads resolve to six release assets without a browser API request', async ({ page, request }) => {
  const external: string[] = [];
  page.on('request', req => { if (!req.url().startsWith('http://127.0.0.1:4173')) external.push(req.url()); });
  await page.goto('/');
  await expect(page).toHaveTitle('Sift — Your space, simplified.');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('A little clarity.A lot more room.');
  const release = await (await request.get('/release.json')).json();
  const pairs = [['windows-setup', 'WINDOWS_SETUP_URL'], ['windows-portable', 'WINDOWS_PORTABLE_URL'], ['mac-arm', 'MAC_ARM_URL'], ['linux-appimage', 'LINUX_APPIMAGE_URL'], ['linux-deb', 'LINUX_DEB_URL']];
  for (const [asset, key] of pairs) await expect(page.locator(`[data-asset="${asset}"]`)).toHaveAttribute('href', release[key]);
  await page.getByRole('button', { name: 'Intel', exact: true }).click();
  await expect(page.locator('#mac-download')).toHaveAttribute('href', release.MAC_INTEL_URL);
  await expect(page.locator('#mac-meta')).toContainText('Intel processor');
  await expect(page.locator('#mac-alternate')).toHaveAttribute('href', release.MAC_ARM_URL);
  await page.getByRole('button', { name: 'Apple Silicon', exact: true }).click();
  await expect(page.locator('#mac-download')).toHaveAttribute('href', release.MAC_ARM_URL);
  expect(external).toEqual([]);
});

test('feature previews support keyboard navigation and images load', async ({ page }) => {
  await page.goto('/');
  const first = page.getByRole('tab', { name: /The bigger picture/ });
  await first.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', { name: /A smarter starting point/ })).toBeFocused();
  await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'tab-cleanup');
  await expect(page.locator('#preview-image')).toHaveAttribute('src', 'assets/cleanup.png');
  await page.keyboard.press('End');
  await expect(page.locator('#preview-title')).toHaveText('Keep the memories. Lose the copies.');
  await page.locator('#preview-image').scrollIntoViewIfNeeded();
  await expect.poll(() => page.locator('#preview-image').evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
  await page.getByText('What happens when I clean up files?', { exact: true }).click();
  await expect(page.locator('details[open]')).toContainText('operating system’s trash');
});

test('mobile layout keeps all platform downloads accessible without horizontal overflow', async ({ page }) => {
  for (const width of [360, 390, 768]) {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/');
    await expect(page.getByRole('link', { name: 'Download Sift', exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Download Sift', exact: true }).click();
    await expect(page).toHaveURL(/#download$/);
    const release = await (await page.request.get('/release.json')).json();
    await expect(page.locator('.download-card:visible')).toHaveCount(release.ANDROID_HIDDEN ? 3 : 4);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const image of await page.locator('img').all()) {
      await image.scrollIntoViewIfNeeded();
      await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
    }
  }
});

test('essential download links and installation help work without JavaScript', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4173/');
  await expect(page.locator('#mac-download')).toHaveAttribute('href', /apple-silicon\.dmg$/);
  await expect(page.locator('#mac-alternate')).toHaveAttribute('href', /macos-intel\.dmg$/);
  await expect(page.locator('[data-asset="windows-setup"]')).toHaveAttribute('href', /setup\.exe$/);
  await page.getByText('Why might my computer show a security warning?', { exact: true }).click();
  await expect(page.locator('details[open]')).toContainText('not notarized');
  await context.close();
});


test('Android phones get an APK recommendation and usable install instructions', async ({ browser }) => {
  const context = await browser.newContext({ userAgent: 'Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36', viewport: {width: 390, height: 844} });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4173/');
  const release = await (await page.request.get('/release.json')).json();
  if (!release.ANDROID_HIDDEN) {
    await expect(page.locator('[data-asset="android-apk"]')).toHaveAttribute('href', release.ANDROID_URL);
    await expect(page.locator('#download-android')).toHaveClass(/is-recommended/);
    await expect(page.locator('#download-linux')).not.toHaveClass(/is-recommended/);
  }
  await page.getByText('How do I install Sift on Android?', {exact: true}).click();
  await expect(page.locator('details[open]')).toContainText('All files access');
  await expect(page.locator('details[open]')).toContainText('Uninstalling Sift deletes its trash');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await context.close();
});

test('workspaces explain recovery scope and Android permission limits without promising universal undelete',async({page})=>{
  await page.goto('/');const workspaces=page.getByRole('region',{name:/Make room\.\s*Bring something back\./});
  await expect(workspaces).toContainText('Clean data or Recover data');await expect(workspaces).toContainText('512 MiB per candidate');
  await workspaces.getByRole('img',{name:/Sift’s start screen/}).scrollIntoViewIfNeeded();await expect.poll(()=>workspaces.getByRole('img',{name:/Sift’s start screen/}).evaluate((img:HTMLImageElement)=>img.complete&&img.naturalWidth>0)).toBe(true);
  await page.getByText('Can Sift recover permanently deleted files?',{exact:true}).click();await expect(page.locator('details[open]')).toContainText('No universal recovery');await expect(page.locator('details[open]')).toContainText('Android’s encrypted internal storage');
});
