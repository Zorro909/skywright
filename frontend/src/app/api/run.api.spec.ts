import { runApi, isLifecycle } from './run.api';
import { observedRun, progress, runId } from '../../../tests/fixtures/run';
import { ApiRequestFailure } from './api-failure';

describe('Run reads', () => {
  afterEach(() => vi.restoreAllMocks());
  it('bounds list requests and follows the server cursor', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(
        Response.json({ items: [observedRun()], nextCursor: runId }),
      );
    await expect(
      runApi.page(runId, new AbortController().signal),
    ).resolves.toMatchObject({ nextCursor: runId });
    const request = fetch.mock.calls[0]?.[0] as Request;
    expect(request.url).toContain(`after=${runId}`);
    expect(request.url).toContain('limit=10');
  });
  it('keeps identity readable when lifecycle is malformed', async () => {
    const body = { ...observedRun(), lifecycle: { state: 'cancelled' } };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json(body));
    const run = await runApi.get(runId, new AbortController().signal);
    expect(run.runId).toBe(runId);
    expect(isLifecycle(run.lifecycle)).toBe(false);
  });
  it.each([
    { ...progress(), record: { ...progress().record, currentStep: -1 } },
    { ...progress(), record: { ...progress().record, latestDurableStep: 13 } },
    { ...progress(), record: { ...progress().record, writtenAt: 'invalid' } },
    {
      ...progress(),
      record: {
        ...progress().record,
        currentStep: Number.MAX_SAFE_INTEGER + 1,
      },
    },
    { availability: 'available', fetchedAt: '2026-09-07T10:00:00Z' },
  ])(
    'rejects invalid progress rather than reporting a false safe point',
    async (body) => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json(body));
      await expect(
        runApi.progress(runId, new AbortController().signal),
      ).rejects.toBeInstanceOf(ApiRequestFailure);
    },
  );
  it('does not need a target to return a valid record', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json(progress()));
    await expect(
      runApi.progress(runId, new AbortController().signal),
    ).resolves.toEqual(progress());
  });
  it('classifies source loss without retrying', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValue(new TypeError('private internals'));
    await expect(
      runApi.get(runId, new AbortController().signal),
    ).rejects.toMatchObject({ outcome: { kind: 'network' } });
    expect(fetch).toHaveBeenCalledOnce();
  });
  it('classifies a response body failure after headers as a capability failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.error(new TypeError('socket lost during body'));
          },
        }),
        { headers: { 'Content-Type': 'application/json' } },
      ),
    );
    await expect(
      runApi.progress(runId, new AbortController().signal),
    ).rejects.toMatchObject({ outcome: { kind: 'network' } });
  });
});
