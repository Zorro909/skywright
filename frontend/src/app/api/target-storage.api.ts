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
  data: T | undefined,
  error: unknown,
): Promise<T> {
  if (!response.ok) {
    throw new ApiRequestFailure(
      await normalizeProblemResponse(response, error),
    );
  }
  if (data === undefined) {
    throw new ApiRequestFailure({ kind: 'malformed-response', response });
  }
  return data;
}

export const targetStorageApi = {
  async list(signal?: AbortSignal) {
    const { data, error, response } = await api.GET('/target-storages', {
      signal: signal ?? null,
    });
    return requireData(response, data, error);
  },

  async listDefaults(signal?: AbortSignal) {
    const { data, error, response } = await api.GET(
      '/target-storage-defaults',
      { signal: signal ?? null },
    );
    return requireData(response, data, error);
  },

  async create(body: CreateTargetStorage) {
    const { data, error, response } = await api.POST('/target-storages', {
      body,
    });
    return requireData(response, data, error);
  },

  async stage(
    storage: TargetStorage,
    endpoint: string,
    region: string,
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
            pathStyleAccess: storage.configuration?.pathStyleAccess ?? true,
            compatibilityOptions,
          },
        },
      },
    );
    return requireData(response, data, error);
  },

  async qualify(storageId: string) {
    const { data, error, response } = await api.POST(
      '/target-storages/{storageId}/qualification',
      { params: { path: { storageId } } },
    );
    return requireData(response, data, error);
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
      },
    );
    return requireData(response, data, error);
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
      },
    );
    return requireData(response, data, error);
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
      },
    );
    return requireData(response, data, error);
  },
};

export const TARGET_STORAGE_API = new InjectionToken<typeof targetStorageApi>(
  'TARGET_STORAGE_API',
  { providedIn: 'root', factory: () => targetStorageApi },
);
