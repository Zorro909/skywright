import { apiFailureFrom } from './api-failure';
import { targetStorageApi } from './target-storage.api';

describe('Target Storage API', () => {
  afterEach(() => vi.restoreAllMocks());

  it('returns a successful list that matches the generated schema', async () => {
    const storage = storageFixture();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([storage]));

    await expect(targetStorageApi.list()).resolves.toEqual([storage]);
  });

  it('accepts a bracketed IPv6 endpoint with an explicit port', async () => {
    const storage = {
      ...storageFixture(),
      configuration: {
        ...storageFixture().configuration,
        endpoint: 'https://[2001:db8::1]:9443/path',
      },
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([storage]));

    await expect(targetStorageApi.list()).resolves.toEqual([storage]);
  });

  it('accepts an endpoint path containing Unicode returned by the backend', async () => {
    const storage = {
      ...storageFixture(),
      configuration: {
        ...storageFixture().configuration,
        endpoint: 'https://storage.example/é',
      },
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([storage]));

    await expect(targetStorageApi.list()).resolves.toEqual([storage]);
  });

  it('returns Target Class defaults that match the generated schema', async () => {
    const defaults = {
      targetClass: 'cloud-spot',
      executionStorageId: '00000000-0000-0000-0000-000000000080',
      repatriationEnabled: true,
      repatriationStorageId: '00000000-0000-0000-0000-000000000082',
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([defaults]));

    await expect(targetStorageApi.listDefaults()).resolves.toEqual([defaults]);
  });

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

  it.each([null, {}, [{}]])(
    'rejects a successful list outside the generated schema: %o',
    async (body) => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json(body));

      const error = await targetStorageApi
        .list()
        .catch((failure: unknown) => failure);

      expect(apiFailureFrom(error)).toMatchObject({
        kind: 'malformed-response',
      });
    },
  );

  it.each([
    { ...storageFixture(), name: 'x'.repeat(256) },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'not a URI',
        region: '',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example/%GG',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example/%ZZ',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example/%',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: `https://storage.example/${String.fromCodePoint(0x7f)}`,
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: `https://storage.example/${String.fromCodePoint(0x85)}`,
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: `https://storage.example/${String.fromCodePoint(0x2028)}`,
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: `https://storage.example/${String.fromCharCode(0xd800)}`,
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example/a|b',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example/a[b',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://storage.example\\evil',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://é.example',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      configuration: {
        endpoint: 'https://exa{mple.com',
        region: 'eu-central-1',
        pathStyleAccess: true,
        compatibilityOptions: {},
      },
    },
    {
      ...storageFixture(),
      assessments: [
        {
          id: '00000000-0000-0000-0000-000000000081',
          configurationRevision: 1,
          observedFrom: '2026-8-19T19:00:00Z',
          observedUntil: '2026-08-19T19:00:01Z',
          availability: 'available',
          bindingRevisions: [],
          capabilities: [],
        },
      ],
    },
  ])(
    'rejects a type-correct response outside schema constraints',
    async (storage) => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([storage]));

      const error = await targetStorageApi
        .list()
        .catch((failure: unknown) => failure);

      expect(apiFailureFrom(error)).toMatchObject({
        kind: 'malformed-response',
      });
    },
  );

  it.each([
    {
      ...storageFixture(),
      revisions: [
        { ...storageFixture().revisions[0], state: 'unexpected-state' },
      ],
    },
    {
      ...storageFixture(),
      bindings: [
        { ...storageFixture().bindings[0], readiness: 'unexpected-readiness' },
      ],
    },
    {
      ...storageFixture(),
      assessments: [
        {
          ...storageFixture().assessments[0],
          capabilities: [
            {
              ...storageFixture().assessments[0]?.capabilities[0],
              succeeded: 'yes',
            },
          ],
        },
      ],
    },
  ])('rejects a malformed nested response field', async (storage) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([storage]));

    const error = await targetStorageApi
      .list()
      .catch((failure: unknown) => failure);

    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'malformed-response',
    });
  });

  it('rejects malformed Target Class defaults', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json([
        {
          targetClass: 'unknown-class',
          executionStorageId: 'not-a-uuid',
          repatriationEnabled: true,
          repatriationStorageId: '00000000-0000-0000-0000-000000000082',
        },
      ]),
    );

    const error = await targetStorageApi
      .listDefaults()
      .catch((failure: unknown) => failure);
    expect(apiFailureFrom(error)).toMatchObject({
      kind: 'malformed-response',
    });
  });
});

function storageFixture() {
  const configuration = {
    endpoint: 'https://storage.example',
    region: 'eu-central-1',
    pathStyleAccess: true,
    compatibilityOptions: { checksumCalculation: 'when-required' },
  };
  const binding = {
    role: 'backend',
    bindingId: '00000000-0000-0000-0000-000000000090',
    bindingRevision: 1,
  };
  return {
    id: '00000000-0000-0000-0000-000000000080',
    name: 'Outputs',
    purpose: 'run-output',
    bucket: 'outputs',
    registrationRevision: 1,
    activated: false,
    eligible: false,
    activeRevision: null,
    candidateRevision: 1,
    availability: 'transiently-unavailable',
    configuration,
    revisions: [{ revision: 1, state: 'candidate', configuration }],
    bindings: [{ ...binding, readiness: 'ready' }],
    assessments: [
      {
        id: '00000000-0000-0000-0000-000000000081',
        configurationRevision: 1,
        observedFrom: '2026-08-19T19:00:00Z',
        observedUntil: '2026-08-19T19:00:01.5Z',
        availability: 'available',
        bindingRevisions: [binding],
        capabilities: [
          {
            capability: 'put-object',
            succeeded: true,
            failureCode: null,
            summary: null,
            observations: { provider: 'test' },
          },
        ],
      },
    ],
  };
}
