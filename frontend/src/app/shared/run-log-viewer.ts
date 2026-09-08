import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  runLogApi,
  type LogPage,
  type LogNavigation,
  type LogStream,
} from '../api/run-log.api';
import { ArchiveTerminal } from './archive-terminal';

@Component({
  selector: 'sky-run-log-viewer',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section aria-label="Archived logs">
      <h3>Archived logs</h3>
      <label
        >Stream
        <select [value]="stream()" (change)="choose($event)">
          <option value="task">Task and setup</option>
          <option value="controller">Controller</option>
        </select></label
      >
      <button type="button" (click)="tail()">Latest bounded tail</button>
      <button
        type="button"
        [disabled]="!first() || first() === '0'"
        (click)="older()"
      >
        Older bytes
      </button>
      @if (historical()) {
        <button
          type="button"
          [disabled]="
            !page()?.nextCursor || page()?.nextCursor === page()?.endCursor
          "
          (click)="next()"
        >
          Next bytes
        </button>
      }
      <button type="button" (click)="pause()" [disabled]="!following()">
        Pause follow
      </button>
      <button
        type="button"
        (click)="resume()"
        [disabled]="following() || historical() || finalized()"
      >
        Resume follow
      </button>
      <p role="status">{{ status() }}</p>
      @if (failure()) {
        <p role="alert">
          {{ failure() }} Captured bytes remain in the archive.
        </p>
      }
      @if (page(); as page) {
        <p>
          Archive: {{ page.archiveState }}. Source:
          {{ page.sourceAvailability }}. Last successful source read:
          {{ (page.lastSuccessfulFetch | date: 'medium') ?? 'not observed' }}.
        </p>
        @if (page.sourceReason) {
          <p>Source unavailable: {{ page.sourceReason }}</p>
        }
        <p>
          Completion: {{ page.completion }}
          @if (page.reason) {
            , {{ page.reason }}
          }
        </p>
        <p>
          Displayed window starts at byte {{ first() ?? 'unknown' }}. Next byte
          {{ acknowledged() ?? 'unknown' }}. Captured end
          {{ page.endCursor ?? 'unavailable' }}.
        </p>
      }
      <p>
        Task and controller have independent byte positions. This terminal
        retains up to 256 KiB and 1,000 scrollback rows. Earlier terminal state
        can be absent at a window boundary.
      </p>
      <div
        #terminal
        class="archive-terminal"
        aria-label="Read-only archived terminal output"
      ></div>
      @if (stream() === 'task') {
        <h4>Setup and Execution Attempts</h4>
        <button
          type="button"
          [disabled]="navigationBusy()"
          (click)="navigate()"
        >
          Refresh segments
        </button>
        @if (navigation()?.nextCursor; as cursor) {
          <button
            type="button"
            [disabled]="navigationBusy()"
            (click)="navigate(cursor)"
          >
            More segments
          </button>
        }
        @if (navigationFailure()) {
          <p>Navigation unavailable. Captured bytes remain readable.</p>
        }
        <ul>
          @for (segment of navigation()?.items ?? []; track $index) {
            <li>
              <button
                type="button"
                (click)="window({ cursor: segment.cursor })"
              >
                {{ segment.kind }} {{ segment.attemptId ?? '' }} at byte
                {{ segment.cursor }}
              </button>
              @if (segment.preparationCursor; as setup) {
                <button type="button" (click)="window({ cursor: setup })">
                  Preceding setup
                </button>
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .archive-terminal {
      margin-block: 1rem;
      max-width: 100%;
      overflow-x: auto;
      min-height: 24rem;
    }
    button,
    select {
      margin: 0.25rem;
    }
  `,
})
export class RunLogViewer implements AfterViewInit {
  readonly runId = input.required<string>();
  @ViewChild('terminal', { static: true })
  private host!: ElementRef<HTMLElement>;
  protected readonly stream = signal<LogStream>('task');
  protected readonly page = signal<LogPage | undefined>(undefined);
  protected readonly following = signal(false);
  protected readonly historical = signal(false);
  protected readonly finalized = signal(false);
  protected readonly status = signal('Opening archive…');
  protected readonly failure = signal('');
  protected readonly first = signal<string | undefined>(undefined);
  protected readonly acknowledged = signal<string | undefined>(undefined);
  protected readonly navigation = signal<LogNavigation | undefined>(undefined);
  protected readonly navigationBusy = signal(false);
  protected readonly navigationFailure = signal(false);
  private terminal: ArchiveTerminal | undefined;
  private request?: AbortController;
  private navigationRequest?: AbortController;
  private active: Promise<void> = Promise.resolve();
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.request?.abort();
      this.navigationRequest?.abort();
      this.terminal?.dispose();
    });
  }

  ngAfterViewInit() {
    void this.tail();
    void this.navigate();
  }

  protected choose(event: Event) {
    const selected = (event.target as HTMLSelectElement).value;
    this.request?.abort();
    this.terminal?.dispose();
    this.terminal = undefined;
    this.first.set(undefined);
    this.acknowledged.set(undefined);
    this.page.set(undefined);
    this.stream.set(selected === 'controller' ? 'controller' : 'task');
    void this.tail();
  }

  protected tail() {
    return this.start({}, true);
  }
  protected window(position: { cursor?: string; before?: string }) {
    return this.start(position, false);
  }
  protected older() {
    const before = this.first();
    if (before) void this.window({ before });
  }
  protected next() {
    const cursor = this.page()?.nextCursor;
    if (cursor) void this.window({ cursor });
  }
  protected pause() {
    this.request?.abort();
    this.following.set(false);
    this.status.set('Follow paused');
  }

  protected async resume() {
    if (this.following() || this.historical() || this.finalized()) return;
    this.request?.abort();
    const request = (this.request = new AbortController());
    await this.active.catch(() => undefined);
    if (request.signal.aborted || this.destroyed) return;
    this.active = this.follow(request);
  }

  private async start(
    position: { cursor?: string; before?: string },
    live: boolean,
  ) {
    this.request?.abort();
    const request = (this.request = new AbortController());
    await this.active.catch(() => undefined);
    if (request.signal.aborted || this.destroyed) return;
    this.failure.set('');
    this.historical.set(!live);
    this.finalized.set(false);
    this.following.set(live);
    this.status.set('Reading bounded archive window…');
    const work = async () => {
      try {
        const page = await runLogApi.page(
          this.runId(),
          this.stream(),
          position,
          AbortSignal.any([request.signal, AbortSignal.timeout(45_000)]),
        );
        if (request.signal.aborted) return;
        if (page.availability === 'available') {
          this.terminal?.dispose();
          this.terminal = new ArchiveTerminal(this.host.nativeElement);
          this.first.set(undefined);
          this.acknowledged.set(undefined);
        }
        await this.accept(page);
        if (live && !this.finalized() && !request.signal.aborted)
          await this.follow(request);
        else if (!request.signal.aborted) {
          this.following.set(false);
          this.status.set(
            live ? 'Archive finalized; follow ended' : 'Historical byte window',
          );
        }
      } catch (error) {
        if (!request.signal.aborted) this.failed(error);
      }
    };
    this.active = work();
    await this.active;
  }

  private async accept(page: LogPage) {
    this.page.set(page);
    if (page.availability !== 'available') {
      this.failure.set('Run Store archive unavailable.');
      return;
    }
    this.terminal ??= new ArchiveTerminal(this.host.nativeElement);
    await this.terminal?.append(page);
    this.first.set(this.terminal?.firstCursor);
    this.acknowledged.set(this.terminal?.cursor);
    this.failure.set('');
    this.finalized.set(
      page.archiveState === 'finalized' &&
        this.terminal?.cursor === page.endCursor,
    );
  }

  private async follow(request: AbortController) {
    this.following.set(true);
    while (!request.signal.aborted && !this.destroyed && !this.finalized()) {
      this.status.set('Following captured archive bytes');
      try {
        await runLogApi.follow(
          this.runId(),
          this.stream(),
          this.terminal?.cursor,
          AbortSignal.any([request.signal, AbortSignal.timeout(45_000)]),
          (page) => this.accept(page),
        );
      } catch (error) {
        if (!request.signal.aborted)
          this.failure.set(
            error instanceof Error
              ? error.message
              : 'Archive follow unavailable.',
          );
      }
      if (!request.signal.aborted && !this.finalized()) {
        this.status.set('Reconnecting from the last rendered byte');
        await new Promise<void>((resolve) => {
          const done = () => {
            clearTimeout(timer);
            request.signal.removeEventListener('abort', done);
            resolve();
          };
          const timer = setTimeout(done, 2000);
          request.signal.addEventListener('abort', done, { once: true });
        });
      }
    }
    if (!request.signal.aborted) {
      this.following.set(false);
      this.status.set('Archive finalized; follow ended');
    }
  }

  protected async navigate(cursor?: string) {
    this.navigationRequest?.abort();
    const request = (this.navigationRequest = new AbortController());
    this.navigationBusy.set(true);
    this.navigationFailure.set(false);
    try {
      const navigation = await runLogApi.navigation(
        this.runId(),
        cursor,
        AbortSignal.any([request.signal, AbortSignal.timeout(45_000)]),
      );
      if (!request.signal.aborted) this.navigation.set(navigation);
    } catch {
      if (!request.signal.aborted) this.navigationFailure.set(true);
    } finally {
      if (!request.signal.aborted) this.navigationBusy.set(false);
    }
  }

  private failed(error: unknown) {
    this.following.set(false);
    this.status.set('Archive read unavailable');
    this.failure.set(
      error instanceof Error ? error.message : 'Archive unavailable.',
    );
  }
}
