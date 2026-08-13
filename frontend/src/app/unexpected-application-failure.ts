import { ErrorHandler, Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class UnexpectedApplicationFailure implements ErrorHandler {
  private readonly failed = signal(false);
  readonly isActive = this.failed.asReadonly();

  handleError(error: unknown): void {
    console.error('Unexpected application failure', error);
    this.failed.set(true);
  }

  clear(): void {
    this.failed.set(false);
  }
}
