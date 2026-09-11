import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { managedRunApi, type ManagedRunForm } from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import {
  RUN_REQUEST_JOURNAL,
  type ManagedSubmissionJournal,
} from '../shared/run-request-journal';
import { RequestFailure } from '../shared/request-failure';

@Component({
  selector: 'sky-managed-run-page',
  imports: [RouterLink, RequestFailure],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './run-views.css',
  template: `
    <a routerLink="/">Back to overview</a>
    <h2>Create Run</h2>
    <p>Run the installed demonstration on one local GPU.</p>
    @if (storageFailure()) {
      <p role="alert">
        The saved submission needs attention before another Run can start.
      </p>
      <a routerLink="/runs/new/advanced">Open the previous Run form</a>
    }
    @if (saved()?.acceptedRunId; as runId) {
      <p role="status">Run accepted.</p>
      <a [routerLink]="['/runs', runId]">Observe Run and inspect outputs</a>
      <button type="button" (click)="another()">Create another Run</button>
    } @else {
      <button type="button" [disabled]="loading()" (click)="load()">
        Refresh readiness
      </button>
      @if (loading()) {
        <p role="status">Checking workload and target readiness…</p>
      }
      @if (form(); as form) {
        <label for="managed-workload">Workload</label>
        <select id="managed-workload" [disabled]="!!saved() || busy()">
          @for (workload of form.workloads; track workload.id) {
            <option [value]="workload.id">{{ workload.displayName }}</option>
          }
        </select>
        <label for="managed-target">Target</label>
        <select
          id="managed-target"
          [value]="target()"
          [disabled]="!!saved() || busy()"
          (change)="target.set($any($event.target).value)"
        >
          @for (target of form.targets; track target.id) {
            <option [value]="target.id">
              {{ target.gpuModel }} · {{ target.gpuCount }} installed GPUs
            </option>
          }
        </select>
        <h3>Readiness</h3>
        <ul>
          @for (check of form.checks; track check.component) {
            <li>
              {{ label(check.component) }}:
              {{ check.ready ? 'Ready' : check.detail }}
            </li>
          }
        </ul>
        <p>
          Readiness is a point-in-time check. Submission checks the
          prerequisites again.
        </p>
      }
      @if (saved(); as saved) {
        @if (saved.rejected) {
          <p>The request was rejected before acceptance.</p>
          <button type="button" (click)="another()">Create another Run</button>
        } @else {
          <p>
            Saved choice: demonstration on {{ saved.request.target }}. Retrying
            preserves this submission.
          </p>
        }
      }
      @if (!saved()?.rejected) {
        <button
          type="button"
          [disabled]="
            busy() ||
            storageFailure() ||
            (!saved() && (loading() || !form()?.ready))
          "
          (click)="submit()"
        >
          {{ saved() ? 'Retry submission' : 'Create Run' }}
        </button>
      }
    }
    @if (failure(); as failure) {
      <sky-request-failure [failure]="failure" />
    }
    <p><a routerLink="/runs/new/advanced">Advanced Run configuration</a></p>
  `,
})
export class ManagedRunPage {
  private readonly journal = inject(RUN_REQUEST_JOURNAL);
  private readonly errors = inject(ErrorHandler);
  private readRequest?: AbortController;
  private submissionRequest?: AbortController;
  protected readonly form = signal<ManagedRunForm | undefined>(undefined);
  protected readonly target = signal('');
  protected readonly saved = signal<ManagedSubmissionJournal | undefined>(
    undefined,
  );
  protected readonly storageFailure = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);

  constructor() {
    try {
      this.saved.set(this.journal.readManaged());
      this.target.set(this.saved()?.request.target ?? '');
    } catch {
      this.storageFailure.set(true);
    }
    void this.load();
    inject(DestroyRef).onDestroy(() => {
      this.readRequest?.abort();
      this.submissionRequest?.abort();
    });
  }

  protected async load() {
    this.readRequest?.abort();
    const request = (this.readRequest = new AbortController());
    this.loading.set(true);
    this.form.set(undefined);
    this.failure.set(undefined);
    try {
      const form = await managedRunApi.form(
        AbortSignal.any([request.signal, AbortSignal.timeout(20_000)]),
      );
      if (request.signal.aborted) return;
      this.form.set(form);
      if (!this.saved()) this.target.set(form.targets[0]?.id ?? '');
    } catch (failure) {
      if (request.signal.aborted) return;
      if (failure instanceof ApiRequestFailure)
        this.failure.set(failure.outcome);
      else this.errors.handleError(failure);
    } finally {
      if (!request.signal.aborted) this.loading.set(false);
    }
  }

  protected async submit() {
    if (
      this.busy() ||
      this.storageFailure() ||
      (!this.saved() && !this.form()?.ready)
    )
      return;
    this.busy.set(true);
    this.failure.set(undefined);
    const request = (this.submissionRequest = new AbortController());
    let saved = this.saved();
    try {
      if (!saved) {
        try {
          saved = await this.journal.freezeManaged({
            submissionId: crypto.randomUUID(),
            workload: 'demonstration',
            target: this.target(),
          });
          this.saved.set(saved);
        } catch {
          this.storageFailure.set(true);
          return;
        }
      }
      if (saved.rejected || saved.acceptedRunId) return;
      const accepted = await managedRunApi.create(
        saved.request,
        AbortSignal.any([request.signal, AbortSignal.timeout(40_000)]),
      );
      if (request.signal.aborted) return;
      this.saved.set({ ...saved, acceptedRunId: accepted.runId });
      try {
        await this.journal.settle(saved.request.submissionId, {
          acceptedRunId: accepted.runId,
        });
      } catch {
        this.storageFailure.set(true);
      }
    } catch (failure) {
      if (request.signal.aborted) return;
      if (failure instanceof ApiRequestFailure) {
        this.failure.set(failure.outcome);
        if (
          saved &&
          failure.outcome.kind === 'problem' &&
          [400, 404, 422].includes(failure.outcome.response.status)
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
      } else this.errors.handleError(failure);
    } finally {
      if (!request.signal.aborted) this.busy.set(false);
    }
  }

  protected async another() {
    try {
      await this.journal.clearSettled();
      this.saved.set(undefined);
      this.storageFailure.set(false);
      await this.load();
    } catch {
      this.storageFailure.set(true);
    }
  }

  protected label(component: string): string {
    return (
      (
        {
          target: 'Target',
          workload: 'Workload',
          projectVersion: 'Project Version',
          dataset: 'Dataset',
          storage: 'Storage',
          credentials: 'Credentials',
          registry: 'Image pull',
          writerAuthority: 'Recovery',
          gpu: 'GPU capacity',
          controlPath: 'Control plane',
          preflight: 'Readiness',
        } as Record<string, string>
      )[component] ?? component
    );
  }
}
