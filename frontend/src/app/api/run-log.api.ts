import createClient from 'openapi-fetch';
import type {
  components,
  paths,
} from '../../../target/generated-sources/openapi/skywright-api';
import { object } from './run.api';

export type LogPage = components['schemas']['RunLogPage'];
export type LogNavigation = components['schemas']['RunLogNavigation'];
export type LogStream = 'task' | 'controller';
const MAX_FRAME = 128 * 1024;
const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: (request) => globalThis.fetch(request),
});

function offset(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^(0|[1-9][0-9]{0,18})$/u.test(value) &&
    BigInt(value) <= 9223372036854775807n
  );
}

export function logBytes(page: LogPage): Uint8Array {
  if (page.bytesBase64.length > 87384)
    throw new Error('Archive page exceeds its bound');
  const bytes = Uint8Array.from(atob(page.bytesBase64), (character) =>
    character.charCodeAt(0),
  );
  if (
    bytes.length > 65536 ||
    (page.availability === 'available' &&
      (page.fromCursor === null ||
        page.nextCursor === null ||
        BigInt(page.nextCursor) - BigInt(page.fromCursor) !==
          BigInt(bytes.length)))
  )
    throw new Error('Archive byte range is invalid');
  return bytes;
}

export function logPage(
  value: unknown,
  runId: string,
  stream: LogStream,
): LogPage {
  if (
    !object(value) ||
    value['runId'] !== runId ||
    value['stream'] !== stream ||
    !['available', 'unavailable'].includes(String(value['availability'])) ||
    typeof value['observedAt'] !== 'string' ||
    !Number.isFinite(Date.parse(value['observedAt'])) ||
    !['staging', 'finalized', 'unknown'].includes(
      String(value['archiveState']),
    ) ||
    !['available', 'unavailable', 'not-observed', 'unknown'].includes(
      String(value['sourceAvailability']),
    ) ||
    !['pending', 'complete', 'partial', 'unknown'].includes(
      String(value['completion']),
    ) ||
    !['reason', 'sourceReason'].every(
      (field) => value[field] === null || typeof value[field] === 'string',
    ) ||
    !(
      value['lastSuccessfulFetch'] === null ||
      (typeof value['lastSuccessfulFetch'] === 'string' &&
        Number.isFinite(Date.parse(value['lastSuccessfulFetch'])))
    ) ||
    typeof value['bytesBase64'] !== 'string' ||
    !['fromCursor', 'nextCursor', 'endCursor'].every(
      (field) => value[field] === null || offset(value[field]),
    )
  )
    throw new Error('Archive response is invalid');
  const page = value as unknown as LogPage;
  if (
    page.availability === 'available'
      ? page.archiveState === 'unknown' ||
        (page.archiveState === 'staging'
          ? page.completion !== 'pending'
          : !['complete', 'partial'].includes(page.completion))
      : page.archiveState !== 'unknown' ||
        page.completion !== 'unknown' ||
        page.bytesBase64 !== ''
  )
    throw new Error('Archive authority is invalid');
  if (
    page.availability === 'available' &&
    (page.fromCursor === null ||
      page.nextCursor === null ||
      page.endCursor === null ||
      BigInt(page.fromCursor) > BigInt(page.nextCursor) ||
      BigInt(page.nextCursor) > BigInt(page.endCursor))
  )
    throw new Error('Archive cursor is invalid');
  logBytes(page);
  return page;
}

async function json(response: Response): Promise<unknown> {
  if (!response.ok || !response.body)
    throw new Error(`Archive request unavailable (${response.status})`);
  const reader = response.body.getReader();
  let body = '';
  let size = 0;
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const result = await reader.read();
      if (result.done) break;
      size += result.value.length;
      if (size > MAX_FRAME)
        throw new Error('Archive response exceeds its bound');
      body += decoder.decode(result.value, { stream: true });
    }
    return JSON.parse(body + decoder.decode()) as unknown;
  } finally {
    await reader.cancel();
  }
}

export const runLogApi = {
  async page(
    runId: string,
    stream: LogStream,
    window: { cursor?: string; before?: string },
    signal: AbortSignal,
  ): Promise<LogPage> {
    const { response } = await api.GET('/run-logs/{runId}/{stream}', {
      params: { path: { runId, stream }, query: window },
      signal,
      parseAs: 'stream',
    });
    return logPage(await json(response), runId, stream);
  },
  async navigation(
    runId: string,
    cursor: string | undefined,
    signal: AbortSignal,
  ): Promise<LogNavigation> {
    const { response } = await api.GET('/run-logs/{runId}/navigation', {
      params: {
        path: { runId },
        query: cursor === undefined ? {} : { cursor },
      },
      signal,
      parseAs: 'stream',
    });
    const value = await json(response);
    if (
      !object(value) ||
      !Array.isArray(value['items']) ||
      value['items'].length > 128 ||
      !(value['nextCursor'] === null || offset(value['nextCursor'])) ||
      !value['items'].every(
        (item: unknown) =>
          object(item) &&
          offset(item['cursor']) &&
          ['setup', 'attempt'].includes(String(item['kind'])) &&
          (item['attemptId'] === null ||
            typeof item['attemptId'] === 'string') &&
          (item['preparationCursor'] === null ||
            offset(item['preparationCursor'])),
      )
    )
      throw new Error('Archive navigation is invalid');
    return value as unknown as LogNavigation;
  },
  async follow(
    runId: string,
    stream: LogStream,
    cursor: string | undefined,
    signal: AbortSignal,
    consume: (page: LogPage) => Promise<void>,
  ): Promise<void> {
    const { response } = await api.GET('/run-logs/{runId}/{stream}/follow', {
      params: {
        path: { runId, stream },
        query: cursor === undefined ? {} : { cursor },
      },
      signal,
      parseAs: 'stream',
    });
    if (!response.ok || !response.body)
      throw new Error(`Archive follow unavailable (${response.status})`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let pending = '';
    try {
      for (;;) {
        const result = await reader.read();
        if (result.done) return;
        pending += decoder.decode(result.value, { stream: true });
        if (pending.length > MAX_FRAME)
          throw new Error('Archive event exceeds its bound');
        for (;;) {
          const boundary = /\r?\n\r?\n/u.exec(pending);
          if (!boundary) break;
          const event = pending.slice(0, boundary.index);
          pending = pending.slice(boundary.index + boundary[0].length);
          const lines = event.split(/\r?\n/u);
          if (!lines.includes('event: archive')) continue;
          const data = lines
            .filter((line) => line.startsWith('data: '))
            .map((line) => line.slice(6))
            .join('\n');
          const page = logPage(JSON.parse(data) as unknown, runId, stream);
          if (signal.aborted) return;
          await consume(page);
          if (
            page.archiveState === 'finalized' &&
            page.nextCursor === page.endCursor
          )
            return;
        }
      }
    } finally {
      await reader.cancel();
    }
  },
};
