import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RUN_API } from '../api/run.api';
import { ApiRequestFailure } from '../api/api-failure';
import { RunLineage } from './run-lineage';
import { runId } from '../../../tests/fixtures/run';

const at = '2026-09-07T14:00:00Z';
const root = {
  runId,
  availability: 'available',
  observedAt: at,
  predecessorRunId: null,
  checkpointReference: null,
  seedVerifiedAt: null,
};

describe('Accepted Run lineage', () => {
  it('distinguishes affirmative roots from unavailable lineage', async () => {
    const lineage = vi.fn().mockResolvedValue(root);
    await TestBed.configureTestingModule({
      imports: [RunLineage],
      providers: [
        provideRouter([]),
        { provide: RUN_API, useValue: { lineage } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunLineage);
    fixture.componentRef.setInput('runId', runId);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(element.textContent).toContain('Root Run'));
    lineage.mockResolvedValue({ ...root, availability: 'unavailable' });
    element.querySelector('button')?.click();
    await fixture.whenStable();
    await vi.waitFor(() =>
      expect(element.textContent).toContain('Lineage unavailable'),
    );
    expect(element.textContent).not.toContain('Root Run.');
  });
  it('retains exact navigation as historical when the independent reader fails', async () => {
    const predecessor = '00000000-0000-4000-8000-000000000001';
    const reference = `skywright-checkpoint:v1:4:sha256:${'a'.repeat(64)}`;
    const lineage = vi.fn().mockResolvedValue({
      ...root,
      predecessorRunId: predecessor,
      checkpointReference: reference,
      seedVerifiedAt: at,
    });
    await TestBed.configureTestingModule({
      imports: [RunLineage],
      providers: [
        provideRouter([]),
        { provide: RUN_API, useValue: { lineage } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunLineage);
    fixture.componentRef.setInput('runId', runId);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(element.querySelector('a')?.getAttribute('href')).toBe(
        `/runs/${predecessor}`,
      ),
    );
    lineage.mockRejectedValue(new ApiRequestFailure({ kind: 'network' }));
    element.querySelector('button')?.click();
    await fixture.whenStable();
    await vi.waitFor(() => expect(element.textContent).toContain('historical'));
    expect(element.textContent).toContain(reference);
    await vi.waitFor(() =>
      expect(element.querySelector('a')?.getAttribute('href')).toBe(
        `/runs/${predecessor}`,
      ),
    );
  });
});
