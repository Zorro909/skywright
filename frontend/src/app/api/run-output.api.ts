import createClient from 'openapi-fetch';
import type {
  components,
  paths,
} from '../../../target/generated-sources/openapi/skywright-api';
import {
  ApiRequestFailure,
  classifyRequestFailure,
  normalizeProblemResponse,
} from './api-failure';
import { object } from './run.api';

export type OutputPage = components['schemas']['RunOutputPage'];
export type OutputKind = 'artifact' | 'sample';
const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: (request) => globalThis.fetch(request),
});

export async function readOutputs(
  runId: string,
  kind: OutputKind,
  cursor: string | undefined,
  signal: AbortSignal,
): Promise<OutputPage> {
  let result: { data?: string; error?: unknown; response: Response };
  try {
    result = await api.GET('/runs/{runId}/outputs', {
      params: {
        path: { runId },
        query: { kind, ...(cursor ? { cursor } : {}) },
      },
      signal,
      parseAs: 'text',
    });
  } catch (failure) {
    if (failure instanceof TypeError || failure instanceof DOMException)
      throw new ApiRequestFailure(classifyRequestFailure(failure));
    throw failure;
  }
  if (!result.response.ok)
    throw new ApiRequestFailure(
      await normalizeProblemResponse(result.response, result.error),
    );
  let value: unknown;
  try {
    value = JSON.parse(result.data ?? '');
  } catch {
    /* Classified below. */
  }
  const prefix = `/api/v1/runs/${runId}/output-content?key=`;
  if (
    !object(value) ||
    !Array.isArray(value['items']) ||
    value['items'].length > 20 ||
    !(
      value['nextCursor'] === undefined ||
      typeof value['nextCursor'] === 'string'
    ) ||
    !value['items'].every(
      (item) =>
        object(item) &&
        item['kind'] === kind &&
        typeof item['name'] === 'string' &&
        Number.isSafeInteger(item['step']) &&
        Number(item['step']) >= 0 &&
        Number.isSafeInteger(item['sizeBytes']) &&
        Number(item['sizeBytes']) >= 0 &&
        typeof item['sha256'] === 'string' &&
        /^[0-9a-f]{64}$/.test(item['sha256']) &&
        typeof item['downloadUrl'] === 'string' &&
        item['downloadUrl'].startsWith(prefix),
    )
  )
    throw new ApiRequestFailure({
      kind: 'malformed-response',
      response: result.response,
    });
  return value as unknown as OutputPage;
}
