import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

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

test('operator can register, revise, qualify, and inspect a Target Storage safely', async ({
  page,
}) => {
  await page.goto('/target-storages');
  const registration = page
    .getByRole('region', { name: 'Register a destination' })
    .locator('form');
  await registration.getByLabel('Name').fill('Acceptance outputs');
  await registration.getByLabel('Bucket').fill('acceptance-runs');
  await registration.getByLabel('Endpoint').fill('http://storage.example');
  await registration.getByLabel('Region').fill('us-east-1');
  await registration.getByRole('button', { name: 'Register' }).click();

  const storage = page.locator('.storage-card', {
    has: page.getByRole('heading', { name: 'Acceptance outputs' }),
  });
  await expect(storage).toContainText('Candidate revision1');
  await expect(storage).toContainText('No bindings associated.');
  await expect(page.locator('input[name*="secret" i]')).toHaveCount(0);
  await expect(page.locator('input[name*="password" i]')).toHaveCount(0);
  await expect(page.locator('input[name*="token" i]')).toHaveCount(0);

  await storage
    .getByLabel('Candidate endpoint')
    .fill('http://replacement.example');
  await storage.getByRole('button', { name: 'Stage revision' }).click();
  await expect(storage).toContainText('Candidate revision2');
  await expect(storage).toContainText(
    'Revision 2 · candidate · http://replacement.example',
  );

  await storage.getByRole('button', { name: 'Qualify' }).click();
  await expect(storage).toContainText('transiently-unavailable');
  await storage.getByText(/Revision 2 · transiently-unavailable/u).click();
  await expect(storage).toContainText(
    'A ready backend Credential Binding is required',
  );

  await storage.getByRole('button', { name: 'Activate' }).click();
  await expect(page.getByRole('status')).toContainText(
    'SKYWRIGHT_TARGET_STORAGE_NOT_QUALIFIED',
  );

  await registration.getByLabel('Name').fill('Conflicting dataset');
  await registration.getByLabel('Purpose').selectOption('dataset');
  await registration.getByLabel('Bucket').fill('acceptance-runs');
  await registration.getByLabel('Endpoint').fill('http://replacement.example');
  await registration.getByLabel('Region').fill('us-east-1');
  await registration.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByRole('status')).toContainText(
    'SKYWRIGHT_TARGET_STORAGE_PURPOSE_CONFLICT',
  );

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test('operator can assign class defaults and deactivate an eligible registration', async ({
  page,
  request,
}) => {
  const created = await request.post('/api/v1/target-storages', {
    data: qualifiedRegistration,
  });
  expect(created.status()).toBe(201);
  const registration = (await created.json()) as {
    id: string;
    registrationRevision: number;
  };
  const storageId = registration.id;
  const activated = await request.put(
    `/api/v1/target-storages/${storageId}/activation`,
    {
      data: {
        expectedRegistrationRevision: registration.registrationRevision,
        activated: true,
      },
    },
  );
  expect(activated.status()).toBe(200);

  await page.goto('/target-storages');
  const defaultsForm = page
    .getByRole('heading', { name: 'Target Class defaults' })
    .locator('..')
    .locator('form');
  await defaultsForm
    .getByLabel('Target Class')
    .selectOption('local-single-gpu');
  await defaultsForm.getByLabel('Execution storage ID').fill(storageId);
  await defaultsForm.getByLabel('Repatriation storage ID').fill(storageId);
  await defaultsForm.getByRole('button', { name: 'Assign defaults' }).click();
  await expect(page.getByRole('status')).toContainText(
    'Target Class defaults assigned.',
  );
  await expect(page.getByText(/local-single-gpu: execution/u)).toContainText(
    storageId,
  );

  const card = page.locator('.storage-card', {
    has: page.getByRole('heading', { name: 'Qualified outputs' }),
  });
  await card.getByRole('button', { name: 'Deactivate' }).click();
  await expect(page.getByRole('status')).toContainText(
    'Registration deactivated.',
  );
  await expect(card).toContainText('Ineligible');
  await card.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByRole('status')).toContainText(
    'SKYWRIGHT_TARGET_STORAGE_REFERENCED',
  );
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

const qualifiedRegistration = {
  name: 'Qualified outputs',
  purpose: 'run-output',
  bucket: 'qualified-runs',
  configuration: {
    endpoint: 'http://qualified-storage.example',
    region: 'us-east-1',
    pathStyleAccess: true,
    compatibilityOptions: {},
  },
  bindings: [
    ['training-process', '00000000-0000-0000-0000-000000000011'],
    ['backend', '00000000-0000-0000-0000-000000000012'],
    ['transfer-worker', '00000000-0000-0000-0000-000000000013'],
    ['metric-view', '00000000-0000-0000-0000-000000000014'],
  ].map(([role, bindingId]) => ({ role, bindingId, bindingRevision: 1 })),
};

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
