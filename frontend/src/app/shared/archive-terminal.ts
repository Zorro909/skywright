import type { Terminal } from '@xterm/xterm';
import { logBytes, type LogPage } from '../api/run-log.api';

/** One passive, bounded terminal window. Cursor acknowledgement follows parser completion. */
export class ArchiveTerminal {
  private terminal: Terminal | undefined;
  private retained = 0;
  private pending: ((error: Error) => void) | undefined;
  private disposed = false;
  cursor: string | undefined;
  firstCursor: string | undefined;

  constructor(private readonly host: HTMLElement) {}

  async append(page: LogPage): Promise<void> {
    if (
      page.availability !== 'available' ||
      page.fromCursor === null ||
      page.nextCursor === null
    )
      return;
    if (this.pending) throw new Error('Terminal rendering is still pending');
    let bytes = logBytes(page);
    const from = BigInt(page.fromCursor);
    const next = BigInt(page.nextCursor);
    const expected = this.cursor === undefined ? from : BigInt(this.cursor);
    if (from > expected) throw new Error('Archive replay has a byte gap');
    if (next < expected) return;
    if (from < expected) bytes = bytes.slice(Number(expected - from));
    if (!bytes.length) {
      this.cursor ??= page.nextCursor;
      this.firstCursor ??= page.fromCursor;
      return;
    }
    if (!this.terminal || this.retained + bytes.length > 256 * 1024) {
      this.terminal?.dispose();
      this.host.replaceChildren();
      const { Terminal } = await import('@xterm/xterm');
      if (this.disposed) return;
      this.terminal = new Terminal({
        cols: 100,
        rows: 24,
        scrollback: 1000,
        disableStdin: true,
        convertEol: false,
        allowProposedApi: false,
        minimumContrastRatio: 4.5,
        linkHandler: {
          activate: () => {
            /* Logs cannot navigate the browser. */
          },
        },
      });
      this.terminal.open(this.host);
      this.retained = 0;
      this.firstCursor = expected.toString();
    }
    const terminal = this.terminal;
    await new Promise<void>((resolve, reject) => {
      const fail = (error: Error) => {
        clearTimeout(timeout);
        this.pending = undefined;
        reject(error);
      };
      const timeout = setTimeout(() => {
        terminal.dispose();
        this.terminal = undefined;
        fail(new Error('Terminal rendering exceeded its deadline'));
      }, 5000);
      this.pending = fail;
      terminal.write(bytes, () => {
        if (this.pending !== fail) return;
        clearTimeout(timeout);
        this.pending = undefined;
        this.retained += bytes.length;
        this.cursor = page.nextCursor ?? undefined;
        resolve();
      });
    });
  }

  dispose() {
    this.disposed = true;
    this.pending?.(new Error('Terminal closed'));
    this.terminal?.dispose();
    this.terminal = undefined;
    this.host.replaceChildren();
  }
}
