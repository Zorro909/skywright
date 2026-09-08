import { RunLineage } from './run-lineage';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { isLifecycle, object, type Run } from '../api/run.api';
import { RunProgress } from './run-progress';
import { ObservationAge } from './observation-age';

export function definitionText(run: Run, section: string, key: string): string {
  const value: unknown = run.definition[section];
  return object(value) && typeof value[key] === 'string'
    ? value[key]
    : 'Not recorded';
}

@Component({
  selector: 'sky-run-evidence',
  imports: [RunLineage, DatePipe, JsonPipe, RunProgress, ObservationAge],
  template: `
    <dl class="identity">
      <div>
        <dt>Training Project</dt>
        <dd>{{ project() }}</dd>
      </div>
      <div>
        <dt>Project Version</dt>
        <dd>
          <code>{{ version() }}</code>
        </dd>
      </div>
      <div>
        <dt>Target</dt>
        <dd>{{ target() }}</dd>
      </div>
      <div>
        <dt>Accepted</dt>
        <dd>{{ run().acceptedAt | date: 'medium' }}</dd>
      </div>
    </dl>
    @if (historical()) {
      <p class="notice">
        <strong
          >Previous response only. Current lifecycle unavailable while this read
          is unresolved.</strong
        >
      </p>
    }
    @if (lifecycle(); as observation) {
      <p>
        <strong
          >{{
            historical() ? 'Last observed lifecycle' : 'Observed lifecycle'
          }}: {{ observation.state ?? 'unavailable' }}</strong
        >
        @if (observation.terminalLatched) {
          · Terminal supported by durable evidence
        }
      </p>
      @if (observation.lastSeen; as seen) {
        <p>
          Last seen {{ seen.state }} at {{ seen.observedAt | date: 'medium' }} ·
          <sky-observation-age [at]="seen.observedAt" /> This is not a current
          state.
        </p>
      }
      @if (observation.cause) {
        <p>Cause: {{ observation.cause }}</p>
      }
      <p>
        SkyPilot: {{ observation.sourceAvailability }} · Run Store lifecycle
        evidence: {{ observation.processAvailability }}
      </p>
      <p>
        Lifecycle read {{ observation.fetchedAt | date: 'medium' }} ·
        <sky-observation-age [at]="observation.fetchedAt" />
      </p>
      @if (observation.sourceStatus) {
        <p>SkyPilot observation: {{ observation.sourceStatus }}</p>
      }
      <dl class="identity">
        <div>
          <dt>Execution Attempts</dt>
          <dd>{{ observation.attemptCount ?? 'Unavailable' }}</dd>
        </div>
        <div>
          <dt>SkyPilot recoveries</dt>
          <dd>
            @if (observation.recoveryCountIsMinimum) {
              At least
            }
            {{ observation.recoveryCount ?? 'Unavailable' }}
            @if (observation.recoveryCountIsMinimum) {
              · retained recovery evidence
            }
          </dd>
        </div>
        <div>
          <dt>Runtime (execution span)</dt>
          <dd>
            {{
              observation.executionSpanMillis === null ||
              observation.executionSpanMillis === undefined
                ? 'Unavailable'
                : span(observation.executionSpanMillis)
            }}
            @if (observation.executionSpanSource === 'retained') {
              · retained source timestamps
            }
          </dd>
        </div>
      </dl>
      <p class="muted">
        Execution span includes recovery waits; it is not active compute or
        billed time.
      </p>
      @if (observation.controlDecisions.length) {
        <h4>Accepted control intent</h4>
        <ul>
          @for (decision of observation.controlDecisions; track decision.id) {
            <li>
              {{ decision.kind }} · {{ decision.decidedAt | date: 'medium' }}
              @if (decision.dispatchPrevented) {
                · First dispatch prevented
              }
            </li>
          }
        </ul>
        <p>Accepted intent does not advance the observed lifecycle.</p>
      }
      @if (observation.evidenceGaps.length) {
        <details>
          <summary>
            Evidence gaps ({{ observation.evidenceGaps.length }})
          </summary>
          <ul>
            @for (gap of observation.evidenceGaps; track $index) {
              <li>{{ gap }}</li>
            }
          </ul>
        </details>
      }
      @if (observation.conflicts.length) {
        <details class="notice">
          <summary>
            Retained-fact conflicts ({{ observation.conflicts.length }})
          </summary>
          @for (conflict of observation.conflicts; track $index) {
            <h4>{{ conflict.kind }} · {{ conflict.sourceEventIdentity }}</h4>
            <p>Selected by the backend:</p>
            <pre>{{ conflict.selected | json }}</pre>
            <p>Conflicting retained observations:</p>
            <pre>{{ conflict.alternatives | json }}</pre>
          }
        </details>
      }
      @if (detail()) {
        <details>
          <summary>
            Source-backed lifecycle facts ({{ observation.facts.length }})
          </summary>
          <p>
            SkyPilot read began
            {{ observation.skyPilotReadAt | date: 'medium' }}; Run Store read
            began {{ observation.runStoreReadAt | date: 'medium' }}.
          </p>
          @for (fact of observation.facts; track $index) {
            <pre>{{ fact | json }}</pre>
          } @empty {
            <p>No retained source facts available.</p>
          }
        </details>
      }
    } @else {
      <p>Lifecycle unavailable. No valid observation was returned.</p>
    }
    <sky-run-progress [runId]="run().runId" />
    @if (detail()) {
      <sky-run-lineage [runId]="run().runId" />
      <p>
        Submission: <code>{{ run().submissionId }}</code>
      </p>
      <details>
        <summary>Accepted Run Definition</summary>
        <pre>{{ run().definition | json }}</pre>
      </details>
      @for (capability of capabilities; track capability.name) {
        <section [attr.aria-label]="capability.name + ' unavailable'">
          <h3>{{ capability.name }} unavailable</h3>
          <p>{{ capability.reason }}</p>
        </section>
      }
    }
  `,
  styleUrl: '../pages/run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunEvidence {
  readonly run = input.required<Run>();
  readonly detail = input(false);
  readonly historical = input(false);
  protected readonly lifecycle = computed(() =>
    isLifecycle(this.run().lifecycle) ? this.run().lifecycle : undefined,
  );
  protected readonly project = computed(() =>
    definitionText(this.run(), 'trainingProjectVersion', 'projectIdentity'),
  );
  protected readonly version = computed(() =>
    definitionText(
      this.run(),
      'trainingProjectVersion',
      'manifestArtifactDigest',
    ),
  );
  protected readonly target = computed(() =>
    definitionText(this.run(), 'targetRequest', 'target'),
  );
  protected readonly capabilities = [
    {
      name: 'Logs',
      reason:
        'The Run log archive reader is not available in this view yet. Archive capture may still be running.',
    },
    {
      name: 'Metrics',
      reason:
        'Metric View is not available here. Basic committed progress is read independently from the Run Store.',
    },
    {
      name: 'Cost',
      reason:
        'No source-backed Run cost observation is available in this view.',
    },
    {
      name: 'Preservation work',
      reason:
        'The preservation-work reader is not available. This does not establish that preservation is complete or unnecessary.',
    },
    {
      name: 'Attention Items',
      reason:
        'The Attention Item reader is not available. This does not establish that the Run needs no attention.',
    },
  ];
  protected span(milliseconds: number) {
    const seconds = Math.floor(milliseconds / 1000);
    return `${Math.floor(seconds / 3600)}h ${Math.floor(seconds / 60) % 60}m ${seconds % 60}s`;
  }
}
