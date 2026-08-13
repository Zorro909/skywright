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

type SystemInformation = components['schemas']['SystemInformation'];

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

export async function loadSystemInformation(abortSignal: AbortSignal) {
  const { data, error, response } = await api.GET('/system-information', {
    signal: abortSignal,
    parseAs: 'text',
  });

  if (!response.ok) {
    throw new ApiRequestFailure(
      await normalizeProblemResponse(response, error),
    );
  }

  const systemInformation = parseSystemInformation(data);
  if (!systemInformation) {
    throw new ApiRequestFailure({ kind: 'malformed-response', response });
  }
  return systemInformation;
}

export const SYSTEM_INFORMATION_LOADER = new InjectionToken<
  typeof loadSystemInformation
>('SYSTEM_INFORMATION_LOADER', {
  providedIn: 'root',
  factory: () => loadSystemInformation,
});

function parseSystemInformation(
  body: string | undefined,
): SystemInformation | undefined {
  try {
    const value: unknown = body ? JSON.parse(body) : undefined;
    if (
      isRecord(value) &&
      Object.keys(value).every((key) =>
        ['apiVersion', 'applicationVersion', 'sourceRevision'].includes(key),
      ) &&
      value['apiVersion'] === '1.0.0' &&
      typeof value['applicationVersion'] === 'string' &&
      value['applicationVersion'].length > 0 &&
      'sourceRevision' in value &&
      (value['sourceRevision'] === null ||
        (typeof value['sourceRevision'] === 'string' &&
          value['sourceRevision'].length > 0))
    ) {
      return value as SystemInformation;
    }
  } catch {
    // The caller maps invalid JSON to the safe malformed-response outcome.
  }
  return undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
