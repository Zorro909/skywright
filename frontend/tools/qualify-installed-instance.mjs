import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { chromium, expect } from '@playwright/test';

const endpoint = new URL(
  process.env.SKYWRIGHT_QUALIFICATION_URL ?? 'http://127.0.0.1:8080',
);
assert.equal(endpoint.protocol, 'http:');
assert.equal(endpoint.hostname, '127.0.0.1');
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ baseURL: endpoint.origin });
const page = await context.newPage();
let runId;
let finished = false;
try {
  await page.goto('/runs/new');
  const create = page.getByRole('button', { name: 'Create Run', exact: true });
  const deadline = Date.now() + 120_000;
  while (!(await create.isEnabled())) {
    assert.ok(Date.now() < deadline, 'Installed workload did not become ready');
    await page.waitForTimeout(5000);
    const refresh = page.getByRole('button', { name: 'Refresh readiness' });
    if (await refresh.isEnabled()) await refresh.click();
  }
  assert.equal(await page.getByLabel('Workload').inputValue(), 'demonstration');
  assert.equal(await page.getByLabel('Target').inputValue(), 'local/amd');
  const accepted = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/managed-runs') &&
      response.request().method() === 'POST',
  );
  await create.click();
  const response = await accepted;
  assert.equal(response.status(), 202);
  runId = (await response.json()).runId;
  await page
    .getByRole('link', { name: 'Observe Run and inspect outputs' })
    .click();
  await page.getByRole('button', { name: 'View archived logs' }).click();
  const logs = page.getByRole('region', { name: 'Archived logs' });
  await logs.getByRole('combobox').selectOption('controller');
  await logs.getByRole('button', { name: 'Latest bounded tail' }).click();
  await logs.getByRole('combobox').selectOption('task');
  const runDeadline = Date.now() + 900_000;
  while (Date.now() < runDeadline) {
    const observation = await context.request.get(`/api/v1/runs/${runId}`);
    assert.ok(observation.ok());
    const run = await observation.json();
    if (run.lifecycle?.terminalLatched) {
      assert.equal(run.lifecycle.state, 'finished');
      finished = true;
      break;
    }
    const refresh = page.getByRole('button', { name: 'Refresh Run' });
    if (await refresh.isEnabled()) await refresh.click();
    await page.waitForTimeout(5000);
  }
  assert.ok(finished, 'Demonstration did not finish within fifteen minutes');
  await page.getByRole('button', { name: 'Refresh Run' }).click();
  const progress = page.getByRole('region', { name: 'Committed progress' });
  await progress.getByRole('button', { name: 'Refresh progress' }).click();
  await expect(progress).toContainText(/Committed Step\s+12/, {
    timeout: 30_000,
  });
  for (const stream of ['controller', 'task']) {
    await logs.getByRole('combobox').selectOption(stream);
    const archived = page.waitForResponse(
      (value) =>
        new URL(value.url()).pathname === `/api/v1/run-logs/${runId}/${stream}`,
    );
    await logs.getByRole('button', { name: 'Latest bounded tail' }).click();
    const chunk = await archived;
    assert.ok(chunk.ok());
    const body = await chunk.json();
    assert.equal(body.availability, 'available');
    assert.ok(Buffer.from(body.bytesBase64, 'base64').length > 0);
  }
  await page.getByRole('button', { name: 'View outputs' }).click();
  const download = page.waitForEvent('download');
  await page
    .getByRole('link', { name: /^Download / })
    .first()
    .click();
  const received = await download;
  assert.equal(await received.failure(), null);
  const content = await readFile(await received.path());
  const listed = await context.request.get(
    `/api/v1/runs/${runId}/outputs?kind=artifact`,
  );
  const output = (await listed.json()).items[0];
  assert.equal(content.length, output.sizeBytes);
  assert.equal(
    createHash('sha256').update(content).digest('hex'),
    output.sha256,
  );
  console.log(
    JSON.stringify({ runId, state: 'finished', artifact: output.name }),
  );
} finally {
  if (runId && !finished) {
    await context.request.post(`/api/v1/runs/${runId}/cancellations`, {
      data: { requestId: randomUUID() },
    });
  }
  await browser.close();
}
