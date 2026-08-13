import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('user can navigate the packaged Skywright shell', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('banner')).toBeVisible();
  const navigation = page.getByRole('navigation', { name: 'Primary' });
  await expect(navigation).toBeVisible();
  await expect(navigation.getByRole('link')).toHaveText(['Overview', 'About']);
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

  for (const path of ['/', '/about', '/missing']) {
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
  for (let tab = 0; tab < 4; tab += 1) {
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
  expect(await openApi.text()).toContain('openapi: 3.1.0');

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
