import { ArchiveTerminal } from './archive-terminal';
import { runLogApi, type LogPage } from '../api/run-log.api';
import { runId } from '../../../tests/fixtures/run';

function page(bytes: Uint8Array, from: bigint, finalized = false): LogPage {
  const end = (from + BigInt(bytes.length)).toString();
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
    fromCursor: from.toString(),
    nextCursor: end,
    endCursor: end,
    bytesBase64: btoa(
      Array.from(bytes, (value) => String.fromCharCode(value)).join(''),
    ),
  };
}

describe('Archived terminal bytes', () => {
  let host: HTMLDivElement;
  let terminal: ArchiveTerminal;
  beforeEach(() => {
    host = document.createElement('div');
    document.body.append(host);
    terminal = new ArchiveTerminal(host);
  });
  afterEach(() => {
    terminal.dispose();
    host.remove();
    vi.restoreAllMocks();
  });

  it('preserves split UTF-8 and ANSI sequences with exact replay beyond JavaScript integer precision', async () => {
    const raw = new TextEncoder().encode('\u001b[31m€🙂\u001b[0m');
    const start = 9007199254740993n;
    for (let i = 0; i < raw.length; i++) {
      const part = page(raw.slice(i, i + 1), start + BigInt(i));
      await terminal.append(part);
      await terminal.append(part);
      expect(terminal.cursor).toBe((start + BigInt(i + 1)).toString());
    }
    await vi.waitFor(() => expect(host.textContent).toContain('€🙂'));
    expect(host.textContent?.match(/€/gu)).toHaveLength(1);
    await expect(
      terminal.append(
        page(new Uint8Array([65]), start + BigInt(raw.length) + 1n),
      ),
    ).rejects.toThrow('gap');
  });

  it('clips overlapping reconnect ranges before interpreting carriage-return rewrites', async () => {
    const first = new TextEncoder().encode('progress 10%');
    await terminal.append(page(first, 0n));
    const full = new TextEncoder().encode('progress 10%\r\u001b[2Kdone');
    await terminal.append(page(full, 0n, true));
    await vi.waitFor(() => expect(host.textContent).toContain('done'));
    expect(host.textContent).not.toContain('progress');
    expect(terminal.cursor).toBe(full.length.toString());
  });

  it('rolls the terminal window over at the raw-byte retention bound', async () => {
    const bytes = new Uint8Array(65536).fill(65);
    for (let i = 0; i < 5; i++)
      await terminal.append(page(bytes, BigInt(i * bytes.length)));
    expect(terminal.firstCursor).toBe('262144');
    expect(terminal.cursor).toBe('327680');
    expect(host.querySelectorAll('.xterm')).toHaveLength(1);
  });

  it('follows bounded SSE frames split across transport chunks and awaits rendering before acknowledgement', async () => {
    const raw = new TextEncoder().encode('€\r\n');
    const parts = [page(raw.slice(0, 1), 0n), page(raw.slice(1), 1n, true)];
    const wire = new TextEncoder().encode(
      parts
        .map(
          (item) =>
            'id: ' +
            item.nextCursor +
            '\r\nevent: archive\r\ndata: ' +
            JSON.stringify(item) +
            '\r\n\r\n',
        )
        .join(''),
    );
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            for (let i = 0; i < wire.length; i += 7)
              controller.enqueue(wire.slice(i, i + 7));
            controller.close();
          },
        }),
        { headers: { 'Content-Type': 'text/event-stream' } },
      ),
    );
    await runLogApi.follow(
      runId,
      'task',
      '0',
      new AbortController().signal,
      (item) => terminal.append(item),
    );
    expect(terminal.cursor).toBe(raw.length.toString());
    expect((fetch.mock.calls[0]?.[0] as Request).url).toContain('cursor=0');
    await vi.waitFor(() => expect(host.textContent).toContain('€'));
  });

  it('rejects oversized or mismatched raw ranges without advancing the cursor', async () => {
    await expect(
      terminal.append({ ...page(new Uint8Array([65]), 0n), nextCursor: '4' }),
    ).rejects.toThrow('range');
    expect(terminal.cursor).toBeUndefined();
    const oversized = 'data: ' + 'x'.repeat(128 * 1024);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(oversized));
    await expect(
      runLogApi.follow(
        runId,
        'task',
        undefined,
        new AbortController().signal,
        (item) => terminal.append(item),
      ),
    ).rejects.toThrow('bound');
  });
});
