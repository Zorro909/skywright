import { defineConfig } from '@playwright/test';

const backendExecutable = process.env['SKYWRIGHT_BACKEND_EXECUTABLE'];
const backendTestFixture = process.env['SKYWRIGHT_BACKEND_TEST_FIXTURE'];

if (!backendExecutable || !backendTestFixture) {
  throw new Error(
    'SKYWRIGHT_BACKEND_EXECUTABLE and SKYWRIGHT_BACKEND_TEST_FIXTURE must name the packaged Spring application and its test-only fixture',
  );
}

const port = Number(process.env['SKYWRIGHT_ACCEPTANCE_PORT'] ?? '18765');

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
    command: `java -Dloader.path=${JSON.stringify(backendTestFixture)} -cp ${JSON.stringify(backendExecutable)} org.springframework.boot.loader.launch.PropertiesLauncher --skywright.deployment.environment=acceptance --skywright.deployment.reporting-currency=EUR --spring.profiles.active=target-storage-acceptance --server.port=${port}`,
    url: `http://127.0.0.1:${port}/readyz`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
