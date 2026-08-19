import {
  createTargetStorage,
  listTargetStorages,
  qualifyTargetStorage,
} from './target-storage.api';

describe('Target Storage API', () => {
  afterEach(() => vi.restoreAllMocks());

  it('submits only non-secret Credential Binding references', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(
        Response.json({ id: '773bf885-10b0-4387-ab20-4c60a77f254b' }),
      );

    await createTargetStorage({
      name: 'Run outputs',
      purpose: 'run-output',
      bucket: 'outputs',
      configuration: {
        endpoint: 'http://storage.example.test',
        region: 'us-east-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
      bindings: [
        {
          role: 'backend',
          bindingId: '8e60f26b-567a-40ae-906e-01a1a57354c9',
          bindingRevision: 1,
        },
      ],
    });

    const request = fetch.mock.calls[0]?.[0] as Request;
    expect(new URL(request.url).pathname).toBe('/api/v1/target-storages');
    expect(request.method).toBe('POST');
    const body = await request.clone().text();
    expect(body).toContain('bindingId');
    expect(body).not.toMatch(
      /secret|password|credentialValue|accessKey|readiness/iu,
    );
  });

  it('uses the generated registry and qualification paths', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json({ id: 'storage-id' }));

    await listTargetStorages();
    await qualifyTargetStorage('storage-id');

    const requests = fetch.mock.calls.map((call) => call[0] as Request);
    expect(new URL(requests[0]?.url ?? '').pathname).toBe(
      '/api/v1/target-storages',
    );
    expect(requests[0]?.method).toBe('GET');
    expect(new URL(requests[1]?.url ?? '').pathname).toBe(
      '/api/v1/target-storages/storage-id/qualification',
    );
    expect(requests[1]?.method).toBe('POST');
  });
});
