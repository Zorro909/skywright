import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ManagedRunPage } from './managed-run.page';
import { observedRun, runId } from '../../../tests/fixtures/run';

describe('Managed Run workflow', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('saves the workload choice before sending and retries the same intent after reload', async () => {
    localStorage.clear();
    const requests: Record<string, unknown>[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = input as Request;
      if (request.url.endsWith('/managed-run-form'))
        return Response.json({
          ready: requests.length === 0,
          observedAt: '2026-09-11T12:00:00Z',
          workloads: [
            { id: 'demonstration', displayName: 'Short CIFAR-10 proof' },
          ],
          targets: [
            {
              id: 'local-amd',
              displayName: 'Local AMD',
              purchaseMode: 'local',
              gpuModel: 'rx7800xt',
              gpuCount: 2,
              ready: true,
              checks: [],
            },
          ],
          checks: [],
        });
      const body = (await request.json()) as Record<string, unknown>;
      requests.push(body);
      if (requests.length === 1) throw new TypeError('lost response');
      return Response.json({
        ...observedRun(),
        submissionId: body['submissionId'],
      });
    });
    await TestBed.configureTestingModule({
      imports: [ManagedRunPage],
      providers: [provideRouter([])],
    }).compileComponents();
    let fixture = TestBed.createComponent(ManagedRunPage);
    fixture.detectChanges();
    let root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Short CIFAR-10 proof'),
    );
    const button = () =>
      Array.from(root.querySelectorAll('button')).find((b) =>
        /Create Run|Retry submission/.test(b.textContent ?? ''),
      )!;
    button().click();
    await vi.waitFor(() => expect(requests).toHaveLength(1));
    expect(Object.keys(requests[0]!).sort()).toEqual([
      'submissionId',
      'target',
      'workload',
    ]);
    fixture.destroy();
    fixture = TestBed.createComponent(ManagedRunPage);
    fixture.detectChanges();
    root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(button().textContent).toContain('Retry submission'),
    );
    button().click();
    await vi.waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toEqual(requests[0]);
    await vi.waitFor(() =>
      expect(root.querySelector(`a[href="/runs/${runId}"]`)).not.toBeNull(),
    );
    fixture.destroy();
  });

  it('shows Vast blockers and cannot submit that target when the local target is ready', async () => {
    const submitted: unknown[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = input as Request;
      if (!request.url.endsWith('/managed-run-form')) {
        submitted.push(await request.json());
        return Response.json(observedRun());
      }
      return Response.json({
        ready: true,
        observedAt: new Date().toISOString(),
        workloads: [
          { id: 'demonstration', displayName: 'Short CIFAR-10 proof' },
        ],
        targets: [
          {
            id: 'local-amd',
            displayName: 'Local AMD',
            purchaseMode: 'local',
            gpuModel: 'rx7800xt',
            gpuCount: 2,
            ready: true,
            checks: [],
          },
          {
            id: 'vast/on-demand',
            displayName: 'Vast.ai on-demand',
            purchaseMode: 'on-demand',
            ready: false,
            checks: [
              {
                component: 'launchPrice',
                ready: false,
                code: 'VAST_LAUNCH_PRICE_UNPROVEN',
                detail: 'The actual rental price is unproven.',
              },
            ],
          },
        ],
        checks: [],
      });
    });
    await TestBed.configureTestingModule({
      imports: [ManagedRunPage],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(ManagedRunPage);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Short CIFAR-10 proof'),
    );
    const target = root.querySelector<HTMLSelectElement>('#managed-target')!;
    const create = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Create Run',
    )!;
    expect(create.disabled).toBe(false);
    target.value = 'vast/on-demand';
    target.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(root.textContent).toContain('The actual rental price is unproven.');
    expect(create.disabled).toBe(true);
    create.click();
    expect(submitted).toHaveLength(0);
    target.value = 'local-amd';
    target.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(create.disabled).toBe(false);
    expect(root.textContent).not.toContain(
      'The actual rental price is unproven.',
    );
    fixture.destroy();
  });
});
