import { InjectionToken } from '@angular/core';
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

export type Run = components['schemas']['AcceptedLocalRun'];
export type RunPage = components['schemas']['RunPage'];
export type Progress = components['schemas']['RunProgressObservation'];
export type Lifecycle = components['schemas']['RunLifecycleObservation'];

const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: (request) => globalThis.fetch(request),
});

async function transport<T>(response: Promise<T>): Promise<T> {
  try {
    return await response;
  } catch (failure) {
    if (failure instanceof ApiRequestFailure) throw failure;
    if (failure instanceof TypeError || failure instanceof DOMException)
      throw new ApiRequestFailure(classifyRequestFailure(failure));
    throw failure;
  }
}

async function read<T>(
  result: { data?: string; error?: unknown; response: Response },
  valid: (value: unknown) => boolean,
): Promise<T> {
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
  if (!valid(value))
    throw new ApiRequestFailure({
      kind: 'malformed-response',
      response: result.response,
    });
  return value as T;
}

export const runApi = {
  async page(after: string | undefined, signal: AbortSignal): Promise<RunPage> {
    return read(
      await transport(
        api.GET('/runs', {
          params: { query: { ...(after ? { after } : {}), limit: 10 } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        object(value) &&
        Array.isArray(value['items']) &&
        value['items'].length <= 10 &&
        value['items'].every(isRun) &&
        nullableText(value['nextCursor']),
    );
  },
  async get(runId: string, signal: AbortSignal): Promise<Run> {
    return read(
      await transport(
        api.GET('/runs/{runId}', {
          params: { path: { runId } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) => isRun(value) && value.runId === runId,
    );
  },
  async progress(runId: string, signal: AbortSignal): Promise<Progress> {
    return read(
      await transport(
        api.GET('/runs/{runId}/progress', {
          params: { path: { runId } },
          signal,
          parseAs: 'text',
        }),
      ),
      isProgress,
    );
  },
};
export const RUN_API = new InjectionToken<typeof runApi>('RUN_API', {
  providedIn: 'root',
  factory: () => runApi,
});

export function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function text(value: unknown): value is string {
  return typeof value === 'string';
}
function nullableText(value: unknown) {
  return value == null || text(value);
}
function time(value: unknown) {
  return text(value) && Number.isFinite(Date.parse(value));
}
function count(value: unknown) {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}
function texts(value: unknown) {
  return Array.isArray(value) && value.every(text);
}
function fact(value: unknown): boolean {
  return (
    object(value) &&
    text(value['runId']) &&
    text(value['kind']) &&
    text(value['sourceEventIdentity']) &&
    time(value['observedAt']) &&
    typeof value['completeUniqueObservation'] === 'boolean' &&
    object(value['payload']) &&
    Object.values(value['payload']).every(text)
  );
}
export function isLifecycle(value: unknown): value is Lifecycle {
  return (
    object(value) &&
    (value['state'] == null ||
      [
        'waiting',
        'running',
        'interrupted',
        'finished',
        'failed',
        'cancelled',
      ].includes(text(value['state']) ? value['state'] : '')) &&
    typeof value['terminalLatched'] === 'boolean' &&
    text(value['sourceAvailability']) &&
    ['live', 'invalid', 'unavailable'].includes(
      String(value['processAvailability']),
    ) &&
    nullableText(value['cause']) &&
    time(value['fetchedAt']) &&
    time(value['skyPilotReadAt']) &&
    time(value['runStoreReadAt']) &&
    texts(value['evidenceGaps']) &&
    ['attemptCount', 'recoveryCount', 'executionSpanMillis'].every(
      (key) => value[key] == null || count(value[key]),
    ) &&
    nullableText(value['sourceStatus']) &&
    (value['recoveryCountIsMinimum'] === undefined ||
      typeof value['recoveryCountIsMinimum'] === 'boolean') &&
    (value['executionSpanSource'] === undefined ||
      ['live', 'retained', 'unavailable'].includes(
        text(value['executionSpanSource']) ? value['executionSpanSource'] : '',
      )) &&
    (value['lastSeen'] == null ||
      (object(value['lastSeen']) &&
        text(value['lastSeen']['state']) &&
        time(value['lastSeen']['observedAt']) &&
        count(value['lastSeen']['ageMillis']))) &&
    Array.isArray(value['facts']) &&
    value['facts'].every(fact) &&
    Array.isArray(value['conflicts']) &&
    value['conflicts'].every(
      (c) =>
        object(c) &&
        text(c['kind']) &&
        text(c['sourceEventIdentity']) &&
        fact(c['selected']) &&
        Array.isArray(c['alternatives']) &&
        c['alternatives'].every(fact),
    ) &&
    Array.isArray(value['controlDecisions']) &&
    value['controlDecisions'].every(
      (c) =>
        object(c) &&
        text(c['id']) &&
        ['CANCELLATION_REQUEST', 'CEILING_STOP'].includes(String(c['kind'])) &&
        time(c['decidedAt']) &&
        typeof c['dispatchPrevented'] === 'boolean',
    )
  );
}
function isRun(value: unknown): value is Run {
  // Identity and immutable definition remain readable even if an optional lifecycle payload is invalid.
  return (
    object(value) &&
    text(value['runId']) &&
    text(value['submissionId']) &&
    time(value['acceptedAt']) &&
    value['acceptedIntent'] === 'submit' &&
    ['source-accepted', 'source-observed', 'uncertain'].includes(
      String(value['handoff']),
    ) &&
    text(value['sourceAvailability']) &&
    object(value['definition']) &&
    texts(value['evidenceGaps'])
  );
}
function isProgress(value: unknown): value is Progress {
  if (
    !object(value) ||
    !time(value['fetchedAt']) ||
    !['available', 'absent', 'invalid', 'unavailable'].includes(
      String(value['availability']),
    )
  )
    return false;
  const record = value['record'];
  if (value['availability'] !== 'available') return record == null;
  return (
    object(record) &&
    count(record['currentStep']) &&
    time(record['writtenAt']) &&
    (record['targetStep'] == null || count(record['targetStep'])) &&
    (record['latestDurableStep'] == null
      ? record['latestDurableCheckpoint'] == null
      : count(record['latestDurableStep']) &&
        Number(record['latestDurableStep']) <= Number(record['currentStep']) &&
        text(record['latestDurableCheckpoint']))
  );
}
