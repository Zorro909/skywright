import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  signal,
} from '@angular/core';
import { formatObservationAge } from './capability-unavailable';

@Component({
  selector: 'sky-observation-age',
  template: `{{ age() }}`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ObservationAge {
  readonly at = input.required<string>();
  private readonly now = signal(new Date());
  constructor() {
    const timer = globalThis.setInterval(
      () => this.now.set(new Date()),
      30_000,
    );
    inject(DestroyRef).onDestroy(() => globalThis.clearInterval(timer));
  }
  protected age() {
    return formatObservationAge(new Date(this.at()), this.now());
  }
}
