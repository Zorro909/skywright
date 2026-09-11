import { TestBed } from '@angular/core/testing';
import { RunOutputViewer } from './run-output-viewer';
import { runId } from '../../../tests/fixtures/run';

describe('Run output viewer', () => {
  afterEach(() => vi.restoreAllMocks());

  it('offers an application download for a produced Artifact', async () => {
    const downloadUrl = `/api/v1/runs/${runId}/output-content?key=example`;
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      Response.json({
        items: [
          {
            kind: 'artifact',
            step: 1,
            name: 'prediction.json',
            sizeBytes: 16,
            sha256: 'a'.repeat(64),
            downloadUrl,
          },
        ],
      }),
    );
    await TestBed.configureTestingModule({
      imports: [RunOutputViewer],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunOutputViewer);
    fixture.componentRef.setInput('runId', runId);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('prediction.json'),
    );
    expect(root.querySelector('a')?.getAttribute('href')).toBe(downloadUrl);
    expect(root.querySelector('a')?.hasAttribute('download')).toBe(true);
    fixture.destroy();
  });
});
