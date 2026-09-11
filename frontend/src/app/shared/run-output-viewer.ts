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
import {
  readOutputs,
  type OutputKind,
  type OutputPage,
} from '../api/run-output.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from './request-failure';

@Component({
  selector: 'sky-run-output-viewer',
  imports: [RequestFailure],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h3>Produced outputs</h3>
    <button type="button" [disabled]="loading()" (click)="choose('artifact')">
      Artifacts
    </button>
    <button type="button" [disabled]="loading()" (click)="choose('sample')">
      Samples
    </button>
    <button type="button" [disabled]="loading()" (click)="load()">
      Refresh outputs
    </button>
    @if (loading()) {
      <p role="status">Reading outputs…</p>
    }
    @if (failure(); as failure) {
      <sky-request-failure [failure]="failure" />
    }
    @if (page(); as page) {
      <p>
        {{ kind() === 'artifact' ? 'Artifacts' : 'Samples' }}. Downloads support
        outputs up to 8 MiB.
      </p>
      @for (item of page.items; track item.downloadUrl) {
        <p>
          {{ item.name }} · Step {{ item.step }} · {{ item.sizeBytes }} bytes
          @if (item.sizeBytes <= 8388608) {
            <a [href]="item.downloadUrl" [attr.download]="item.name"
              >Download {{ item.name }}</a
            >
          } @else {
            <span>Exceeds download limit</span>
          }
        </p>
      } @empty {
        <p>No outputs on this page.</p>
      }
      @if (page.nextCursor) {
        <button
          type="button"
          [disabled]="loading()"
          (click)="load(page.nextCursor)"
        >
          Next outputs
        </button>
      }
    }
  `,
})
export class RunOutputViewer {
  readonly runId = input.required<string>();
  protected readonly kind = signal<OutputKind>('artifact');
  protected readonly page = signal<OutputPage | undefined>(undefined);
  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  private readonly errors = inject(ErrorHandler);
  private request?: AbortController;

  constructor() {
    effect(() => {
      const id = this.runId();
      untracked(() => void this.load(undefined, id));
    });
    inject(DestroyRef).onDestroy(() => this.request?.abort());
  }

  protected choose(kind: OutputKind) {
    this.kind.set(kind);
    void this.load();
  }

  protected async load(cursor?: string, id = this.runId()) {
    this.request?.abort();
    const request = (this.request = new AbortController());
    this.loading.set(true);
    this.page.set(undefined);
    this.failure.set(undefined);
    try {
      const page = await readOutputs(
        id,
        this.kind(),
        cursor,
        AbortSignal.any([request.signal, AbortSignal.timeout(15_000)]),
      );
      if (!request.signal.aborted) this.page.set(page);
    } catch (failure) {
      if (request.signal.aborted) return;
      if (failure instanceof ApiRequestFailure)
        this.failure.set(failure.outcome);
      else this.errors.handleError(failure);
    } finally {
      if (!request.signal.aborted) this.loading.set(false);
    }
  }
}
