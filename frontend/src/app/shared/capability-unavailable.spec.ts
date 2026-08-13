import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';

import { CapabilityUnavailable } from './capability-unavailable';

@Component({
  imports: [CapabilityUnavailable],
  template: `
    <button type="button">Unaffected action</button>
    <sky-capability-unavailable
      capability="System Information"
      source="control-plane API"
      reason="The server could not be reached."
      [observedAt]="observedAt"
      [now]="now"
      [hasLastSeen]="hasLastSeen()"
      [retrying]="retrying()"
      (retry)="retries.set(retries() + 1)"
    >
      @if (hasLastSeen()) {
        <p last-seen>Last build: 0</p>
      }
    </sky-capability-unavailable>
    <sky-capability-unavailable
      capability="Metrics"
      source="Run Store"
      reason="Metric observations are unavailable."
      [observedAt]="observedAt"
      [now]="now"
      (retry)="metricRetries.set(metricRetries() + 1)"
    />
  `,
})
class TestHost {
  readonly observedAt = new Date('2026-08-13T10:00:00Z');
  readonly now = new Date('2026-08-13T10:05:00Z');
  readonly hasLastSeen = signal(false);
  readonly retrying = signal(false);
  readonly retries = signal(0);
  readonly metricRetries = signal(0);
}

@Component({
  imports: [CapabilityUnavailable],
  template: `
    <sky-capability-unavailable
      capability="System Information"
      source="control-plane API"
      reason="Unavailable."
      [observedAt]="observedAt"
    />
  `,
})
class AutoClockHost {
  readonly observedAt = new Date('2026-08-13T10:00:00Z');
}

describe('capability unavailable presentation', () => {
  let fixture: ComponentFixture<TestHost>;

  afterEach(() => vi.useRealTimers());

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHost, AutoClockHost],
    }).compileComponents();
    fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
  });

  it('names the capability and source without confusing unavailable data with zero', async () => {
    const view = fixture.nativeElement as HTMLElement;
    const systemInformation = view.querySelector(
      '[aria-label="System Information unavailable"]',
    );

    expect(systemInformation?.textContent).toContain(
      'System Information unavailable',
    );
    expect(systemInformation?.textContent).toContain('control-plane API');
    expect(systemInformation?.textContent).toContain(
      'The server could not be reached.',
    );
    expect(systemInformation?.textContent).toContain('Observed 5 minutes ago.');
    expect(systemInformation?.textContent).toContain(
      'No last-seen information is available.',
    );

    fixture.componentInstance.hasLastSeen.set(true);
    fixture.detectChanges();

    expect(systemInformation?.textContent).toContain('Last-seen information');
    expect(systemInformation?.textContent).toContain('Last build: 0');
    expect(systemInformation?.textContent).not.toContain(
      'No last-seen information is available.',
    );
    expect((await axe.run(view)).violations).toEqual([]);
  });

  it('announces retry state while leaving independent controls operable', () => {
    const view = fixture.nativeElement as HTMLElement;
    const retry = Array.from(view.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Retry',
    );
    const unaffected = Array.from(view.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Unaffected action',
    );

    retry?.click();
    expect(fixture.componentInstance.retries()).toBe(1);
    expect(fixture.componentInstance.metricRetries()).toBe(0);
    expect(
      view.querySelector('[aria-label="Metrics unavailable"]'),
    ).not.toBeNull();
    expect(unaffected?.disabled).toBe(false);

    fixture.componentInstance.retrying.set(true);
    fixture.detectChanges();

    expect(retry?.disabled).toBe(true);
    expect(view.querySelector('[role="status"]')?.textContent).toContain(
      'Retrying System Information…',
    );
  });

  it('advances observation age while an outage remains visible', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-13T10:00:00Z'));
    const clockFixture = TestBed.createComponent(AutoClockHost);
    clockFixture.detectChanges();
    const view = clockFixture.nativeElement as HTMLElement;
    expect(view.textContent).toContain('Observed just now.');

    await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
    clockFixture.detectChanges();

    expect(view.textContent).toContain('Observed 5 minutes ago.');
    clockFixture.destroy();
    vi.useRealTimers();
  });
});
