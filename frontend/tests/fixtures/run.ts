import type { Run, Progress } from '../../src/app/api/run.api';

export const runId = '00000000-0000-4000-8000-000000000083';
export function observedRun(): Run {
  return {
    runId,
    submissionId: '00000000-0000-4000-8000-000000000001',
    acceptedAt: '2026-09-07T10:00:00Z',
    acceptedIntent: 'submit',
    handoff: 'source-observed',
    sourceAvailability: 'live',
    evidenceGaps: [],
    definition: {
      trainingProjectVersion: {
        projectIdentity: 'tiny-training',
        manifestArtifactDigest: 'sha256:accepted-version',
      },
      targetRequest: {
        target: 'kubernetes/local',
        targetClass: 'local-single-gpu',
      },
    },
    lifecycle: {
      state: 'running',
      terminalLatched: false,
      sourceAvailability: 'live',
      processAvailability: 'live',
      sourceStatus: 'RUNNING',
      fetchedAt: '2026-09-07T10:10:00Z',
      skyPilotReadAt: '2026-09-07T10:09:58Z',
      runStoreReadAt: '2026-09-07T10:09:59Z',
      attemptCount: 2,
      recoveryCount: 1,
      executionSpanMillis: 600_000,
      evidenceGaps: [],
      facts: [],
      conflicts: [],
      controlDecisions: [],
    },
  };
}
export function progress(): Progress {
  return {
    availability: 'available',
    fetchedAt: '2026-09-07T10:10:00Z',
    record: {
      currentStep: 12,
      latestDurableStep: 10,
      latestDurableCheckpoint: `skywright-checkpoint:v1:10:sha256:${'a'.repeat(64)}`,
      writtenAt: '2026-09-07T10:09:30Z',
    },
  };
}
