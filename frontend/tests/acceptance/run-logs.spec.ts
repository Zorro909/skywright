import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { observedRun, progress, runId } from '../fixtures/run';

test('packaged Run details render independent raw task/controller archives and partial reasons', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const run = observedRun();
  await page.route('**/api/v1/runs/' + runId, (route) =>
    route.fulfill({ json: run }),
  );
  await page.route('**/api/v1/runs/' + runId + '/progress', (route) =>
    route.fulfill({ json: progress() }),
  );
  await page.route('**/api/v1/run-logs/' + runId + '/navigation', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            cursor: '0',
            kind: 'setup',
            attemptId: null,
            preparationCursor: null,
          },
        ],
        nextCursor: null,
      },
    }),
  );
  for (const stream of ['task', 'controller']) {
    const bytes = Buffer.from(
      stream === 'task'
        ? '\u001b[31m€ task failure\u001b[0m\r\n'
        : 'controller output\r\n',
      'utf8',
    );
    await page.route('**/api/v1/run-logs/' + runId + '/' + stream, (route) =>
      route.fulfill({
        json: {
          runId,
          stream,
          availability: 'available',
          observedAt: run.acceptedAt,
          archiveState: 'finalized',
          sourceAvailability: 'unavailable',
          sourceReason: 'SOURCE_GENERATION_LOST',
          lastSuccessfulFetch: run.acceptedAt,
          completion: 'partial',
          reason: 'SOURCE_GENERATION_LOST',
          fromCursor: '0',
          nextCursor: String(bytes.length),
          endCursor: String(bytes.length),
          bytesBase64: bytes.toString('base64'),
        },
      }),
    );
  }
  await page.goto('/runs/' + runId);
  await page.getByRole('button', { name: 'View archived logs' }).click();
  const viewer = page.getByRole('region', {
    name: 'Archived logs',
    exact: true,
  });
  await expect(
    viewer.getByText('Archive finalized; follow ended'),
  ).toBeVisible();
  await expect(viewer.locator('.xterm-rows')).toContainText('€ task failure');
  await expect(viewer.getByText('Completion:', { exact: false })).toContainText(
    'SOURCE_GENERATION_LOST',
  );
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await viewer
    .getByRole('combobox', { name: 'Stream' })
    .selectOption('controller');
  await expect(viewer.locator('.xterm-rows')).toContainText(
    'controller output',
  );
  await expect(viewer.locator('.xterm-rows')).not.toContainText('task failure');
  await page.getByRole('button', { name: 'Close archived logs' }).click();
  await expect(viewer).toHaveCount(0);
  expect(errors).toEqual([]);
});
