import { RunRequestJournal } from './run-request-journal';
import type { CreateRun } from '../api/run.api';

const request: CreateRun = {
  submissionId: '00000000-0000-4000-8000-000000000233',
  trainingProjectId: '00000000-0000-4000-8000-000000000001',
  manifestArtifactDigest: `sha256:${'1'.repeat(64)}`,
  datasetDefinitionId: '00000000-0000-4000-8000-000000000002',
  target: 'local/amd',
  gpuCount: 1,
  configuration: { steps: 20 },
};

describe('Browser Run request journal', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());
  it('serializes concurrent tabs and restores exact inputs after a reload', async () => {
    const first = new RunRequestJournal();
    const second = new RunRequestJournal();
    const altered = {
      ...request,
      submissionId: crypto.randomUUID(),
      configuration: { steps: 999 },
    };
    const [a, b] = await Promise.all([
      first.freeze(request),
      second.freeze(altered),
    ]);
    expect(a).toEqual(b);
    expect(new RunRequestJournal().read()).toEqual(a);
    await expect(second.clearSettled()).rejects.toThrow('uncertain');
    await first.settle(a.request.submissionId, {
      acceptedRunId: request.trainingProjectId,
    });
    expect(second.read()?.acceptedRunId).toBe(request.trainingProjectId);
    await second.clearSettled();
    expect(first.read()).toBeUndefined();
  });
  it('keeps cancellation identity through replay and adopts a server-known intent', async () => {
    const journal = new RunRequestJournal();
    const id = await journal.cancellation(request.trainingProjectId);
    expect(
      await new RunRequestJournal().cancellation(request.trainingProjectId),
    ).toBe(id);
    expect(journal.savedCancellation(request.trainingProjectId)).toBe(id);
    expect(
      await journal.cancellation(
        request.trainingProjectId,
        request.submissionId,
      ),
    ).toBe(request.submissionId);
  });
  it('refuses corrupted storage and does not replace a request silently', async () => {
    localStorage.setItem('skywright.local-submission.v1', '{');
    const journal = new RunRequestJournal();
    expect(() => journal.read()).toThrow();
    await expect(journal.freeze(request)).rejects.toThrow();
    expect(localStorage.getItem('skywright.local-submission.v1')).toBe('{');
  });
  it('does not overwrite a newer request with a late acceptance response', async () => {
    const journal = new RunRequestJournal();
    await journal.freeze(request);
    await journal.settle(request.submissionId, { rejected: true });
    await journal.clearSettled();
    const next = { ...request, submissionId: crypto.randomUUID() };
    await journal.freeze(next);
    await journal.settle(request.submissionId, {
      acceptedRunId: request.trainingProjectId,
    });
    expect(journal.read()).toEqual({ version: 1, request: next });
  });
});
