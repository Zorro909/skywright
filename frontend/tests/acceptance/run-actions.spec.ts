import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { observedRun, progress, runId } from '../fixtures/run';

test('local submission diagnostics, uncertain replay, lineage and cancellation remain separate from lifecycle', async ({
  page,
}) => {
  const run = observedRun();
  const at = run.acceptedAt;
  const project = '00000000-0000-4000-8000-000000000001';
  const dataset = '00000000-0000-4000-8000-000000000002';
  const digest = `sha256:${'1'.repeat(64)}`;
  const requests: Record<string, unknown>[] = [];
  await page.route('**/api/v1/local-run-target', (route) =>
    route.fulfill({
      json: {
        identity: 'local/amd',
        gpuModel: 'RX7900XTX',
        maximumGpuCount: 1,
        gpuMemoryBytes: 24 * 1024 ** 3,
        submissionAvailable: true,
        observedAt: at,
      },
    }),
  );
  await page.route('**/api/v1/training-projects', (route) =>
    route.fulfill({
      json: [{ id: project, displayName: 'GPU qualification' }],
    }),
  );
  await page.route(`**/api/v1/training-projects/${project}/versions`, (route) =>
    route.fulfill({
      json: {
        registryAvailable: true,
        observedAt: at,
        versions: [{ versionLabel: 'v1', manifestDigest: digest }],
        failures: [],
      },
    }),
  );
  await page.route('**/api/v1/dataset-catalog?*', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            definition: {
              definitionId: dataset,
              datasetId: dataset,
              versionLabel: 'd1',
              contentFingerprint: digest,
            },
          },
        ],
        nextCursor: null,
      },
    }),
  );
  await page.route('**/api/v1/runs', async (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    requests.push(body);
    if (requests.length === 1) {
      await route.fulfill({
        status: 422,
        contentType: 'application/problem+json',
        json: {
          errorCode: 'SKYWRIGHT_RUN_DEFINITION_INVALID',
          correlationId: 'validation-233',
          fieldViolations: [
            {
              field: '/configuration/project/rate',
              code: 'TYPE',
              message: 'Expected a number.',
            },
          ],
        },
      });
      return;
    }
    if (requests.length === 2) {
      await route.abort('connectionreset');
      return;
    }
    expect(body).toEqual(requests[1]);
    run.submissionId = String(body['submissionId']);
    await route.fulfill({
      status: 202,
      json: { ...run, handoff: 'uncertain' },
    });
  });
  await page.route(`**/api/v1/runs/${runId}`, (route) =>
    route.fulfill({ json: run }),
  );
  await page.route(`**/api/v1/runs/${runId}/progress`, (route) =>
    route.fulfill({ json: progress() }),
  );
  await page.route(`**/api/v1/runs/${runId}/lineage`, (route) =>
    route.fulfill({
      json: {
        runId,
        availability: 'available',
        observedAt: at,
        predecessorRunId: project,
        checkpointReference: `skywright-checkpoint:v1:4:sha256:${'2'.repeat(64)}`,
        seedVerifiedAt: at,
      },
    }),
  );
  await page.route(`**/api/v1/runs/${runId}/cancellations`, async (route) => {
    const body = route.request().postDataJSON() as { requestId: string };
    await route.fulfill({
      status: 202,
      json: {
        id: body.requestId,
        runId,
        kind: 'CANCELLATION_REQUEST',
        acceptedAt: at,
        disposition: 'cooperative-request-published',
        projectedAt: at,
        forceAfter: at,
      },
    });
  });
  await page.goto('/runs/new/advanced');
  await page
    .getByRole('combobox', { name: 'Training Project', exact: true })
    .selectOption(project);
  await page
    .getByRole('combobox', { name: 'Published Project Version' })
    .selectOption(digest);
  await page
    .getByRole('combobox', { name: 'Published Dataset Definition' })
    .selectOption(dataset);
  await page
    .getByLabel('Configuration JSON')
    .fill('{"project":{"rate":"invalid"}}');
  await page.getByRole('button', { name: 'Accept and submit Run' }).click();
  await expect(page.getByText('Expected a number.')).toBeVisible();
  await expect(page.getByText('/configuration/project/rate')).toBeVisible();
  await page.getByRole('button', { name: 'Edit rejected request' }).click();
  await page.getByLabel('Configuration JSON').fill('{"project":{"rate":0.1}}');
  await page.getByRole('button', { name: 'Accept and submit Run' }).click();
  await expect(page.getByText(/Acceptance is unresolved/u)).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Replay saved submission' }).click();
  await expect(page.getByText(/Handoff: uncertain/u)).toBeVisible();
  expect(requests).toHaveLength(3);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.getByRole('link', { name: `Open Run ${runId}` }).click();
  await expect(
    page.getByRole('link', { name: project, exact: true }),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Request cancellation', exact: true })
    .click();
  await expect(page.getByText('cooperative-request-published')).toBeVisible();
  await expect(
    page.getByText('Observed lifecycle: running', { exact: true }),
  ).toBeVisible();
  if (!run.lifecycle) throw new Error('Lifecycle fixture');
  run.lifecycle.state = 'finished';
  run.lifecycle.terminalLatched = true;
  await page.getByRole('button', { name: 'Refresh Run', exact: true }).click();
  await expect(
    page.getByText('Observed lifecycle: finished', { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/confirmed a terminal outcome/u)).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});
