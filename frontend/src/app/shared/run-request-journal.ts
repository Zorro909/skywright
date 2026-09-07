import { InjectionToken } from '@angular/core';
import { object, type CreateRun } from '../api/run.api';

export interface SubmissionJournal {
  readonly version: 1;
  readonly request: CreateRun;
  readonly acceptedRunId?: string;
  readonly rejected?: true;
}
const key = 'skywright.local-submission.v1';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;

/** One recoverable submission per browser origin. Changes serialize across tabs. */
export class RunRequestJournal {
  constructor(private readonly storage: Storage = localStorage) {}

  read(): SubmissionJournal | undefined {
    const raw = this.storage.getItem(key);
    if (raw === null) return undefined;
    const value: unknown = JSON.parse(raw);
    if (!object(value) || value['version'] !== 1 || !object(value['request']))
      throw new Error('Unreadable saved submission');
    const request = value['request'];
    if (
      typeof request['submissionId'] !== 'string' ||
      !uuid.test(request['submissionId']) ||
      ![
        'trainingProjectId',
        'manifestArtifactDigest',
        'datasetDefinitionId',
        'target',
      ].every((field) => typeof request[field] === 'string') ||
      !Number.isSafeInteger(request['gpuCount']) ||
      !object(request['configuration']) ||
      (value['acceptedRunId'] !== undefined &&
        (typeof value['acceptedRunId'] !== 'string' ||
          !uuid.test(value['acceptedRunId']))) ||
      (value['rejected'] !== undefined && value['rejected'] !== true)
    )
      throw new Error('Unreadable saved submission');
    return value as unknown as SubmissionJournal;
  }

  freeze(request: CreateRun): Promise<SubmissionJournal> {
    return this.lock(() => {
      const previous = this.read();
      if (previous) return previous;
      const saved: SubmissionJournal = { version: 1, request };
      this.write(saved);
      return saved;
    });
  }

  settle(
    submissionId: string,
    result: { acceptedRunId: string } | { rejected: true },
  ): Promise<void> {
    return this.lock(() => {
      const saved = this.read();
      if (saved?.request.submissionId === submissionId)
        this.write({ ...saved, ...result });
    });
  }

  clearSettled(): Promise<void> {
    return this.lock(() => {
      const saved = this.read();
      if (saved && !saved.acceptedRunId && !saved.rejected)
        throw new Error('Submission acceptance is still uncertain');
      this.storage.removeItem(key);
    });
  }

  savedCancellation(runId: string): string | undefined {
    const value = this.storage.getItem(`skywright.cancel.v1.${runId}`);
    if (value !== null && !uuid.test(value))
      throw new Error('Unreadable saved cancellation');
    return value ?? undefined;
  }

  cancellation(runId: string, knownCommandId?: string): Promise<string> {
    return this.lock(() => {
      if (
        !uuid.test(runId) ||
        (knownCommandId !== undefined && !uuid.test(knownCommandId))
      )
        throw new Error('Invalid cancellation identity');
      const cancelKey = `skywright.cancel.v1.${runId}`;
      const saved = this.storage.getItem(cancelKey);
      if (saved !== null && !uuid.test(saved))
        throw new Error('Unreadable saved cancellation');
      const identity = knownCommandId ?? saved ?? crypto.randomUUID();
      this.storage.setItem(cancelKey, identity);
      if (this.storage.getItem(cancelKey) !== identity)
        throw new Error('Cancellation identity was not saved');
      return identity;
    });
  }

  private write(value: SubmissionJournal) {
    const encoded = JSON.stringify(value);
    if (new TextEncoder().encode(encoded).length > 1024 * 1024)
      throw new Error('Saved request exceeds 1 MiB');
    this.storage.setItem(key, encoded);
    if (this.storage.getItem(key) !== encoded)
      throw new Error('Submission identity was not saved');
  }

  private lock<T>(operation: () => T): Promise<T> {
    if (!navigator.locks)
      return Promise.reject(
        new Error('Browser request locking is unavailable'),
      );
    return navigator.locks.request(key, operation);
  }
}

export const RUN_REQUEST_JOURNAL = new InjectionToken<RunRequestJournal>(
  'RUN_REQUEST_JOURNAL',
  {
    providedIn: 'root',
    factory: () => new RunRequestJournal(),
  },
);
