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

export type TargetStorage = components['schemas']['TargetStorage'];
export type TargetStorageDefaults =
  components['schemas']['TargetStorageDefaults'];
export type CreateTargetStorage = components['schemas']['CreateTargetStorage'];
export type TargetClass = components['schemas']['TargetClass'];

const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: async (request) => {
    try {
      return await globalThis.fetch(request);
    } catch (error) {
      throw new ApiRequestFailure(classifyRequestFailure(error));
    }
  },
});

async function requireData<T>(
  response: Response,
  data: string | undefined,
  error: unknown,
  accepts: (value: unknown) => value is T,
): Promise<T> {
  if (!response.ok) {
    throw new ApiRequestFailure(
      await normalizeProblemResponse(response, error),
    );
  }
  try {
    const value: unknown = JSON.parse(data ?? '');
    if (accepts(value)) {
      return value;
    }
  } catch {
    // Invalid JSON is normalized below without retaining parser details.
  }
  throw new ApiRequestFailure({ kind: 'malformed-response', response });
}

export const targetStorageApi = {
  async list(signal?: AbortSignal) {
    const { data, error, response } = await api.GET('/target-storages', {
      signal: signal ?? null,
      parseAs: 'text',
    });
    return requireData(response, data, error, isTargetStorageList);
  },

  async listDefaults(signal?: AbortSignal) {
    const { data, error, response } = await api.GET(
      '/target-storage-defaults',
      { signal: signal ?? null, parseAs: 'text' },
    );
    return requireData(response, data, error, isTargetStorageDefaultsList);
  },

  async create(body: CreateTargetStorage) {
    const { data, error, response } = await api.POST('/target-storages', {
      body,
      parseAs: 'text',
    });
    return requireData(response, data, error, isTargetStorage);
  },

  async stage(
    storage: TargetStorage,
    endpoint: string,
    region: string,
    pathStyleAccess: boolean,
    compatibilityOptions: Record<string, string>,
  ) {
    const { data, error, response } = await api.POST(
      '/target-storages/{storageId}/revisions',
      {
        params: { path: { storageId: storage.id } },
        body: {
          expectedRegistrationRevision: storage.registrationRevision,
          configuration: {
            endpoint,
            region,
            pathStyleAccess,
            compatibilityOptions,
          },
        },
        parseAs: 'text',
      },
    );
    return requireData(response, data, error, isTargetStorage);
  },

  async qualify(storageId: string) {
    const { data, error, response } = await api.POST(
      '/target-storages/{storageId}/qualification',
      { params: { path: { storageId } }, parseAs: 'text' },
    );
    return requireData(response, data, error, isTargetStorage);
  },

  async activate(storage: TargetStorage, activated: boolean) {
    const { data, error, response } = await api.PUT(
      '/target-storages/{storageId}/activation',
      {
        params: { path: { storageId: storage.id } },
        body: {
          expectedRegistrationRevision: storage.registrationRevision,
          activated,
        },
        parseAs: 'text',
      },
    );
    return requireData(response, data, error, isTargetStorage);
  },

  async replaceBindings(
    storage: TargetStorage,
    bindings: CreateTargetStorage['bindings'],
  ) {
    const { data, error, response } = await api.PUT(
      '/target-storages/{storageId}/bindings',
      {
        params: { path: { storageId: storage.id } },
        body: {
          expectedRegistrationRevision: storage.registrationRevision,
          bindings,
        },
        parseAs: 'text',
      },
    );
    return requireData(response, data, error, isTargetStorage);
  },

  async remove(storageId: string) {
    const { error, response } = await api.DELETE(
      '/target-storages/{storageId}',
      { params: { path: { storageId } } },
    );
    if (!response.ok) {
      throw new ApiRequestFailure(
        await normalizeProblemResponse(response, error),
      );
    }
  },

  async assignDefaults(
    targetClass: TargetClass,
    executionStorageId: string,
    repatriationEnabled: boolean,
    repatriationStorageId: string,
  ) {
    const { data, error, response } = await api.PUT(
      '/target-storage-defaults/{targetClass}',
      {
        params: { path: { targetClass } },
        body: {
          executionStorageId,
          repatriationEnabled,
          repatriationStorageId,
        },
        parseAs: 'text',
      },
    );
    return requireData(response, data, error, isTargetStorageDefaults);
  },
};

function isTargetStorageList(value: unknown): value is TargetStorage[] {
  return Array.isArray(value) && value.every(isTargetStorage);
}

function isTargetStorageDefaultsList(
  value: unknown,
): value is TargetStorageDefaults[] {
  return Array.isArray(value) && value.every(isTargetStorageDefaults);
}

function isTargetStorage(value: unknown): value is TargetStorage {
  return (
    hasOnlyKeys(value, [
      'id',
      'name',
      'purpose',
      'bucket',
      'registrationRevision',
      'activated',
      'eligible',
      'activeRevision',
      'candidateRevision',
      'availability',
      'configuration',
      'revisions',
      'bindings',
      'assessments',
    ]) &&
    isId(value['id']) &&
    isStringAtMost(value['name'], 255) &&
    isOneOf(value['purpose'], ['dataset', 'run-output']) &&
    isStringAtMost(value['bucket'], 255) &&
    isInteger(value['registrationRevision']) &&
    typeof value['activated'] === 'boolean' &&
    typeof value['eligible'] === 'boolean' &&
    isNullableInteger(value['activeRevision']) &&
    isNullableInteger(value['candidateRevision']) &&
    isAvailability(value['availability']) &&
    (value['configuration'] === null ||
      isConfiguration(value['configuration'])) &&
    isArrayOf(value['revisions'], isStorageRevision) &&
    isArrayOf(value['bindings'], isStorageBinding) &&
    isArrayOf(value['assessments'], isAssessment)
  );
}

function isConfiguration(
  value: unknown,
): value is components['schemas']['TargetStorageConfiguration'] {
  return (
    hasOnlyKeys(value, [
      'endpoint',
      'region',
      'pathStyleAccess',
      'compatibilityOptions',
    ]) &&
    isUriAtMost(value['endpoint'], 2048) &&
    isStringBetween(value['region'], 1, 255) &&
    typeof value['pathStyleAccess'] === 'boolean' &&
    isStringRecord(value['compatibilityOptions'])
  );
}

function isStorageRevision(
  value: unknown,
): value is components['schemas']['TargetStorageRevision'] {
  return (
    hasOnlyKeys(value, ['revision', 'state', 'configuration']) &&
    isRevision(value['revision']) &&
    isOneOf(value['state'], ['active', 'candidate', 'historical']) &&
    isConfiguration(value['configuration'])
  );
}

function isStorageBinding(
  value: unknown,
): value is components['schemas']['TargetStorageBinding'] {
  return (
    hasOnlyKeys(value, ['role', 'bindingId', 'bindingRevision', 'readiness']) &&
    isRole(value['role']) &&
    isId(value['bindingId']) &&
    isRevision(value['bindingRevision']) &&
    isOneOf(value['readiness'], ['ready', 'missing', 'invalid', 'expired'])
  );
}

function isBindingReference(
  value: unknown,
): value is components['schemas']['TargetStorageBindingReference'] {
  return (
    hasOnlyKeys(value, ['role', 'bindingId', 'bindingRevision']) &&
    isRole(value['role']) &&
    isId(value['bindingId']) &&
    isRevision(value['bindingRevision'])
  );
}

function isAssessment(
  value: unknown,
): value is components['schemas']['TargetStorageAssessment'] {
  return (
    hasOnlyKeys(value, [
      'id',
      'configurationRevision',
      'observedFrom',
      'observedUntil',
      'availability',
      'bindingRevisions',
      'capabilities',
    ]) &&
    isId(value['id']) &&
    isRevision(value['configurationRevision']) &&
    isUtcInstant(value['observedFrom']) &&
    isUtcInstant(value['observedUntil']) &&
    isAvailability(value['availability']) &&
    isArrayOf(value['bindingRevisions'], isBindingReference) &&
    isArrayOf(value['capabilities'], isCapabilityResult)
  );
}

function isCapabilityResult(
  value: unknown,
): value is components['schemas']['TargetStorageCapabilityResult'] {
  return (
    hasOnlyKeys(value, [
      'capability',
      'succeeded',
      'failureCode',
      'summary',
      'observations',
    ]) &&
    typeof value['capability'] === 'string' &&
    typeof value['succeeded'] === 'boolean' &&
    isNullableString(value['failureCode']) &&
    isNullableString(value['summary']) &&
    isStringRecord(value['observations'])
  );
}

function isTargetStorageDefaults(
  value: unknown,
): value is TargetStorageDefaults {
  return (
    hasOnlyKeys(value, [
      'targetClass',
      'executionStorageId',
      'repatriationEnabled',
      'repatriationStorageId',
    ]) &&
    isOneOf(value['targetClass'], [
      'local-single-gpu',
      'local-multi-gpu',
      'cloud-on-demand',
      'cloud-spot',
    ]) &&
    isId(value['executionStorageId']) &&
    typeof value['repatriationEnabled'] === 'boolean' &&
    isId(value['repatriationStorageId'])
  );
}

function hasOnlyKeys(
  value: unknown,
  keys: readonly string[],
): value is Record<string, unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => key in value)
  );
}

function isArrayOf<T>(
  value: unknown,
  accepts: (entry: unknown) => entry is T,
): value is T[] {
  return Array.isArray(value) && value.every(accepts);
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((entry) => typeof entry === 'string')
  );
}

function isId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/iu.test(value)
  );
}

function isRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 1;
}

function isInteger(value: unknown): value is number {
  return Number.isSafeInteger(value);
}

function isNullableInteger(value: unknown): value is number | null {
  return value === null || isInteger(value);
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isUtcInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z$/u.exec(value);
  if (!match) return false;
  const [, yearText, monthText, dayText, hourText, minuteText, secondText] =
    match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const daysInMonth = [
    31,
    year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ];
  return (
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= (daysInMonth[month - 1] ?? 0) &&
    Number(hourText) <= 23 &&
    Number(minuteText) <= 59 &&
    Number(secondText) <= 59
  );
}

function isStringAtMost(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && [...value].length <= maximum;
}

function isStringBetween(
  value: unknown,
  minimum: number,
  maximum: number,
): value is string {
  return (
    typeof value === 'string' &&
    [...value].length >= minimum &&
    [...value].length <= maximum
  );
}

function isUriAtMost(value: unknown, maximum: number): value is string {
  if (!isStringAtMost(value, maximum)) {
    return false;
  }
  const match = /^https?:\/\/([^/?#]+)(\/[^?#]*)?$/iu.exec(value);
  if (!match) {
    return false;
  }
  const [, authority = '', path = ''] = match;
  if (
    !isHttpAuthority(authority) ||
    [...path].some((character) => !isEndpointPathCharacter(character))
  ) {
    return false;
  }
  for (
    let index = value.indexOf('%');
    index >= 0;
    index = value.indexOf('%', index + 1)
  ) {
    if (!/^[0-9A-Fa-f]{2}$/u.test(value.slice(index + 1, index + 3))) {
      return false;
    }
  }
  try {
    return new URL(value).hostname.length > 0;
  } catch {
    return false;
  }
}

function isEndpointPathCharacter(value: string): boolean {
  return (
    /[A-Za-z0-9]/u.test(value) ||
    "-._~!$&'()*+,;=:@/%".includes(value) ||
    (isIriUcsCharacter(value.codePointAt(0) ?? 0) &&
      !/[\p{Cc}\p{Z}]/u.test(value))
  );
}

function isIriUcsCharacter(codePoint: number): boolean {
  if (
    (codePoint >= 0xa0 && codePoint <= 0xd7ff) ||
    (codePoint >= 0xf900 && codePoint <= 0xfdcf) ||
    (codePoint >= 0xfdf0 && codePoint <= 0xffef)
  ) {
    return true;
  }
  const plane = Math.floor(codePoint / 0x10000);
  const offset = codePoint % 0x10000;
  return (
    ((plane >= 1 && plane <= 13) || (plane === 14 && offset >= 0x1000)) &&
    offset <= 0xfffd
  );
}

function isHttpAuthority(value: string): boolean {
  const ipv6 = /^\[([0-9A-Fa-f:.]+)\](?::(\d+))?$/u.exec(value);
  if (ipv6) {
    return ipv6[1]?.includes(':') === true && isPort(ipv6[2]);
  }

  const namedHost = /^([^:]+)(?::(\d+))?$/u.exec(value);
  if (!namedHost || !isPort(namedHost[2])) {
    return false;
  }
  const host = namedHost[1]?.replace(/\.$/u, '') ?? '';
  return (
    host.length > 0 &&
    host
      .split('.')
      .every((label) =>
        /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/u.test(label),
      )
  );
}

function isPort(value: string | undefined): boolean {
  return value === undefined || Number(value) <= 65_535;
}

function isRole(
  value: unknown,
): value is components['schemas']['TargetStorageRole'] {
  return isOneOf(value, [
    'training-process',
    'backend',
    'transfer-worker',
    'metric-view',
  ]);
}

function isAvailability(
  value: unknown,
): value is components['schemas']['CapabilityAvailability'] {
  return isOneOf(value, [
    'available',
    'transiently-unavailable',
    'incompatible',
  ]);
}

function isOneOf<const T extends string>(
  value: unknown,
  allowed: readonly T[],
): value is T {
  return typeof value === 'string' && allowed.includes(value as T);
}

export const TARGET_STORAGE_API = new InjectionToken<typeof targetStorageApi>(
  'TARGET_STORAGE_API',
  { providedIn: 'root', factory: () => targetStorageApi },
);
