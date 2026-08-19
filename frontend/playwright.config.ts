import { defineConfig } from '@playwright/test';

const backendExecutable = process.env['SKYWRIGHT_BACKEND_EXECUTABLE'];
const backendTestClasses = process.env['SKYWRIGHT_BACKEND_TEST_CLASSES'];

if (!backendExecutable) {
  throw new Error(
    'SKYWRIGHT_BACKEND_EXECUTABLE must name the packaged Spring application',
  );
}
if (!backendTestClasses) {
  throw new Error(
    'SKYWRIGHT_BACKEND_TEST_CLASSES must name the compiled acceptance configuration',
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
    command: `java -Dloader.path=${JSON.stringify(backendTestClasses)} -cp ${JSON.stringify(backendExecutable)} org.springframework.boot.loader.launch.PropertiesLauncher --skywright.deployment.environment=acceptance --skywright.acceptance.target-storage.enabled=true --server.port=${port}`,
    url: `http://127.0.0.1:${port}/readyz`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
