import { TestBed } from '@angular/core/testing';
import { RunLogViewer } from './run-log-viewer';
import type { LogPage } from '../api/run-log.api';
import { runId } from '../../../tests/fixtures/run';

const initial = 'initial\r\n';
function page(text: string, from = 0, finalized = false): LogPage {
  const end = String(from + new TextEncoder().encode(text).length);
  return {
    runId,
    stream: 'task',
    availability: 'available',
    observedAt: '2026-09-08T13:00:00Z',
    archiveState: finalized ? 'finalized' : 'staging',
    sourceAvailability: 'available',
    sourceReason: null,
    lastSuccessfulFetch: '2026-09-08T13:00:00Z',
    completion: finalized ? 'complete' : 'pending',
    reason: null,
    fromCursor: String(from),
    nextCursor: end,
    endCursor: end,
    bytesBase64: btoa(text),
  };
}
function event(value: LogPage) {
  return new Response(
    'event: archive\ndata: ' + JSON.stringify(value) + '\n\n',
    { headers: { 'Content-Type': 'text/event-stream' } },
  );
}

describe('Run log viewer', () => {
  afterEach(() => vi.restoreAllMocks());
  it('retains captured terminal bytes during a source outage and reconnects from the rendered cursor', async () => {
    let follows = 0;
    const cursors: (string | null)[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = new URL((input as Request).url);
      if (url.pathname.endsWith('/navigation'))
        return Promise.resolve(Response.json({ items: [], nextCursor: null }));
      if (url.pathname.endsWith('/follow')) {
        cursors.push(url.searchParams.get('cursor'));
        follows++;
        if (follows === 1)
          return Promise.resolve(
            event({
              ...page('', initial.length),
              availability: 'unavailable',
              archiveState: 'unknown',
              sourceAvailability: 'unknown',
              completion: 'unknown',
              reason: 'ARCHIVE_UNAVAILABLE',
              endCursor: null,
            }),
          );
        return Promise.resolve(event(page('done', initial.length, true)));
      }
      return Promise.resolve(Response.json(page(initial)));
    });
    await TestBed.configureTestingModule({
      imports: [RunLogViewer],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunLogViewer);
    fixture.componentRef.setInput('runId', runId);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Run Store archive unavailable'),
    );
    expect(root.querySelector('.archive-terminal')?.textContent).toContain(
      'initial',
    );
    await vi.waitFor(
      () =>
        expect(root.textContent).toContain('Archive finalized; follow ended'),
      { timeout: 5000 },
    );
    expect(cursors).toEqual([String(initial.length), String(initial.length)]);
    await vi.waitFor(() =>
      expect(root.querySelector('.archive-terminal')?.textContent).toContain(
        'done',
      ),
    );
    fixture.destroy();
  });

  it('shows partial finalization and navigates setup in a separate historical window', async () => {
    const requests: URL[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = new URL((input as Request).url);
      requests.push(url);
      if (url.pathname.endsWith('/navigation'))
        return Promise.resolve(
          Response.json({
            items: [
              {
                cursor: '0',
                kind: 'setup',
                attemptId: null,
                preparationCursor: null,
              },
            ],
            nextCursor: null,
          }),
        );
      return Promise.resolve(
        Response.json({
          ...page(
            url.searchParams.has('cursor') ? 'setup failed' : 'tail',
            0,
            true,
          ),
          completion: 'partial',
          reason: 'SOURCE_GENERATION_LOST',
        }),
      );
    });
    await TestBed.configureTestingModule({
      imports: [RunLogViewer],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunLogViewer);
    fixture.componentRef.setInput('runId', runId);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Archive finalized; follow ended'),
    );
    expect(root.textContent).toContain('SOURCE_GENERATION_LOST');
    Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('setup'))
      ?.click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Historical byte window'),
    );
    await vi.waitFor(() =>
      expect(root.querySelector('.archive-terminal')?.textContent).toContain(
        'setup failed',
      ),
    );
    expect(root.querySelector('.archive-terminal')?.textContent).not.toContain(
      'tail',
    );
    expect(requests.some((url) => url.searchParams.get('cursor') === '0')).toBe(
      true,
    );
    expect(requests.some((url) => url.pathname.endsWith('/follow'))).toBe(
      false,
    );
    fixture.destroy();
  });
});
