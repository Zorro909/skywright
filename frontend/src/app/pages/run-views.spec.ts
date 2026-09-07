import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RUN_API, type Run, type RunPage } from '../api/run.api';
import { ApiRequestFailure } from '../api/api-failure';
import { OverviewPage } from './overview.page';
import { RunDetailPage } from './run-detail.page';
import { observedRun, progress, runId } from '../../../tests/fixtures/run';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

describe('Run views', () => {
  it('loads a bounded page, filters locally and follows only the returned cursor', async () => {
    const page = vi
      .fn()
      .mockResolvedValue({ items: [observedRun()], nextCursor: runId });
    await TestBed.configureTestingModule({
      imports: [OverviewPage],
      providers: [
        provideRouter([]),
        {
          provide: RUN_API,
          useValue: { page, progress: () => Promise.resolve(progress()) },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const filter = root.querySelector('input');
    if (!filter) throw new Error('filter');
    filter.value = 'different-project';
    filter.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.textContent).toContain('No Runs on this page match');
    filter.value = '';
    filter.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    page.mockResolvedValueOnce({ items: [], nextCursor: null });
    Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Next page'))
      ?.click();
    await fixture.whenStable();
    expect(page.mock.calls[1]?.[0]).toBe(runId);
    expect(page).toHaveBeenCalledTimes(2);
  });
  it('shows loading and empty results without manufacturing Runs', async () => {
    const pending = deferred<RunPage>();
    await TestBed.configureTestingModule({
      imports: [OverviewPage],
      providers: [
        provideRouter([]),
        { provide: RUN_API, useValue: { page: () => pending.promise } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Reading Runs');
    pending.resolve({ items: [] });
    await fixture.whenStable();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('No Runs have been accepted'),
    );
  });
  it('keeps a failed refresh historical and leaves progress independently readable', async () => {
    const get = vi.fn().mockResolvedValue(observedRun());
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'runs/:runId', component: RunDetailPage }]),
        {
          provide: RUN_API,
          useValue: { get, progress: () => Promise.resolve(progress()) },
        },
      ],
    }).compileComponents();
    const harness = await RouterTestingHarness.create(`/runs/${runId}`);
    await harness.fixture.whenStable();
    get.mockRejectedValueOnce(new ApiRequestFailure({ kind: 'network' }));
    harness.routeNativeElement?.querySelector('button')?.click();
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement?.textContent).toContain(
      'Run read unavailable',
    );
    expect(harness.routeNativeElement?.textContent).toContain(
      'Last observed lifecycle: running',
    );
    expect(harness.routeNativeElement?.textContent).toContain(
      'Current lifecycle unavailable',
    );
    expect(harness.routeNativeElement?.textContent).toContain(
      'Committed Step 12',
    );
  });
  it('aborts navigation requests and ignores a late result for the previous Run', async () => {
    const old = deferred<Run>();
    let oldSignal: AbortSignal | undefined;
    const nextId = '00000000-0000-4000-8000-000000000084';
    const next = { ...observedRun(), runId: nextId };
    const get = vi.fn((id: string, signal: AbortSignal) => {
      if (id === runId) {
        oldSignal = signal;
        return old.promise;
      }
      return Promise.resolve(next);
    });
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'runs/:runId', component: RunDetailPage }]),
        {
          provide: RUN_API,
          useValue: { get, progress: () => Promise.resolve(progress()) },
        },
      ],
    }).compileComponents();
    const harness = await RouterTestingHarness.create(`/runs/${runId}`);
    await TestBed.inject(Router).navigateByUrl(`/runs/${nextId}`);
    await harness.fixture.whenStable();
    expect(oldSignal?.aborted).toBe(true);
    old.resolve(observedRun());
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement?.textContent).toContain(nextId);
    expect(harness.routeNativeElement?.textContent).not.toContain(runId);
  });
});
