import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './website-tests',
  use: { baseURL: 'http://127.0.0.1:4173', viewport: { width: 1440, height: 1000 }, headless: true },
  webServer: { command: 'python3 -m http.server 4173 --bind 127.0.0.1 --directory site-dist', url: 'http://127.0.0.1:4173', reuseExistingServer: !process.env.CI },
  reporter: 'list',
});
