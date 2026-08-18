import {
  apiFailureFrom,
  classifyRequestFailure,
  isApiFailure,
  normalizeProblemResponse,
} from './api-failure';

describe('API failure normalization', () => {
  it('retains safe Problem fields and the original response metadata', async () => {
    const response = new Response(
      JSON.stringify({
        type: 'https://skywright.example/problems/invalid-request',
        title: 'Invalid request',
        status: 422,
        detail: 'Two fields need attention.',
        instance: '/api/v1/runs',
        errorCode: 'SKYWRIGHT_INVALID_REQUEST',
        correlationId: 'body-correlation',
        unavailableSource: 'PostgreSQL',
        retryable: true,
        fieldViolations: [
          {
            field: 'configuration.batchSize',
            code: 'minimum',
            message: 'Must be at least 1.',
          },
        ],
      }),
      {
        status: 422,
        headers: {
          'Content-Type': 'application/problem+json; charset=utf-8',
          'X-Correlation-ID': 'effective-correlation',
          'X-Request-Metadata': 'retained',
        },
      },
    );

    const failure = await normalizeProblemResponse(response);

    expect(failure.kind).toBe('problem');
    if (failure.kind !== 'problem') {
      return;
    }
    expect(failure.problem).toEqual({
      type: 'https://skywright.example/problems/invalid-request',
      title: 'Invalid request',
      status: 422,
      detail: 'Two fields need attention.',
      instance: '/api/v1/runs',
      errorCode: 'SKYWRIGHT_INVALID_REQUEST',
      correlationId: 'effective-correlation',
      unavailableSource: 'PostgreSQL',
      retryable: true,
      fieldViolations: [
        {
          field: 'configuration.batchSize',
          code: 'minimum',
          message: 'Must be at least 1.',
        },
      ],
    });
    expect(failure.response).toBe(response);
    expect(failure.response.headers.get('X-Request-Metadata')).toBe('retained');
  });

  it('keeps malformed, network, and aborted requests distinct', async () => {
    const malformed = await normalizeProblemResponse(
      new Response('{not json', {
        status: 500,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    const network = classifyRequestFailure(new TypeError('socket details'));
    const aborted = classifyRequestFailure(
      new DOMException('request details', 'AbortError'),
    );

    expect(malformed.kind).toBe('malformed-response');
    expect(network).toEqual({ kind: 'network' });
    expect(aborted).toEqual({ kind: 'aborted' });
  });

  it('rejects incomplete failures and terminates cyclic cause traversal', () => {
    expect(isApiFailure({ kind: 'problem' })).toBe(false);
    expect(isApiFailure({ kind: 'malformed-response' })).toBe(false);

    const first = new Error('first');
    const second = new Error('second', { cause: first });
    Object.defineProperty(first, 'cause', { value: second });

    expect(apiFailureFrom(first)).toBeUndefined();
  });
});
