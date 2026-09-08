import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RUN_API, type CreateRun } from '../api/run.api';
import { ApiRequestFailure } from '../api/api-failure';
import { NewRunPage } from './new-run.page';
import { RunCancellation } from '../shared/run-cancellation';
import { observedRun, runId } from '../../../tests/fixtures/run';

const at = '2026-09-07T14:00:00Z';
const project = '00000000-0000-4000-8000-000000000001';
const dataset = '00000000-0000-4000-8000-000000000002';
const digest = `sha256:${'1'.repeat(64)}`;
const receipt = (id: string) => ({
  id,
  runId,
  kind: 'CANCELLATION_REQUEST',
  acceptedAt: at,
  disposition: 'cooperative-request-published',
  projectedAt: at,
  forceAfter: '2026-09-07T14:00:30Z',
});

function click(root: HTMLElement, text: string) {
  const button = Array.from(root.querySelectorAll('button')).find((item) =>
    item.textContent?.includes(text),
  );
  if (!button) throw new Error(`Missing button ${text}`);
  button.click();
}

async function creation(
  create = vi.fn((body: CreateRun) =>
    Promise.resolve({
      ...observedRun(),
      submissionId: body.submissionId,
    }),
  ),
) {
  const api = {
    create,
    target: vi.fn().mockResolvedValue({
      identity: 'local/amd',
      gpuModel: 'AMD',
      maximumGpuCount: 1,
      gpuMemoryBytes: 1024,
      submissionAvailable: true,
      observedAt: at,
    }),
    projects: vi
      .fn()
      .mockResolvedValue([{ id: project, displayName: 'Project' }]),
    versions: vi.fn().mockResolvedValue({
      registryAvailable: true,
      observedAt: at,
      versions: [{ versionLabel: 'v1', manifestDigest: digest }],
      failures: [],
    }),
    datasets: vi.fn().mockResolvedValue({
      items: [
        {
          definition: {
            definitionId: dataset,
            datasetId: dataset,
            versionLabel: 'd1',
            contentFingerprint: digest,
          },
        },
      ],
      nextCursor: null,
    }),
  };
  await TestBed.configureTestingModule({
    imports: [NewRunPage],
    providers: [provideRouter([]), { provide: RUN_API, useValue: api }],
  }).compileComponents();
  const fixture = TestBed.createComponent(NewRunPage);
  fixture.detectChanges();
  await fixture.whenStable();
  return { api, fixture, root: fixture.nativeElement as HTMLElement };
}

async function fill(root: HTMLElement) {
  const selects = root.querySelectorAll('select');
  if (!selects[0]) throw new Error('Project selector');
  selects[0].value = project;
  selects[0].dispatchEvent(new Event('change'));
  await vi.waitFor(() => expect(selects[1]?.options.length).toBe(2));
  if (!selects[1] || !selects[2]) throw new Error('Version selectors');
  selects[1].value = digest;
  selects[1].dispatchEvent(new Event('change'));
  selects[2].value = dataset;
  selects[2].dispatchEvent(new Event('change'));
}

describe('Local Run actions', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());
  it('renders creation with controls disabled when accessing localStorage throws', async () => {
    const storage = vi
      .spyOn(window, 'localStorage', 'get')
      .mockImplementation(() => {
        throw new DOMException('Storage access denied', 'SecurityError');
      });
    try {
      const { api, fixture, root } = await creation();
      expect(root.querySelector('fieldset')?.disabled).toBe(true);
      root
        .querySelector('form')
        ?.dispatchEvent(new Event('submit', { cancelable: true }));
      await fixture.whenStable();
      expect(api.create).not.toHaveBeenCalled();
    } finally {
      storage.mockRestore();
    }
  });
  it('renders cancellation evidence with controls disabled when accessing localStorage throws', async () => {
    const storage = vi
      .spyOn(window, 'localStorage', 'get')
      .mockImplementation(() => {
        throw new DOMException('Storage access denied', 'SecurityError');
      });
    try {
      const cancel = vi.fn();
      await TestBed.configureTestingModule({
        imports: [RunCancellation],
        providers: [{ provide: RUN_API, useValue: { cancel } }],
      }).compileComponents();
      const fixture = TestBed.createComponent(RunCancellation);
      fixture.componentRef.setInput('run', observedRun());
      fixture.detectChanges();
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      expect(root.textContent).toContain('Cancellation controls unavailable');
      expect(root.querySelector('button')?.disabled).toBe(true);
      expect(cancel).not.toHaveBeenCalled();
    } finally {
      storage.mockRestore();
    }
  });
  it('blocks a corrupt saved seed without sending or clearing its identity', async () => {
    const saved = JSON.stringify({
      version: 1,
      request: {
        submissionId: runId,
        trainingProjectId: project,
        manifestArtifactDigest: digest,
        datasetDefinitionId: dataset,
        target: 'local/amd',
        gpuCount: 1,
        configuration: {},
        checkpointSeed: 'bad',
      },
    });
    localStorage.setItem('skywright.local-submission.v1', saved);
    const { api, fixture, root } = await creation();
    expect(root.querySelector('fieldset')?.disabled).toBe(true);
    root
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    expect(api.create).not.toHaveBeenCalled();
    expect(localStorage.getItem('skywright.local-submission.v1')).toBe(saved);
  });
  it('does not submit malformed JSON and leaves field validation to the backend', async () => {
    const { api, fixture, root } = await creation();
    await fill(root);
    const config = root.querySelector('textarea');
    if (!config) throw new Error('Configuration');
    config.value = '{';
    config.dispatchEvent(new Event('input'));
    root
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    expect(root.textContent).toContain('Configuration must be a JSON object');
    expect(api.create).not.toHaveBeenCalled();
    expect(localStorage.getItem('skywright.local-submission.v1')).toBeNull();
  });
  it('freezes double-submit and replays exact identity after response loss and component reload', async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new ApiRequestFailure({ kind: 'network' }))
      .mockImplementation((body: CreateRun) =>
        Promise.resolve({
          ...observedRun(),
          submissionId: body.submissionId,
          handoff: 'uncertain',
        }),
      );
    const { fixture, root } = await creation(create);
    await fill(root);
    const form = root.querySelector('form');
    form?.dispatchEvent(new Event('submit', { cancelable: true }));
    form?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Acceptance is unresolved'),
    );
    expect(create).toHaveBeenCalledOnce();
    const original: unknown = create.mock.calls[0]?.[0];
    fixture.destroy();
    const restored = TestBed.createComponent(NewRunPage);
    restored.detectChanges();
    await restored.whenStable();
    const restoredRoot = restored.nativeElement as HTMLElement;
    expect(create).toHaveBeenCalledOnce();
    click(restoredRoot, 'Replay saved submission');
    await vi.waitFor(() =>
      expect(restoredRoot.textContent).toContain('Run accepted'),
    );
    expect(create.mock.calls[1]?.[0]).toEqual(original);
    expect(
      restoredRoot.querySelector(`a[href="/runs/${runId}"]`),
    ).not.toBeNull();
    expect(restoredRoot.textContent).toContain('Handoff: uncertain');
  });
  it.each([400, 404, 422])(
    'allows correcting a definitive backend rejection (%s)',
    async (status) => {
      const create = vi.fn().mockRejectedValue(
        new ApiRequestFailure({
          kind: 'problem',
          response: new Response('', { status }),
          problem: {
            errorCode: 'SKYWRIGHT_RUN_DEFINITION_INVALID',
            correlationId: 'validation-233',
            detail: 'Invalid configuration.',
            fieldViolations: [
              {
                field: '/configuration/project/rate',
                code: 'TYPE',
                message: 'Expected a number.',
              },
            ],
          },
        }),
      );
      const { fixture, root } = await creation(create);
      await fill(root);
      root
        .querySelector('form')
        ?.dispatchEvent(new Event('submit', { cancelable: true }));
      await vi.waitFor(() =>
        expect(root.textContent).toContain('Expected a number'),
      );
      expect(root.textContent).toContain('/configuration/project/rate');
      expect(root.textContent).toContain('validation-233');
      await vi.waitFor(() =>
        expect(
          Array.from(root.querySelectorAll('button')).find((b) =>
            b.textContent?.includes('Edit rejected request'),
          )?.disabled,
        ).toBe(false),
      );
      click(root, 'Edit rejected request');
      await fixture.whenStable();
      await vi.waitFor(() =>
        expect(root.querySelector('fieldset')?.disabled).toBe(false),
      );
    },
  );
  it.each([
    ['Maximum Recovery Debt', 'maximumRecoveryDebt'],
    ['GPU count', 'gpuCount'],
  ] as const)(
    'allows correcting fractional %s after backend rejection',
    async (label, field) => {
      const create = vi
        .fn((body: CreateRun) =>
          Promise.resolve({
            ...observedRun(),
            submissionId: body.submissionId,
          }),
        )
        .mockRejectedValueOnce(
          new ApiRequestFailure({
            kind: 'problem',
            response: new Response('', { status: 422 }),
            problem: {
              errorCode: 'SKYWRIGHT_RUN_DEFINITION_INVALID',
              correlationId: 'fractional-input',
              detail: 'Expected an integer.',
              fieldViolations: [],
            },
          }),
        );
      const { fixture, root } = await creation(create);
      await fill(root);
      const input = Array.from(root.querySelectorAll('label'))
        .find((item) => item.textContent?.includes(label))
        ?.querySelector('input');
      if (!input) throw new Error(`Missing ${label}`);
      input.value = '1.5';
      input.dispatchEvent(new Event('input'));
      root
        .querySelector('form')
        ?.dispatchEvent(new Event('submit', { cancelable: true }));
      await vi.waitFor(() =>
        expect(root.textContent).toContain('Expected an integer'),
      );
      await fixture.whenStable();
      expect(create.mock.calls[0]?.[0][field]).toBe(1.5);
      await vi.waitFor(() =>
        expect(
          Array.from(root.querySelectorAll('button')).find((button) =>
            button.textContent?.includes('Edit rejected request'),
          )?.disabled,
        ).toBe(false),
      );
      click(root, 'Edit rejected request');
      await fixture.whenStable();
      await vi.waitFor(() =>
        expect(root.querySelector('fieldset')?.disabled).toBe(false),
      );
      input.value = '1';
      input.dispatchEvent(new Event('input'));
      root
        .querySelector('form')
        ?.dispatchEvent(new Event('submit', { cancelable: true }));
      await vi.waitFor(() =>
        expect(root.textContent).toContain('Run accepted'),
      );
      expect(create.mock.calls[1]?.[0][field]).toBe(1);
      expect(create.mock.calls[1]?.[0].submissionId).not.toBe(
        create.mock.calls[0]?.[0].submissionId,
      );
    },
  );
  it('preserves an accepted link when catalogues become unavailable on refresh', async () => {
    const { api, fixture, root } = await creation();
    await fill(root);
    root
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() => expect(root.textContent).toContain('Run accepted'));
    api.target.mockRejectedValue(new ApiRequestFailure({ kind: 'network' }));
    click(root, 'Refresh catalogues');
    await fixture.whenStable();
    expect(root.textContent).toContain('Local target unavailable');
    expect(root.querySelector(`a[href="/runs/${runId}"]`)).not.toBeNull();
  });
  it('retains cancellation intent and escalation evidence while completion wins the race', async () => {
    const cancel = vi.fn((_run: string, id: string) =>
      Promise.resolve(receipt(id)),
    );
    await TestBed.configureTestingModule({
      imports: [RunCancellation],
      providers: [{ provide: RUN_API, useValue: { cancel } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(RunCancellation);
    fixture.componentRef.setInput('run', observedRun());
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    click(root, 'Request cancellation');
    await vi.waitFor(() =>
      expect(root.textContent).toContain('cooperative-request-published'),
    );
    expect(root.textContent).toContain('Forced cancellation eligible after');
    const run = observedRun();
    fixture.componentRef.setInput('run', {
      ...run,
      lifecycle: { ...run.lifecycle, state: 'finished', terminalLatched: true },
    });
    await fixture.whenStable();
    expect(root.textContent).toContain('confirmed a terminal outcome');
    expect(root.textContent).toContain('cooperative-request-published');
    expect(cancel).toHaveBeenCalledOnce();
  });
  it('reuses a lost cancellation identity after reconnect without claiming cancellation', async () => {
    const cancel = vi
      .fn()
      .mockRejectedValueOnce(new ApiRequestFailure({ kind: 'network' }))
      .mockImplementation((_run: string, id: string) =>
        Promise.resolve(receipt(id)),
      );
    await TestBed.configureTestingModule({
      imports: [RunCancellation],
      providers: [
        {
          provide: RUN_API,
          useValue: {
            cancel,
            command: vi
              .fn()
              .mockRejectedValue(new ApiRequestFailure({ kind: 'network' })),
          },
        },
      ],
    }).compileComponents();
    const first = TestBed.createComponent(RunCancellation);
    first.componentRef.setInput('run', observedRun());
    first.detectChanges();
    await first.whenStable();
    click(first.nativeElement as HTMLElement, 'Request cancellation');
    await vi.waitFor(() => expect(cancel).toHaveBeenCalledOnce());
    first.destroy();
    const next = TestBed.createComponent(RunCancellation);
    next.componentRef.setInput('run', observedRun());
    next.detectChanges();
    await next.whenStable();
    await vi.waitFor(() =>
      expect(
        Array.from(
          (next.nativeElement as HTMLElement).querySelectorAll('button'),
        ).find((b) => b.textContent?.includes('Replay Cancellation Request'))
          ?.disabled,
      ).toBe(false),
    );
    click(next.nativeElement as HTMLElement, 'Replay Cancellation Request');
    await vi.waitFor(() => expect(cancel).toHaveBeenCalledTimes(2));
    expect(cancel.mock.calls[0]?.[1]).toBe(cancel.mock.calls[1]?.[1]);
  });
});
