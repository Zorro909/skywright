import { apiFailureFrom } from './api-failure';
import { loadSystemInformation } from './system-information.api';

describe('System Information API', () => {
  afterEach(() => vi.restoreAllMocks());

  it('returns generated-boundary data from a valid response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json({
        apiVersion: '1.0.0',
        applicationVersion: '0.1.0-test',
        sourceRevision: null,
      }),
    );

    await expect(
      loadSystemInformation(new AbortController().signal),
    ).resolves.toEqual({
      apiVersion: '1.0.0',
      applicationVersion: '0.1.0-test',
      sourceRevision: null,
    });
    expect(globalThis.fetch).toHaveBeenCalledOnce();
  });

  it('preserves a valid product Problem and its HTTP response', async () => {
    const response = Response.json(
      {
        type: 'about:blank',
        title: 'Unavailable',
        status: 503,
        detail: 'The capability is temporarily unavailable.',
        instance: '/api/v1/system-information',
        errorCode: 'SKYWRIGHT_CAPABILITY_UNAVAILABLE',
        correlationId: 'problem-correlation',
        fieldViolations: [],
      },
      {
        status: 503,
        headers: {
          'Content-Type': 'application/problem+json',
          'X-Correlation-ID': 'effective-correlation',
        },
      },
    );
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response);

    const error = await loadSystemInformation(
      new AbortController().signal,
    ).catch((error: unknown) => error);
    const failure = apiFailureFrom(error);

    expect(failure).toMatchObject({
      kind: 'problem',
      problem: {
        detail: 'The capability is temporarily unavailable.',
        errorCode: 'SKYWRIGHT_CAPABILITY_UNAVAILABLE',
        correlationId: 'effective-correlation',
      },
      response,
    });
  });

  it('does not retry or expose malformed response content', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{internal invalid json', {
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const error = await loadSystemInformation(
      new AbortController().signal,
    ).catch((failure: unknown) => failure);
    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'malformed-response',
    });
    expect(globalThis.fetch).toHaveBeenCalledOnce();
  });

  it('keeps network loss and abortion distinct', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockRejectedValueOnce(new TypeError('network internals'))
      .mockRejectedValueOnce(new DOMException('abort internals', 'AbortError'));

    const network = await loadSystemInformation(
      new AbortController().signal,
    ).catch((failure: unknown) => failure);
    const aborted = await loadSystemInformation(
      new AbortController().signal,
    ).catch((failure: unknown) => failure);

    expect(apiFailureFrom(network)).toEqual({ kind: 'network' });
    expect(apiFailureFrom(aborted)).toEqual({ kind: 'aborted' });
  });
});
