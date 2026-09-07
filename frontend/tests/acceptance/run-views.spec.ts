import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { observedRun, progress, runId } from '../fixtures/run';

test('Run source loss preserves identity, durable progress, conflicts and terminal evidence', async ({
  page,
}) => {
  const run = observedRun();
  if (!run.lifecycle) throw new Error('fixture');
  const fact = {
    runId,
    kind: 'TERMINATION',
    sourceEventIdentity: 'provider-event',
    payload: { status: 'FAILED' },
    observedAt: run.acceptedAt,
    completeUniqueObservation: true,
  };
  run.lifecycle.conflicts = [
    {
      kind: fact.kind,
      sourceEventIdentity: fact.sourceEventIdentity,
      selected: fact,
      alternatives: [{ ...fact, payload: { status: 'SUCCEEDED' } }],
    },
  ];
  await page.route('**/api/v1/runs?*', (route) =>
    route.fulfill({ json: { items: [run], nextCursor: null } }),
  );
  await page.route(`**/api/v1/runs/${runId}`, (route) =>
    route.fulfill({ json: run }),
  );
  await page.route(`**/api/v1/runs/${runId}/progress`, (route) =>
    route.fulfill({ json: progress() }),
  );
  await page.goto('/');
  await page.getByRole('link', { name: `Run ${runId}` }).click();
  await expect(
    page.getByText('Observed lifecycle: running', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('Latest Durable Safe Point:', { exact: false }),
  ).toContainText('10');
  await expect(page.getByText('No target Step reported.')).toBeVisible();
  expect(await page.locator('main').innerText()).not.toContain('%');
  await page.getByText('Retained-fact conflicts (1)', { exact: true }).click();
  await expect(
    page.getByText('Conflicting retained observations:'),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Metrics unavailable' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Cost unavailable' }),
  ).toBeVisible();
  run.lifecycle.state = null;
  run.lifecycle.sourceAvailability = 'unavailable';
  run.lifecycle.sourceStatus = null;
  run.lifecycle.executionSpanMillis = null;
  run.lifecycle.recoveryCount = null;
  run.lifecycle.lastSeen = {
    state: 'running',
    observedAt: run.acceptedAt,
    ageMillis: 600_000,
  };
  await page.getByRole('button', { name: 'Refresh Run', exact: true }).click();
  await expect(
    page.getByText('Observed lifecycle: unavailable', { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Last seen running/u)).toBeVisible();
  await expect(page.getByText('tiny-training', { exact: true })).toBeVisible();
  run.lifecycle.state = 'finished';
  run.lifecycle.terminalLatched = true;
  delete run.lifecycle.lastSeen;
  await page.getByRole('button', { name: 'Refresh Run', exact: true }).click();
  await expect(
    page.getByText(/Terminal supported by durable evidence/u),
  ).toBeVisible();
  await page.route(`**/api/v1/runs/${runId}/progress`, (route) =>
    route.abort('connectionrefused'),
  );
  await page.getByRole('button', { name: 'Refresh progress' }).click();
  await expect(
    page.getByText('Progress unavailable.', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('Last-seen Progress Record', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('Observed lifecycle: finished', { exact: true }),
  ).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test('late cancellation evidence does not optimistically advance lifecycle and absent progress is not zero', async ({
  page,
}) => {
  const run = observedRun();
  if (!run.lifecycle) throw new Error('fixture');
  run.lifecycle.state = 'waiting';
  run.lifecycle.sourceStatus = 'SETTING_UP';
  run.lifecycle.evidenceGaps = ['EXECUTION_TERMINATION_REPORT_ABSENT'];
  await page.route(`**/api/v1/runs/${runId}`, (route) =>
    route.fulfill({ json: run }),
  );
  await page.route(`**/api/v1/runs/${runId}/progress`, (route) =>
    route.fulfill({
      json: { availability: 'absent', fetchedAt: run.acceptedAt },
    }),
  );
  await page.goto(`/runs/${runId}`);
  await expect(
    page.getByText('SkyPilot observation: SETTING_UP'),
  ).toBeVisible();
  await expect(page.getByText(/Progress absent/u)).toBeVisible();
  await page.getByText('Evidence gaps (1)', { exact: true }).click();
  await expect(
    page.getByText('EXECUTION_TERMINATION_REPORT_ABSENT'),
  ).toBeVisible();
  run.lifecycle.controlDecisions = [
    {
      id: run.submissionId,
      kind: 'CANCELLATION_REQUEST',
      decidedAt: run.acceptedAt,
      dispatchPrevented: false,
    },
  ];
  await page.getByRole('button', { name: 'Refresh Run', exact: true }).click();
  await expect(page.getByText(/CANCELLATION_REQUEST/u)).toBeVisible();
  await expect(
    page.getByText('Observed lifecycle: waiting', { exact: true }),
  ).toBeVisible();
  run.lifecycle.state = 'cancelled';
  run.lifecycle.terminalLatched = true;
  await page.getByRole('button', { name: 'Refresh Run', exact: true }).click();
  await expect(page.getByText(/Observed lifecycle: cancelled/u)).toBeVisible();
});
