import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  inject,
  signal,
} from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  RUN_API,
  object,
  type Run,
  type LocalTarget,
  type Project,
  type Versions,
  type DatasetPage,
} from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from '../shared/request-failure';
import {
  RUN_REQUEST_JOURNAL,
  type SubmissionJournal,
} from '../shared/run-request-journal';

@Component({
  selector: 'sky-new-run-page',
  imports: [RouterLink, DatePipe, JsonPipe, RequestFailure],
  templateUrl: './new-run.page.html',
  styleUrl: './run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewRunPage {
  private readonly api = inject(RUN_API);
  private readonly journal = inject(RUN_REQUEST_JOURNAL);
  private readonly errors = inject(ErrorHandler);
  private readonly requests = new Map<string, AbortController>();
  protected readonly saved = signal<SubmissionJournal | undefined>(undefined);
  protected readonly accepted = signal<Run | undefined>(undefined);
  protected readonly storageFailure = signal(false);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  protected readonly catalogFailures = signal<Record<string, ApiFailure>>({});
  protected readonly pending = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly validation = signal('');
  protected readonly target = signal<LocalTarget | undefined>(undefined);
  protected readonly projects = signal<Project[]>([]);
  protected readonly versions = signal<Versions | undefined>(undefined);
  protected readonly datasets = signal<DatasetPage | undefined>(undefined);
  protected readonly projectId = signal('');
  protected readonly manifest = signal('');
  protected readonly datasetId = signal('');
  protected readonly gpuCount = signal('1');
  protected readonly configuration = signal('{}');
  protected readonly executionStorage = signal('');
  protected readonly maximumDebt = signal('');
  protected readonly predecessor = signal('');
  protected readonly checkpoint = signal('');

  constructor() {
    try {
      this.restore(this.journal.read());
    } catch {
      this.storageFailure.set(true);
    }
    void this.refreshCatalogues();
    inject(DestroyRef).onDestroy(() =>
      this.requests.forEach((request) => request.abort()),
    );
  }

  protected refreshCatalogues() {
    return Promise.allSettled([
      this.readCatalogue(
        'Local target',
        (signal) => this.api.target(signal),
        (value) => this.target.set(value),
      ),
      this.readCatalogue(
        'Projects',
        (signal) => this.api.projects(signal),
        (value) => this.projects.set(value),
      ),
      this.loadDatasets(),
      ...(this.projectId() ? [this.loadVersions(this.projectId())] : []),
    ]);
  }

  protected async chooseProject(id: string) {
    this.projectId.set(id);
    this.manifest.set('');
    this.versions.set(undefined);
    await this.loadVersions(id);
  }

  private loadVersions(id: string) {
    return this.readCatalogue(
      'Project Versions',
      (signal) => this.api.versions(id, signal),
      (value) => this.versions.set(value),
    );
  }

  protected loadDatasets(cursor?: string) {
    return this.readCatalogue(
      'Datasets',
      (signal) => this.api.datasets(cursor, signal),
      (value) => this.datasets.set(value),
    );
  }

  private async readCatalogue<T>(
    name: string,
    read: (signal: AbortSignal) => Promise<T>,
    accept: (value: T) => void,
  ) {
    this.requests.get(name)?.abort();
    const request = new AbortController();
    this.requests.set(name, request);
    this.pending.update((items) => [
      ...items.filter((item) => item !== name),
      name,
    ]);
    try {
      const value = await read(
        AbortSignal.any([request.signal, AbortSignal.timeout(15_000)]),
      );
      if (request.signal.aborted) return;
      accept(value);
      this.catalogFailures.update((items) =>
        Object.fromEntries(
          Object.entries(items).filter(([key]) => key !== name),
        ),
      );
    } catch (error) {
      if (request.signal.aborted) return;
      if (error instanceof ApiRequestFailure)
        this.catalogFailures.update((items) => ({
          ...items,
          [name]: error.outcome,
        }));
      else this.errors.handleError(error);
    } finally {
      if (!request.signal.aborted)
        this.pending.update((items) => items.filter((item) => item !== name));
    }
  }

  protected catalogueErrors() {
    return Object.entries(this.catalogFailures());
  }

  protected async submit(event?: Event) {
    event?.preventDefault();
    if (this.busy() || this.storageFailure()) return;
    this.busy.set(true);
    this.failure.set(undefined);
    this.validation.set('');
    let saved = this.saved();
    const controller = new AbortController();
    this.requests.set('Submission', controller);
    try {
      if (!saved) {
        let configuration: unknown;
        try {
          configuration = JSON.parse(this.configuration());
        } catch {
          this.validation.set('Configuration must be a JSON object.');
          return;
        }
        if (!object(configuration)) {
          this.validation.set('Configuration must be a JSON object.');
          return;
        }
        const target = this.target();
        if (
          !target?.submissionAvailable ||
          this.catalogFailures()['Local target'] ||
          this.pending().includes('Local target')
        ) {
          this.validation.set('Refresh the local target before submitting.');
          return;
        }
        try {
          saved = await this.journal.freeze({
            submissionId: crypto.randomUUID(),
            trainingProjectId: this.projectId(),
            manifestArtifactDigest: this.manifest(),
            datasetDefinitionId: this.datasetId(),
            target: target.identity,
            gpuCount: Number(this.gpuCount()),
            configuration,
            ...(this.predecessor() || this.checkpoint()
              ? {
                  checkpointSeed: {
                    predecessorRunId: this.predecessor(),
                    checkpointReference: this.checkpoint(),
                  },
                }
              : {}),
            ...(this.executionStorage()
              ? { executionStorageId: this.executionStorage() }
              : {}),
            ...(this.maximumDebt()
              ? { maximumRecoveryDebt: Number(this.maximumDebt()) }
              : {}),
          });
        } catch {
          this.storageFailure.set(true);
          return;
        }
        this.restore(saved);
      }
      if (saved.acceptedRunId || saved.rejected) return;
      const run = await this.api.create(
        saved.request,
        AbortSignal.any([controller.signal, AbortSignal.timeout(40_000)]),
      );
      this.accepted.set(run);
      this.saved.set({ ...saved, acceptedRunId: run.runId });
      try {
        await this.journal.settle(saved.request.submissionId, {
          acceptedRunId: run.runId,
        });
      } catch {
        this.storageFailure.set(true);
      }
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiRequestFailure) {
        this.failure.set(error.outcome);
        if (
          saved &&
          error.outcome.kind === 'problem' &&
          error.outcome.response.status === 422
        ) {
          this.saved.set({ ...saved, rejected: true });
          try {
            await this.journal.settle(saved.request.submissionId, {
              rejected: true,
            });
          } catch {
            this.storageFailure.set(true);
          }
        }
      } else this.errors.handleError(error);
    } finally {
      if (!controller.signal.aborted) this.busy.set(false);
    }
  }

  protected async another() {
    if (this.busy()) return;
    try {
      await this.journal.clearSettled();
      this.saved.set(undefined);
      this.accepted.set(undefined);
      this.failure.set(undefined);
      this.validation.set('');
    } catch {
      this.storageFailure.set(true);
    }
  }

  private restore(saved: SubmissionJournal | undefined) {
    this.saved.set(saved);
    if (!saved) return;
    this.predecessor.set(saved.request.checkpointSeed?.predecessorRunId ?? '');
    this.checkpoint.set(
      saved.request.checkpointSeed?.checkpointReference ?? '',
    );
    this.projectId.set(saved.request.trainingProjectId);
    this.manifest.set(saved.request.manifestArtifactDigest);
    this.datasetId.set(saved.request.datasetDefinitionId);
    this.gpuCount.set(String(saved.request.gpuCount));
    this.configuration.set(
      JSON.stringify(saved.request.configuration, null, 2),
    );
    this.executionStorage.set(saved.request.executionStorageId ?? '');
    this.maximumDebt.set(saved.request.maximumRecoveryDebt?.toString() ?? '');
  }
}
