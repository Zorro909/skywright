import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('user can navigate the packaged Skywright shell', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('banner')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
  await expect(page.getByRole('main')).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Skywright' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Overview' }),
  ).toBeVisible();

  await page.getByRole('link', { name: 'About' }).click();
  await expect(page).toHaveURL(/\/about$/u);
  await expect(
    page.getByRole('heading', { level: 2, name: 'About Skywright' }),
  ).toBeVisible();
  await expect(
    page.getByText(/portable contract for machine-learning training/u),
  ).toBeVisible();

  const accessibility = await new AxeBuilder({ page }).analyze();
  expect(accessibility.violations).toEqual([]);
});

test('direct application routes boot without rewriting reserved backend URLs', async ({
  page,
  request,
}) => {
  await page.goto('/about');
  await expect(
    page.getByRole('heading', { level: 2, name: 'About Skywright' }),
  ).toBeVisible();

  await page.goto('/missing');
  await expect(
    page.getByRole('heading', { level: 2, name: 'Page not found' }),
  ).toBeVisible();

  for (const path of [
    '/api/v1/not-an-operation',
    '/actuator/not-an-endpoint',
    '/assets/not-an-asset.js',
  ]) {
    const response = await request.get(path);
    expect(response.status()).toBe(404);
    expect(response.headers()['content-type'] ?? '').not.toContain('text/html');
  }

  const openApi = await request.get('/openapi/skywright-api.yaml');
  expect(openApi.status()).toBe(200);
  expect(openApi.headers()['content-type'] ?? '').not.toContain('text/html');
  expect(await openApi.text()).toContain('openapi: 3.1.0');
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
