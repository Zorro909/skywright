import { apiFailureFrom } from './api-failure';
import { targetStorageApi } from './target-storage.api';

describe('Target Storage API', () => {
  afterEach(() => vi.restoreAllMocks());

  it('normalizes malformed successful JSON as a safe API failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{invalid json', {
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const error = await targetStorageApi
      .list()
      .catch((failure: unknown) => failure);

    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'malformed-response',
    });
    expect(globalThis.fetch).toHaveBeenCalledOnce();
  });

  it('preserves a valid conflict Problem when response parsing uses text', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json(
        {
          title: 'Conflict',
          status: 409,
          detail: 'The storage resource already has another purpose.',
          errorCode: 'SKYWRIGHT_TARGET_STORAGE_PURPOSE_CONFLICT',
          correlationId: 'conflict-correlation',
          fieldViolations: [],
        },
        {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    );

    const error = await targetStorageApi
      .list()
      .catch((failure: unknown) => failure);

    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'problem',
      problem: { errorCode: 'SKYWRIGHT_TARGET_STORAGE_PURPOSE_CONFLICT' },
    });
  });

  it('does not accept a double-encoded Problem document', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json(
        JSON.stringify({
          title: 'Conflict',
          status: 409,
          errorCode: 'SKYWRIGHT_TARGET_STORAGE_PURPOSE_CONFLICT',
          correlationId: 'conflict-correlation',
          fieldViolations: [],
        }),
        {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    );

    const error = await targetStorageApi
      .list()
      .catch((failure: unknown) => failure);

    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'malformed-response',
    });
  });
});
