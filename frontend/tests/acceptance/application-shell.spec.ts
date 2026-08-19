import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const targetStorageEndpoint = process.env['SKYWRIGHT_ACCEPTANCE_S3_ENDPOINT'];

if (!targetStorageEndpoint) {
  throw new Error('SKYWRIGHT_ACCEPTANCE_S3_ENDPOINT must be configured');
}

test('user can navigate the packaged Skywright shell', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('banner')).toBeVisible();
  const navigation = page.getByRole('navigation', { name: 'Primary' });
  await expect(navigation).toBeVisible();
  await expect(navigation.getByRole('link')).toHaveText([
    'Overview',
    'Target Storages',
    'About',
  ]);
  await expect(page.getByRole('main')).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Skywright' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Overview' }),
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'Overview', exact: true }),
  ).toHaveAttribute('aria-current', 'page');

  const systemInformationResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/system-information') &&
      response.request().method() === 'GET',
  );
  await page.getByRole('link', { name: 'About' }).click();
  const systemInformation = await (await systemInformationResponse).json();
  await expect(page).toHaveURL(/\/about$/u);
  await expect(page.getByRole('link', { name: 'About' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(
    page.getByRole('heading', { level: 2, name: 'About Skywright' }),
  ).toBeVisible();
  await expect(
    page.getByText(/portable contract for machine-learning training/u),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', {
      level: 3,
      name: 'System Information',
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByText(`API version ${systemInformation.apiVersion}`),
  ).toBeVisible();
  await expect(
    page.getByText(
      `Application version ${systemInformation.applicationVersion}`,
    ),
  ).toBeVisible();
  if (systemInformation.sourceRevision) {
    await expect(
      page.getByText(`Source revision ${systemInformation.sourceRevision}`),
    ).toBeVisible();
  }

  for (const path of ['/', '/target-storages', '/about', '/missing']) {
    await page.goto(path);
    const accessibility = await new AxeBuilder({ page }).analyze();
    expect(accessibility.violations).toEqual([]);
  }
});

test('keyboard users can bypass navigation and open a destination', async ({
  page,
}) => {
  await page.goto('/');

  const skipLink = page.getByRole('link', { name: 'Skip to main content' });
  await page.keyboard.press('Tab');
  await expect(skipLink).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();

  await page.goto('/');
  for (let tab = 0; tab < 5; tab += 1) {
    await page.keyboard.press('Tab');
  }
  const aboutLink = page.getByRole('link', { name: 'About' });
  await expect(aboutLink).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/about$/u);
});

test('operator can manage a Target Storage through the packaged control plane', async ({
  page,
}) => {
  await page.goto('/target-storages');
  await expect(
    page.getByRole('heading', { level: 2, name: 'Target Storages' }),
  ).toBeVisible();

  await page.getByText('Register Target Storage').click();
  await expect(
    page.getByText(
      'Binding UUIDs only. Credential values are never accepted here.',
    ),
  ).toBeVisible();

  const registration = page.locator('form').first();
  await registration.getByLabel('Name').fill('Acceptance outputs');
  await registration.getByLabel('Endpoint URL').fill(targetStorageEndpoint);
  await registration.getByLabel('Bucket').fill('acceptance-outputs');
  await registration
    .getByLabel('Training Process binding UUID')
    .fill('65d81b25-ac05-455a-85ae-de56024348e2');
  await registration
    .getByLabel('Backend binding UUID')
    .fill('fd049266-8da9-44ea-9021-9556907a2a96');
  await registration
    .getByLabel('Transfer Worker binding UUID')
    .fill('b99fce1b-c4c6-47d7-93f3-161f46f66c39');
  await registration
    .getByLabel('Metric View binding UUID')
    .fill('2ed9cde4-4fbf-4ce9-aa86-1f7d6f9188bf');
  const creation = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/target-storages') &&
      response.request().method() === 'POST',
  );
  await registration
    .getByRole('button', { name: 'Register candidate' })
    .click();
  const registered = await (await creation).json();

  const storage = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: 'Acceptance outputs' }),
  });
  await expect(storage).toBeVisible();
  await expect(storage.getByText('Revision 1', { exact: true })).toBeVisible();
  await expect(storage.getByText('available').first()).toBeVisible();
  await expect(storage.getByText('ready').first()).toBeVisible();

  await registration.getByLabel('Name').fill('Acceptance outputs');
  await registration.getByLabel('Endpoint URL').fill(targetStorageEndpoint);
  await registration.getByLabel('Bucket').fill('acceptance-outputs');
  await registration
    .getByRole('button', { name: 'Register candidate' })
    .click();
  await expect(
    page.getByText('The Target Storage operation could not be completed.'),
  ).toBeVisible();
  await expect(registration.getByLabel('Name')).toHaveValue(
    'Acceptance outputs',
  );

  await storage
    .getByRole('button', { name: 'Qualify current revision' })
    .click();
  await expect(storage.getByText('Qualification history')).toBeVisible();
  await expect(storage.getByText('available', { exact: true })).toHaveCount(2);

  await storage.getByText('Stage revised configuration').click();
  const revision = storage
    .locator('details')
    .filter({ hasText: 'Stage revised configuration' })
    .locator('form');
  await revision.getByLabel('Endpoint URL').fill('http://127.0.0.1:18765');
  const failedRevisionResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/target-storages/${registered.id}/revisions`) &&
      response.request().method() === 'POST',
  );
  await revision.getByRole('button', { name: 'Stage revision' }).click();
  const failedRevision = await (await failedRevisionResponse).json();
  await expect(storage.getByText('Revision 2', { exact: true })).toBeVisible();
  await expect(storage.getByText('incompatible')).toBeVisible();
  await expect(storage.getByText('capability-failed').first()).toBeVisible();

  const staleMutation = await page.request.put(
    `/api/v1/target-storages/${registered.id}/activation`,
    {
      data: {
        expectedRegistrationRevision: failedRevision.registrationRevision,
        activated: false,
      },
    },
  );
  expect(staleMutation.ok()).toBe(true);
  await revision.getByLabel('Endpoint URL').fill(targetStorageEndpoint);
  await revision.getByRole('button', { name: 'Stage revision' }).click();
  await expect(
    page.getByText('The Target Storage operation could not be completed.'),
  ).toBeVisible();

  await page.reload();
  await storage.getByText('Stage revised configuration').click();
  await revision.getByLabel('Endpoint URL').fill(targetStorageEndpoint);
  await revision.getByRole('button', { name: 'Stage revision' }).click();
  await expect(storage.getByText('Revision 3', { exact: true })).toBeVisible();

  const activationResponse = page.waitForResponse(
    (response) =>
      response
        .url()
        .endsWith(`/api/v1/target-storages/${registered.id}/activation`) &&
      response.request().method() === 'PUT',
  );
  await storage.getByRole('button', { name: 'Activate' }).click();
  const activationBody = await (await activationResponse).json();
  expect(activationBody).toMatchObject({ activated: true, eligible: true });
  await expect(storage.getByText('Eligible', { exact: true })).toBeVisible();

  const localDefaults = page.locator('form').filter({
    has: page.getByRole('heading', { name: 'local-single-gpu' }),
  });
  await localDefaults
    .getByLabel('Execution destination')
    .selectOption(registered.id);
  await localDefaults.getByLabel('Enable repatriation').check();
  await localDefaults
    .getByLabel('Repatriation destination')
    .selectOption(registered.id);
  const defaultsResponse = page.waitForResponse(
    (response) =>
      response
        .url()
        .endsWith('/api/v1/target-storage-defaults/local-single-gpu') &&
      response.request().method() === 'PUT',
  );
  await localDefaults.getByRole('button', { name: 'Save defaults' }).click();
  expect((await defaultsResponse).ok()).toBe(true);
  await expect(localDefaults.getByLabel('Enable repatriation')).toBeChecked();

  await storage.getByRole('button', { name: 'Deactivate' }).click();
  await expect(
    storage.getByText('Not eligible', { exact: true }),
  ).toBeVisible();

  await storage.getByRole('button', { name: 'Delete' }).click();
  await expect(
    page.getByText('The Target Storage could not be deleted.'),
  ).toBeVisible();
  await expect(storage).toBeVisible();

  const sensitiveNames = await page
    .locator('input')
    .evaluateAll((inputs) =>
      inputs
        .map((input) => input.getAttribute('name') ?? '')
        .filter((name) =>
          /secret|password|accessKey|credentialValue/iu.test(name),
        ),
    );
  expect(sensitiveNames).toEqual([]);
});

test('backend loss degrades and recovers only System Information', async ({
  page,
}) => {
  await page.goto('/');
  await page.route('**/api/v1/system-information', async (route) => {
    await route.abort('connectionrefused');
  });

  await page.getByRole('link', { name: 'About' }).click();
  await expect(
    page.getByRole('heading', { name: 'System Information unavailable' }),
  ).toBeVisible();
  await expect(
    page.getByText('The server could not be reached.'),
  ).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
  await expect(
    page.getByRole('heading', {
      level: 3,
      name: 'System Information',
      exact: true,
    }),
  ).toBeFocused();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);

  await page.getByRole('link', { name: 'Overview', exact: true }).click();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Overview' }),
  ).toBeVisible();
  await page.getByRole('link', { name: 'About' }).click();
  await expect(
    page.getByRole('heading', { name: 'System Information unavailable' }),
  ).toBeVisible();

  await page.unroute('**/api/v1/system-information');
  await page.getByRole('button', { name: 'Retry' }).click();

  await expect(page.getByText(/API version \S+/u)).toBeVisible();
  await expect(page.getByText(/Application version \S+/u)).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 3, name: 'System Information' }),
  ).toBeFocused();
});

test('direct application routes boot without rewriting reserved backend URLs', async ({
  page,
  request,
}) => {
  await page.goto('/about');
  await expect(
    page.getByRole('heading', { level: 2, name: 'About Skywright' }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('heading', { level: 2, name: 'About Skywright' }),
  ).toBeVisible();

  await page.goto('/missing');
  await expect(
    page.getByRole('heading', { level: 2, name: 'Page not found' }),
  ).toBeVisible();

  for (const path of [
    '/api/v1/not-an-operation',
    '/openapi/not-a-contract',
    '/livez/not-an-endpoint',
    '/readyz/not-an-endpoint',
    '/actuator/not-an-endpoint',
    '/assets/not-an-asset.js',
    '/proxy/not-an-endpoint',
    '/downloads/archive.tar.gz/checksum',
  ]) {
    const response = await request.get(path);
    expect(response.status()).toBe(404);
    expect(response.headers()['content-type'] ?? '').not.toContain('text/html');
  }

  const openApi = await request.get('/openapi/skywright-api.yaml');
  expect(openApi.status()).toBe(200);
  expect(openApi.headers()['content-type'] ?? '').not.toContain('text/html');
  expect(await openApi.body()).toEqual(
    await readFile(
      '../api/skywright-api/src/main/resources/META-INF/openapi/skywright-api.yaml',
    ),
  );

  for (const path of ['/livez', '/readyz', '/actuator/health']) {
    const response = await request.get(path);
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type'] ?? '').not.toContain('text/html');
  }
});

test('packaged resources use version-safe caching', async ({ request }) => {
  const shell = await request.get('/');
  expect(shell.status()).toBe(200);
  expect(shell.headers()['cache-control']).toMatch(/(?:no-cache|max-age=0)/u);

  const html = await shell.text();
  const assetPath =
    /(?:src|href)="([^"]+-[A-Za-z0-9_-]{8,}\.(?:css|js))"/u.exec(html)?.[1];
  expect(assetPath).toBeDefined();

  const asset = await request.get(`/${assetPath}`);
  expect(asset.status()).toBe(200);
  expect(asset.headers()['cache-control']).toContain('immutable');
});
