import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 2,
  timeout: 45000,
  expect: { timeout: 7000 },
  reporter: 'list',
  use: {
    baseURL: process.env.DRAFT_TEST_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 1000 },
        channel: 'msedge',
      },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'], channel: 'msedge' } },
  ],
})
