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
      typeof value === 'object' &&
      value !== null &&
      'apiVersion' in value &&
      typeof value.apiVersion === 'string' &&
      'applicationVersion' in value &&
      typeof value.applicationVersion === 'string' &&
      (!('sourceRevision' in value) ||
        value.sourceRevision === null ||
        typeof value.sourceRevision === 'string')
    ) {
      return value as SystemInformation;
    }
  } catch {
    // The caller maps invalid JSON to the safe malformed-response outcome.
  }
  return undefined;
}
