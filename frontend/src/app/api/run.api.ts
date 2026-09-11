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
export type Lineage = components['schemas']['RunLineage'];
export type RunPage = components['schemas']['RunPage'];
export type Progress = components['schemas']['RunProgressObservation'];
export type CreateRun = components['schemas']['CreateLocalRun'];
export type CreateManagedRun = components['schemas']['CreateManagedRun'];
export type ManagedRunForm = components['schemas']['ManagedRunForm'];
export type CommandReceipt = components['schemas']['RunCommandReceipt'];
export type LocalTarget = components['schemas']['LocalRunTarget'];
export type Project = components['schemas']['TrainingProject'];
export type Versions = components['schemas']['TrainingProjectVersionDiscovery'];
export type DatasetPage = components['schemas']['DatasetCatalogPage'];
export type Lifecycle = components['schemas']['RunLifecycleObservation'];

const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: (request) => globalThis.fetch(request),
});

export const managedRunApi = {
  async form(signal: AbortSignal): Promise<ManagedRunForm> {
    return read(
      await transport(
        api.GET('/managed-run-form', { signal, parseAs: 'text' }),
      ),
      (value) =>
        object(value) &&
        typeof value['ready'] === 'boolean' &&
        time(value['observedAt']) &&
        Array.isArray(value['workloads']) &&
        value['workloads'].length <= 1 &&
        value['workloads'].every(
          (w) =>
            object(w) &&
            w['id'] === 'demonstration' &&
            typeof w['displayName'] === 'string',
        ) &&
        Array.isArray(value['targets']) &&
        value['targets'].length <= 1 &&
        value['targets'].every(
          (t) =>
            object(t) &&
            typeof t['id'] === 'string' &&
            typeof t['gpuModel'] === 'string' &&
            Number.isSafeInteger(t['gpuCount']) &&
            Number(t['gpuCount']) > 0,
        ) &&
        Array.isArray(value['checks']) &&
        value['checks'].every(
          (c) =>
            object(c) &&
            typeof c['ready'] === 'boolean' &&
            ['component', 'code', 'detail'].every(
              (k) => typeof c[k] === 'string',
            ),
        ) &&
        (!value['ready'] ||
          (value['workloads'].length === 1 &&
            value['targets'].length === 1 &&
            value['checks'].every((c) => object(c) && c['ready'] === true))),
    );
  },
  async create(body: CreateManagedRun, signal: AbortSignal): Promise<Run> {
    return read(
      await transport(
        api.POST('/managed-runs', { body, signal, parseAs: 'text' }),
      ),
      (value) => isRun(value) && value.submissionId === body.submissionId,
    );
  },
};

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
  async lineage(runId: string, signal: AbortSignal): Promise<Lineage> {
    return read(
      await transport(
        api.GET('/runs/{runId}/lineage', {
          params: { path: { runId } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        object(value) &&
        value['runId'] === runId &&
        time(value['observedAt']) &&
        ['available', 'unavailable'].includes(String(value['availability'])) &&
        (value['predecessorRunId'] === null
          ? value['checkpointReference'] === null &&
            value['seedVerifiedAt'] === null
          : value['availability'] === 'available' &&
            text(value['predecessorRunId']) &&
            value['predecessorRunId'] !== runId &&
            text(value['checkpointReference']) &&
            time(value['seedVerifiedAt'])),
    );
  },
  async create(body: CreateRun, signal: AbortSignal): Promise<Run> {
    return read(
      await transport(api.POST('/runs', { body, signal, parseAs: 'text' })),
      (value) => isRun(value) && value.submissionId === body.submissionId,
    );
  },
  async cancel(
    runId: string,
    requestId: string,
    signal: AbortSignal,
  ): Promise<CommandReceipt> {
    return read(
      await transport(
        api.POST('/runs/{runId}/cancellations', {
          params: { path: { runId } },
          body: { requestId },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        isCommand(value) &&
        value.runId === runId &&
        value.id === requestId &&
        value.kind === 'CANCELLATION_REQUEST',
    );
  },
  async command(
    runId: string,
    commandId: string,
    signal: AbortSignal,
  ): Promise<CommandReceipt> {
    return read(
      await transport(
        api.GET('/runs/{runId}/commands/{commandId}', {
          params: { path: { runId, commandId } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        isCommand(value) && value.runId === runId && value.id === commandId,
    );
  },
  async target(signal: AbortSignal): Promise<LocalTarget> {
    return read(
      await transport(
        api.GET('/local-run-target', { signal, parseAs: 'text' }),
      ),
      (value) =>
        object(value) &&
        text(value['identity']) &&
        text(value['gpuModel']) &&
        count(value['maximumGpuCount']) &&
        Number(value['maximumGpuCount']) > 0 &&
        count(value['gpuMemoryBytes']) &&
        Number(value['gpuMemoryBytes']) > 0 &&
        typeof value['submissionAvailable'] === 'boolean' &&
        time(value['observedAt']),
    );
  },
  async projects(signal: AbortSignal): Promise<Project[]> {
    return read(
      await transport(
        api.GET('/training-projects', { signal, parseAs: 'text' }),
      ),
      (value) =>
        Array.isArray(value) &&
        value.every(
          (p) => object(p) && text(p['id']) && text(p['displayName']),
        ),
    );
  },
  async versions(projectId: string, signal: AbortSignal): Promise<Versions> {
    return read(
      await transport(
        api.GET('/training-projects/{projectId}/versions', {
          params: { path: { projectId } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        object(value) &&
        typeof value['registryAvailable'] === 'boolean' &&
        time(value['observedAt']) &&
        Array.isArray(value['versions']) &&
        value['versions'].every(
          (v) =>
            object(v) && text(v['versionLabel']) && text(v['manifestDigest']),
        ) &&
        Array.isArray(value['failures']) &&
        value['failures'].every(
          (f) => object(f) && text(f['code']) && text(f['pointer']),
        ),
    );
  },
  async datasets(
    cursor: string | undefined,
    signal: AbortSignal,
  ): Promise<DatasetPage> {
    return read(
      await transport(
        api.GET('/dataset-catalog', {
          params: { query: { limit: 20, ...(cursor ? { cursor } : {}) } },
          signal,
          parseAs: 'text',
        }),
      ),
      (value) =>
        object(value) &&
        nullableText(value['nextCursor']) &&
        Array.isArray(value['items']) &&
        value['items'].length <= 20 &&
        value['items'].every(
          (item) =>
            object(item) &&
            object(item['definition']) &&
            text(item['definition']['definitionId']) &&
            text(item['definition']['datasetId']) &&
            nullableText(item['definition']['versionLabel']) &&
            text(item['definition']['contentFingerprint']),
        ),
    );
  },
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

function isCommand(value: unknown): value is CommandReceipt {
  return (
    object(value) &&
    text(value['id']) &&
    text(value['runId']) &&
    ['SUBMISSION', 'CANCELLATION_REQUEST', 'CEILING_STOP'].includes(
      String(value['kind']),
    ) &&
    time(value['acceptedAt']) &&
    text(value['disposition']) &&
    ['projectedAt', 'forceAfter', 'stopAttemptedAt'].every(
      (key) => value[key] == null || time(value[key]),
    )
  );
}
