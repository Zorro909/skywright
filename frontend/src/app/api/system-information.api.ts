import { InjectionToken } from '@angular/core';
import createClient from 'openapi-fetch';

import type { paths } from '../../../target/generated-sources/openapi/skywright-api';

const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: (request) => globalThis.fetch(request),
});

export async function loadSystemInformation(abortSignal: AbortSignal) {
  const { data, response } = await api.GET('/system-information', {
    signal: abortSignal,
  });

  if (!response.ok || !data) {
    throw new Error('System Information is unavailable.');
  }
  return data;
}

export const SYSTEM_INFORMATION_LOADER = new InjectionToken<
  typeof loadSystemInformation
>('SYSTEM_INFORMATION_LOADER', {
  providedIn: 'root',
  factory: () => loadSystemInformation,
});
