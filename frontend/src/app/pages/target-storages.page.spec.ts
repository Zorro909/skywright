import { TestBed } from '@angular/core/testing';

import {
  TARGET_STORAGE_API,
  type CreateTargetStorage,
} from '../api/target-storage.api';
import { TargetStoragesPage } from './target-storages.page';

describe('Target Storages operator workflow', () => {
  it('shows non-secret registration state and creates a draft', async () => {
    const create = vi
      .fn<(request: CreateTargetStorage) => Promise<void>>()
      .mockRejectedValueOnce({ kind: 'network' })
      .mockResolvedValueOnce(undefined);
    const api = {
      list: vi.fn().mockResolvedValue([storageFixture()]),
      listDefaults: vi.fn().mockResolvedValue([]),
      create,
      stage: vi.fn(),
      qualify: vi.fn(),
      activate: vi.fn(),
      replaceBindings: vi.fn(),
      remove: vi.fn(),
      assignDefaults: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [TargetStoragesPage],
      providers: [{ provide: TARGET_STORAGE_API, useValue: api }],
    }).compileComponents();

    const fixture = TestBed.createComponent(TargetStoragesPage);
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(view.textContent).toContain('backend: missing');
    });

    expect(view.textContent).toContain('Run outputs');
    expect(view.querySelector('input[name*="secret" i]')).toBeNull();
    expect(view.querySelector('input[name*="password" i]')).toBeNull();
    expect(view.querySelector('input[name*="token" i]')).toBeNull();

    const form = view.querySelector('form');
    setValue(form, 'name', 'Dataset archive');
    setValue(form, 'bucket', 'datasets');
    setValue(form, 'endpoint', 'https://s3.example');
    form?.dispatchEvent(new SubmitEvent('submit'));
    await fixture.whenStable();
    expect((form?.elements.namedItem('name') as HTMLInputElement).value).toBe(
      'Dataset archive',
    );

    form?.dispatchEvent(new SubmitEvent('submit'));
    await fixture.whenStable();

    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[0]?.[0].name).toBe('Dataset archive');
    expect(create.mock.calls[0]?.[0].bucket).toBe('datasets');
    expect(create.mock.calls[0]?.[0].configuration.endpoint).toBe(
      'https://s3.example',
    );
    expect((form?.elements.namedItem('name') as HTMLInputElement).value).toBe(
      '',
    );

    const stageForm = view.querySelector<HTMLFormElement>('form.inline-form');
    const pathStyleAccess = stageForm?.elements.namedItem(
      'pathStyleAccess',
    ) as HTMLInputElement;
    expect(pathStyleAccess.checked).toBe(false);
    pathStyleAccess.checked = true;
    stageForm?.dispatchEvent(new SubmitEvent('submit'));
    await fixture.whenStable();
    expect(api.stage).toHaveBeenCalledWith(
      expect.objectContaining({ id: storageFixture().id }),
      'https://candidate.storage.example',
      'us-west-2',
      true,
      expect.any(Object),
    );
  });

  it('retains a fulfilled storage list when loading defaults fails', async () => {
    const api = {
      list: vi.fn().mockResolvedValue([storageFixture()]),
      listDefaults: vi
        .fn()
        .mockRejectedValue(new Error('defaults unavailable')),
    };
    await TestBed.configureTestingModule({
      imports: [TargetStoragesPage],
      providers: [{ provide: TARGET_STORAGE_API, useValue: api }],
    }).compileComponents();

    const fixture = TestBed.createComponent(TargetStoragesPage);
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(view.querySelector('h4')?.textContent).toContain('Run outputs');
    });
    expect(view.textContent).toContain('The Target Storage operation failed.');
  });

  it('does not present a failed storage list as an empty registry', async () => {
    const api = {
      list: vi
        .fn()
        .mockResolvedValueOnce([])
        .mockRejectedValueOnce({ kind: 'network' }),
      listDefaults: vi.fn().mockResolvedValue([]),
    };
    await TestBed.configureTestingModule({
      imports: [TargetStoragesPage],
      providers: [{ provide: TARGET_STORAGE_API, useValue: api }],
    }).compileComponents();

    const fixture = TestBed.createComponent(TargetStoragesPage);
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(view.textContent).toContain('No Target Storages are registered.');
    });

    const page = fixture.componentInstance as unknown as {
      reload(): Promise<void>;
    };
    await page.reload();
    fixture.detectChanges();

    expect(view.textContent).not.toContain(
      'No Target Storages are registered.',
    );
  });

  it('does not hide unexpected client defects as API failures', async () => {
    const api = {
      list: vi.fn().mockResolvedValue([]),
      listDefaults: vi.fn().mockResolvedValue([]),
    };
    await TestBed.configureTestingModule({
      imports: [TargetStoragesPage],
      providers: [{ provide: TARGET_STORAGE_API, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(TargetStoragesPage);
    const page = fixture.componentInstance as unknown as {
      mutate(
        message: string,
        operation: () => Promise<unknown>,
      ): Promise<boolean>;
    };
    const defect = new Error('client defect');

    await expect(
      page.mutate('completed', () => Promise.reject(defect)),
    ).rejects.toBe(defect);
  });
});

function storageFixture() {
  return {
    id: '00000000-0000-0000-0000-000000000080',
    name: 'Run outputs',
    purpose: 'run-output' as const,
    bucket: 'runs',
    registrationRevision: 2,
    activated: false,
    eligible: false,
    activeRevision: 1,
    candidateRevision: 2,
    availability: 'transiently-unavailable' as const,
    configuration: {
      endpoint: 'https://storage.example',
      region: 'eu-central-1',
      pathStyleAccess: true,
      compatibilityOptions: {},
    },
    revisions: [
      {
        revision: 1,
        state: 'active' as const,
        configuration: {
          endpoint: 'https://storage.example',
          region: 'eu-central-1',
          pathStyleAccess: true,
          compatibilityOptions: {},
        },
      },
      {
        revision: 2,
        state: 'candidate' as const,
        configuration: {
          endpoint: 'https://candidate.storage.example',
          region: 'us-west-2',
          pathStyleAccess: false,
          compatibilityOptions: {
            chunkedEncoding: 'enabled',
            checksumCalculation: 'when-supported',
          },
        },
      },
    ],
    bindings: [
      {
        role: 'backend' as const,
        bindingId: '00000000-0000-0000-0000-000000000001',
        bindingRevision: 1,
        readiness: 'missing' as const,
      },
    ],
    assessments: [],
  };
}

function setValue(form: HTMLFormElement | null, name: string, value: string) {
  const input = form?.elements.namedItem(name) as HTMLInputElement | null;
  if (input) input.value = value;
}
