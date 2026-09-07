import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { RUN_API, isLifecycle, type Run, type RunPage } from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from '../shared/request-failure';
import { RunEvidence, definitionText } from '../shared/run-evidence';

@Component({
  selector: 'sky-overview-page',
  imports: [RouterLink, RequestFailure, RunEvidence],
  template: `
    <section aria-labelledby="overview-heading">
      <p class="eyebrow">Runs</p>
      <h2 id="overview-heading">Overview</h2>
      <p>Inspect accepted Runs and their source-backed observations.</p>
      <a routerLink="/runs/new">Create Run</a>
      <div class="controls">
        <label
          >Filter this page
          <input
            type="search"
            [value]="filter()"
            (input)="filter.set($event.target.value)"
            placeholder="Run, project or target"
        /></label>
        <label
          >Observed lifecycle on this page
          <select
            #stateFilter
            [value]="state()"
            (change)="state.set(stateFilter.value)"
          >
            <option value="">All observations</option>
            @for (state of states; track state) {
              <option [value]="state">{{ state }}</option>
            }
          </select></label
        >
        <label
          >Sort this page
          <select
            #sortOrder
            [value]="sort()"
            (change)="sort.set(sortOrder.value)"
          >
            <option value="newest">Newest accepted first</option>
            <option value="oldest">Oldest accepted first</option>
            <option value="project">Project</option>
          </select></label
        >
        <button type="button" [disabled]="loading()" (click)="load()">
          Refresh Runs
        </button>
      </div>
      <p class="muted">
        Each page contains at most 10 Runs. Filters and sorting apply to this
        page; use Next page to inspect the rest.
      </p>
      @if (loading()) {
        <p role="status">Reading Runs and lifecycle evidence…</p>
      }
      @if (failure(); as failure) {
        <h3>Run list unavailable</h3>
        <sky-request-failure [failure]="failure" />
      }
      @if (page(); as page) {
        @if (!page.items.length) {
          <p>
            {{
              after()
                ? 'No Runs were returned on this page.'
                : 'No Runs have been accepted.'
            }}
          </p>
        } @else if (!visible().length) {
          <p>No Runs on this page match these filters.</p>
        }
        @for (run of visible(); track run.runId) {
          <article class="run-card" [attr.aria-label]="'Run ' + run.runId">
            <h3>
              <a [routerLink]="['/runs', run.runId]">Run {{ run.runId }}</a>
            </h3>
            <sky-run-evidence
              [run]="run"
              [historical]="loading() || !!failure()"
            />
          </article>
        }
        <nav class="controls" aria-label="Run pages">
          <button
            type="button"
            [disabled]="loading() || !after()"
            (click)="first()"
          >
            First page
          </button>
          <button
            type="button"
            [disabled]="loading() || !page.nextCursor"
            (click)="next()"
          >
            Next page
          </button>
        </nav>
      }
    </section>
  `,
  styleUrl: './run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverviewPage {
  private readonly api = inject(RUN_API);
  private readonly errors = inject(ErrorHandler);
  private request?: AbortController;
  protected readonly page = signal<RunPage | undefined>(undefined);
  protected readonly after = signal<string | undefined>(undefined);
  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  protected readonly filter = signal('');
  protected readonly state = signal('');
  protected readonly sort = signal('newest');
  protected readonly states = [
    'waiting',
    'running',
    'interrupted',
    'finished',
    'failed',
    'cancelled',
    'unavailable',
  ];
  protected readonly visible = computed(() => {
    const filter = this.filter().trim().toLowerCase();
    const project = (run: Run) =>
      definitionText(run, 'trainingProjectVersion', 'projectIdentity');
    return (this.page()?.items ?? [])
      .filter(
        (run) =>
          [
            run.runId,
            project(run),
            definitionText(run, 'targetRequest', 'target'),
          ].some((value) => value.toLowerCase().includes(filter)) &&
          (!this.state() ||
            (isLifecycle(run.lifecycle)
              ? (run.lifecycle.state ?? 'unavailable')
              : 'unavailable') === this.state()),
      )
      .sort((a, b) =>
        this.sort() === 'project'
          ? project(a).localeCompare(project(b))
          : (Date.parse(a.acceptedAt) - Date.parse(b.acceptedAt)) *
            (this.sort() === 'oldest' ? 1 : -1),
      );
  });
  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.abort());
    void this.load();
  }
  protected async load() {
    this.request?.abort();
    const request = (this.request = new AbortController());
    this.loading.set(true);
    this.failure.set(undefined);
    try {
      const page = await this.api.page(
        this.after(),
        AbortSignal.any([request.signal, AbortSignal.timeout(70_000)]),
      );
      if (!request.signal.aborted) this.page.set(page);
    } catch (error) {
      if (request.signal.aborted) return;
      if (error instanceof ApiRequestFailure) this.failure.set(error.outcome);
      else this.errors.handleError(error);
    } finally {
      if (!request.signal.aborted) this.loading.set(false);
    }
  }
  protected first() {
    this.after.set(undefined);
    void this.load();
  }
  protected next() {
    const cursor = this.page()?.nextCursor;
    if (cursor) {
      this.after.set(cursor);
      void this.load();
    }
  }
}
