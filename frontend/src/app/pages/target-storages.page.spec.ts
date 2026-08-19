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
      .mockResolvedValue(undefined);
    const api = {
      list: vi.fn().mockResolvedValue([
        {
          id: '00000000-0000-0000-0000-000000000080',
          name: 'Run outputs',
          purpose: 'run-output',
          bucket: 'runs',
          registrationRevision: 2,
          activated: false,
          eligible: false,
          activeRevision: null,
          candidateRevision: 1,
          availability: 'transiently-unavailable',
          configuration: {
            endpoint: 'https://storage.example',
            region: 'eu-central-1',
            pathStyleAccess: true,
            compatibilityOptions: {},
          },
          revisions: [
            {
              revision: 1,
              state: 'candidate',
              configuration: {
                endpoint: 'https://storage.example',
                region: 'eu-central-1',
                pathStyleAccess: true,
                compatibilityOptions: {},
              },
            },
          ],
          bindings: [
            {
              role: 'backend',
              bindingId: '00000000-0000-0000-0000-000000000001',
              bindingRevision: 1,
              readiness: 'missing',
            },
          ],
          assessments: [],
        },
      ]),
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

    expect(create).toHaveBeenCalledOnce();
    expect(create.mock.calls[0]?.[0].name).toBe('Dataset archive');
    expect(create.mock.calls[0]?.[0].bucket).toBe('datasets');
    expect(create.mock.calls[0]?.[0].configuration.endpoint).toBe(
      'https://s3.example',
    );
  });
});

function setValue(form: HTMLFormElement | null, name: string, value: string) {
  const input = form?.elements.namedItem(name) as HTMLInputElement | null;
  if (input) input.value = value;
}
