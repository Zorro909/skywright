import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { RUN_API, type Run } from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from '../shared/request-failure';
import { RunCancellation } from '../shared/run-cancellation';
import { RunEvidence } from '../shared/run-evidence';
import { RunLogViewer } from '../shared/run-log-viewer';

@Component({
  selector: 'sky-run-detail-page',
  imports: [
    RouterLink,
    RequestFailure,
    RunEvidence,
    RunCancellation,
    RunLogViewer,
  ],
  template: `
    <a routerLink="/">Back to overview</a>
    <section aria-labelledby="run-heading">
      <p class="eyebrow">Run detail</p>
      <h2 id="run-heading">Run</h2>
      <p>
        <code>{{ id() }}</code>
      </p>
      <button type="button" [disabled]="loading()" (click)="load()">
        Refresh Run
      </button>
      @if (loading()) {
        <p role="status">Reading Run and lifecycle evidence…</p>
      }
      @if (failure(); as failure) {
        <h3>Run read unavailable</h3>
        <sky-request-failure [failure]="failure" />
      }
      @if (run(); as run) {
        <sky-run-cancellation
          [run]="run"
          [historical]="loading() || !!failure()"
          (refreshRun)="load()"
        />
        <h3>Run observations</h3>
        <button type="button" (click)="showLogs.set(!showLogs())">
          {{ showLogs() ? 'Close archived logs' : 'View archived logs' }}
        </button>
        @if (showLogs()) {
          <sky-run-log-viewer [runId]="run.runId" />
        }
        <sky-run-evidence
          [run]="run"
          [detail]="true"
          [historical]="loading() || !!failure()"
        />
      }
    </section>
  `,
  styleUrl: './run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunDetailPage {
  private readonly api = inject(RUN_API);
  private readonly errors = inject(ErrorHandler);
  private request?: AbortController;
  protected readonly id = signal('');
  protected readonly run = signal<Run | undefined>(undefined);
  protected readonly showLogs = signal(false);
  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  constructor() {
    const subscription = inject(ActivatedRoute).paramMap.subscribe((params) => {
      this.id.set(params.get('runId') ?? '');
      this.run.set(undefined);
      void this.load();
    });
    inject(DestroyRef).onDestroy(() => {
      subscription.unsubscribe();
      this.request?.abort();
    });
  }
  protected async load() {
    this.request?.abort();
    const request = (this.request = new AbortController());
    this.loading.set(true);
    this.failure.set(undefined);
    try {
      const run = await this.api.get(
        this.id(),
        AbortSignal.any([request.signal, AbortSignal.timeout(40_000)]),
      );
      if (!request.signal.aborted) this.run.set(run);
    } catch (error) {
      if (request.signal.aborted) return;
      if (error instanceof ApiRequestFailure) this.failure.set(error.outcome);
      else this.errors.handleError(error);
    } finally {
      if (!request.signal.aborted) this.loading.set(false);
    }
  }
}
