import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RUN_API } from '../api/run.api';
import { RunEvidence } from './run-evidence';
import { observedRun, progress } from '../../../tests/fixtures/run';

describe('Run evidence', () => {
  async function render(run = observedRun()) {
    await TestBed.configureTestingModule({
      imports: [RunEvidence],
      providers: [
        provideRouter([]),
        {
          provide: RUN_API,
          useValue: {
            progress: () => Promise.resolve(progress()),
            lineage: (id: string) =>
              Promise.resolve({
                runId: id,
                availability: 'unavailable',
                observedAt: '2026-09-07T14:00:00Z',
                predecessorRunId: null,
                checkpointReference: null,
                seedVerifiedAt: null,
              }),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunEvidence);
    fixture.componentRef.setInput('run', run);
    fixture.componentRef.setInput('detail', true);
    fixture.detectChanges();
    await fixture.whenStable();
    await vi.waitFor(() =>
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Committed Step 12',
      ),
    );
    return fixture;
  }
  it('keeps durable progress separate and omits percentage without a target', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Committed Step 12');
    expect(root.textContent).toContain('Latest Durable Safe Point: 10');
    expect(root.textContent).toContain('2 committed Steps beyond');
    expect(root.textContent).not.toContain('%');
    expect(root.textContent).toContain('Execution Attempts2');
    expect(root.textContent).toContain('Logs unavailable');
    expect(root.textContent).toContain('Attention Items unavailable');
  });
  it('renders the server unavailable state and last-seen side channel separately', async () => {
    const run = observedRun();
    if (!run.lifecycle) throw new Error('fixture');
    run.lifecycle.state = null;
    run.lifecycle.sourceAvailability = 'unavailable';
    run.lifecycle.lastSeen = {
      state: 'running',
      observedAt: '2026-09-07T10:00:00Z',
      ageMillis: 600_000,
    };
    const fixture = await render(run);
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Observed lifecycle: unavailable');
    expect(root.textContent).toContain('Last seen running');
    expect(root.textContent).toContain('This is not a current state.');
    expect(root.textContent).toContain('tiny-training');
  });
  it('never derives cancellation from an accepted control decision', async () => {
    const run = observedRun();
    if (!run.lifecycle) throw new Error('fixture');
    run.lifecycle.controlDecisions = [
      {
        id: run.submissionId,
        kind: 'CANCELLATION_REQUEST',
        decidedAt: run.acceptedAt,
        dispatchPrevented: false,
      },
    ];
    const fixture = await render(run);
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('CANCELLATION_REQUEST');
    expect(root.textContent).toContain('Observed lifecycle: running');
    expect(root.textContent).not.toContain('Observed lifecycle: cancelled');
    fixture.componentRef.setInput('historical', true);
    fixture.detectChanges();
    expect(root.textContent).toContain('Current lifecycle unavailable');
    expect(root.textContent).toContain('Last observed lifecycle: running');
  });
});
