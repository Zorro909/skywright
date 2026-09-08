import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { RUN_API, type Lineage } from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from './request-failure';
import { ObservationAge } from './observation-age';

@Component({
  selector: 'sky-run-lineage',
  imports: [RouterLink, DatePipe, RequestFailure, ObservationAge],
  template: `
    <section aria-label="Run lineage">
      <h3>Lineage</h3>
      <button type="button" [disabled]="loading()" (click)="load()">
        Refresh lineage
      </button>
      @if (loading()) {
        <p role="status">Reading accepted lineage…</p>
      }
      @if (failure(); as failure) {
        <p>
          Current lineage read unavailable. Any retained relationship below is
          historical.
        </p>
        <sky-request-failure [failure]="failure" />
      }
      @if (lineage(); as lineage) {
        @if (lineage.availability === 'available') {
          @if (lineage.predecessorRunId; as predecessor) {
            <p>
              Predecessor Run:
              <a [routerLink]="['/runs', predecessor]">{{ predecessor }}</a>
            </p>
            <p>
              Exact seed checkpoint:
              <code>{{ lineage.checkpointReference }}</code>
            </p>
            <p>
              Child-owned seed verified
              {{ lineage.seedVerifiedAt | date: 'medium' }}. This Run's recovery
              reads its own copy.
            </p>
          } @else {
            <p>
              Root Run. The accepted lineage record confirms that no predecessor
              or seed checkpoint was supplied.
            </p>
          }
        } @else {
          <p>
            Lineage unavailable. No accepted relationship record is available;
            this does not establish a root Run.
          </p>
        }
        <sky-observation-age [at]="lineage.observedAt" />
      }
    </section>
  `,
  styleUrl: '../pages/run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunLineage {
  readonly runId = input.required<string>();
  private readonly api = inject(RUN_API);
  private readonly errors = inject(ErrorHandler);
  private request?: AbortController;
  protected readonly lineage = signal<Lineage | undefined>(undefined);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  protected readonly loading = signal(false);
  constructor() {
    effect(() => {
      const id = this.runId();
      untracked(() => {
        this.lineage.set(undefined);
        void this.load(id);
      });
    });
    inject(DestroyRef).onDestroy(() => this.request?.abort());
  }
  protected async load(id = this.runId()) {
    this.request?.abort();
    const request = (this.request = new AbortController());
    this.failure.set(undefined);
    this.loading.set(true);
    try {
      const value = await this.api.lineage(
        id,
        AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]),
      );
      if (!request.signal.aborted) this.lineage.set(value);
    } catch (error) {
      if (request.signal.aborted) return;
      if (error instanceof ApiRequestFailure) this.failure.set(error.outcome);
      else this.errors.handleError(error);
    } finally {
      if (!request.signal.aborted) this.loading.set(false);
    }
  }
}
