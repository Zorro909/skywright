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
          targets: [{ id: 'local-amd', gpuModel: 'rx7800xt', gpuCount: 2 }],
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
});
