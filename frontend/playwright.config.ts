import { defineConfig } from '@playwright/test';

const backendExecutable = process.env['SKYWRIGHT_BACKEND_EXECUTABLE'];

if (!backendExecutable) {
  throw new Error(
    'SKYWRIGHT_BACKEND_EXECUTABLE must name the packaged Spring application',
  );
}

const port = 18_765;

export default defineConfig({
  testDir: './tests/acceptance',
  fullyParallel: false,
  workers: 1,
  reporter: [
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'target/test-results/acceptance.xml' }],
  ],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    browserName: 'chromium',
  },
  webServer: {
    command: `java -jar ${JSON.stringify(backendExecutable)} --skywright.deployment.environment=acceptance --server.port=${port}`,
    url: `http://127.0.0.1:${port}/readyz`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
