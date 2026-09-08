# Issue 234 terminal streaming

Research checked 2026-09-08 against [#234](https://github.com/Zorro909/skywright/issues/234)
and accepted [ADR 0019](../adr/0019-tail-the-run-log-archive-for-live-logs.md).
The implementation reads only the archive, keeps task/controller byte positions
separate, and ends follow at terminal-manifest publication. Demand-adaptive
reconciliation and live Repatriation remain later scope.

## Renderer and Angular integration

The npm registry currently identifies these stable releases:

| Package | Version | Published | Compatibility evidence |
| --- | --- | --- | --- |
| `@xterm/xterm` | `6.0.0` | 2025-12-22 | Bundled TypeScript declarations; no framework peer dependency |
| `@xterm/addon-fit` | `0.11.0` | 2025-12-22 | Bundled declarations; released with xterm 6 |

Use the maintained scoped packages. These exact values came from the publisher's
[xterm registry metadata](https://registry.npmjs.org/@xterm/xterm/6.0.0)
and [fit registry metadata](https://registry.npmjs.org/@xterm/addon-fit/0.11.0),
not stale search summaries showing xterm 5.

The repository uses Angular 22.1.1 and TypeScript 6.0.2. Directly wrap the terminal
in an Angular component rather than introducing an Angular-specific wrapper.
This is a compatibility inference from the framework-independent API; the actual
Angular compiler/browser tests still need to pass. Open it after Angular renders
a visible container, include the package's `css/xterm.css`, and dispose the
terminal, observer and stream on destruction. Angular recommends render callbacks
for DOM work; xterm requires dimensions when `open` runs.
[Angular DOM APIs](https://angular.dev/guide/components/dom-apis),
[xterm 6 API declarations](https://github.com/xtermjs/xterm.js/blob/6.0.0/typings/xterm.d.ts)

Fit is optional. Its `proposeDimensions()` permits clamping rows and columns
before `terminal.resize()`, whereas `fit()` applies the computed size directly.
Observe only the container, avoid resize feedback loops, and defer measurement
while hidden. The addon returns no dimensions before its font metrics exist.
[Fit implementation](https://github.com/xtermjs/xterm.js/blob/6.0.0/addons/addon-fit/src/FitAddon.ts)

## Bytes, parser state and passive rendering

Decode each base64 payload to `Uint8Array` and call
`terminal.write(bytes, callback)`. Never decode individual archive chunks to
JavaScript text or use `writeln`: xterm's UTF-8 decoder carries incomplete
codepoints across writes. A single terminal's parser likewise carries unfinished
escape sequences. Keep that terminal alive across ordinary network reconnects.
[Encoding guide](https://xtermjs.org/docs/guides/encoding/),
[persistent decoder/parser](https://github.com/xtermjs/xterm.js/blob/6.0.0/src/common/InputHandler.ts)

Advance the applied archive cursor only in the write callback. `write` queues
asynchronously; `onWriteParsed` can fire while more writes remain, so it is not
an acknowledgement for a particular range. Keep raw CR, LF, NUL and ANSI bytes;
`convertEol: false` avoids inventing CR bytes in terminal interpretation.
[API declarations](https://github.com/xtermjs/xterm.js/blob/6.0.0/typings/xterm.d.ts)

Use `disableStdin: true`, no output-to-server listeners, no attach/image/clipboard
addons, and no document-title changes from log content. Explicitly disable link
activation with a no-op `linkHandler.activate`; omitting the handler still permits
OSC 8 links through the default confirmation dialog. Keep window operations at
their disabled defaults. Render source metadata with Angular text bindings, never
HTML derived from terminal content.
[Options](https://xtermjs.org/docs/api/terminal/interfaces/iterminaloptions/),
[security guide](https://xtermjs.org/docs/guides/security/)

## Bounded transport and replay

SSE is UTF-8 text, so JSON carrying base64 is appropriate for arbitrary archived
bytes. Its frame parser must tolerate network splits, LF/CRLF/CR line endings,
comments and multiline `data`; incomplete final events are discarded. Native
EventSource updates Last-Event-ID before application rendering finishes and
automatically reconnects. A transport event ID therefore is not a renderer
acknowledgement. Explicit `close()` prevents reconnect; HTTP 204 also stops it.
[SSE standard](https://html.spec.whatwg.org/multipage/server-sent-events.html)

Recommended application protocol, derived from ADR 0019:

1. Describe each range as `(stream, startInclusive, endExclusive, base64)`.
   Check identity, nonnegative positions, decoded length and configured maximum
   before queueing. Encode large positions as decimal strings, or explicitly
   reject values outside JavaScript's safe-integer range.
2. For applied cursor `C`, ignore a range ending at or before `C`; trim overlap
   when `start < C < end`; append only when the remaining start equals `C`.
   A gap requires archive replay from `C`, never silent skipping.
3. Process ranges serially through write callbacks. Heartbeats, freshness and
   outage notices do not advance byte cursors. Retain the terminal and resume
   from the last applied cursor on reconnect. Before reconnect, settle an
   outstanding write or rebuild the bounded retained window; otherwise its
   late callback can race replay and duplicate bytes.
4. A new page load or renderer replacement cannot resume from a cursor alone:
   it also lost decoder and ANSI state. Start a fresh bounded tail or replay
   the retained contiguous raw window from its declared beginning.

Native EventSource can work with an explicit bounded queue and manual close/reopen
using the applied cursor. A `fetch` response reader makes sequential consumption
and cancellation simpler, but requires implementing SSE framing and reconnect
policy. Pause reads until the renderer callback; bound both complete events and
the unfinished frame. Stream backpressure helps upstream flow but does not prove
a fixed network-stack memory limit.
[Streams standard](https://streams.spec.whatwg.org/#rs-class)

Suggested initial tuning, not canonical limits: 64 KiB tail/page, 16 KiB raw
range, 32 KiB maximum SSE frame, 256 KiB application pending budget, 2,000
scrollback rows and clamped geometry. Count base64 expansion and JSON overhead
separately. Give the server explicit per-viewer pending-byte/frame limits, an
aggregate viewer/read budget, and bounded send time. On overload close the
connection, discard only transient queued frames, and replay from the browser's
applied cursor; archive bytes remain durable. Finalization status is acknowledged
only after preceding terminal writes finish.

Do not rely on xterm's emergency write limit: version 6 throws after its pending
buffer exceeds roughly 50 MB. Its guide recommends flow control far below that
level. More subtly, bounded scrollback counts rows, not bytes: unlimited combining
marks can accumulate in one cell. A strict browser bound also needs a bounded
terminal lifetime/input window, followed by explicit rollover or bounded rebuild.
[Write buffer](https://github.com/xtermjs/xterm.js/blob/6.0.0/src/common/input/WriteBuffer.ts),
[flow control](https://xtermjs.org/docs/guides/flowcontrol/),
[combined-cell storage](https://github.com/xtermjs/xterm.js/blob/6.0.0/src/common/buffer/BufferLine.ts#L230)

## Tail and paging limitation

Older bytes cannot be prepended to a live terminal parser. Page into a separate
bounded history view or recreate a bounded chronological window. An arbitrary
tail boundary may begin inside UTF-8 or an escape sequence and lacks earlier
terminal state; finite lookbehind cannot reconstruct arbitrary earlier modes.
Expose that earlier context is omitted, preserve exact byte ranges, and do not
claim full-screen equivalence to replay from byte zero. These are consequences
of a stateful terminal renderer, not permission to strip or rewrite archive bytes.

Useful regression cases are every split of a multibyte codepoint and CSI/OSC
sequence; CR progress rewrites; duplicate and overlapping ranges; a gap;
disconnect during an outstanding write; a split SSE delimiter; truncated final
frame; a slow renderer; combining-mark output; source loss; and complete/partial
finalization after pending data. Use real xterm in browser tests for parser and
display behavior, with deterministic transport fixtures for reconnect races.

## Spring MVC and nonblocking Servlet output

This repository resolves Spring Framework **7.0.8** through Boot 4.1.0 and pins
Tomcat **11.0.25**. For a strict slow-viewer bound, use Servlet nonblocking output
through Spring's existing async lifecycle. Calling `request.startAsync()` alone
inside the controller misses Spring bookkeeping: its
`StandardServletAsyncWebRequest.isAsyncStarted()` also requires its own stored
async context. `DispatcherServlet` consults that value to distinguish concurrent
handling from synchronous completion.
[Spring async wrapper](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-web/src/main/java/org/springframework/web/context/request/async/StandardServletAsyncWebRequest.java#L124),
[dispatcher](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java#L965)

The public SPI exposes the adapter's existing wrapper. Before registering the
writer, perform the equivalent of:

```java
var manager = WebAsyncUtils.getAsyncManager(request);
var asyncRequest = Objects.requireNonNull(manager.getAsyncWebRequest());
asyncRequest.setTimeout(sessionTimeoutMillis);
asyncRequest.startAsync();
var context = request.getAsyncContext();
var output = context.getResponse().getOutputStream();
```

Use the response retained by the context, including Spring/filter wrappers.
The wrapper registers Spring's completion listener and makes
`manager.isConcurrentHandlingStarted()` true. A generated `ResponseEntity<Void>`
method may then return null: `HttpEntityMethodProcessor` marks that response
handled and does not write a body. A direct `HttpServletResponse` method argument
also marks it handled. Do not return a second response body or dispatch back into
the controller to continue the stream. This is a source-backed use of public SPI,
requiring a real embedded-Tomcat integration test with the deployed filter chain.
[WebAsyncManager](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java#L120),
[null response handling](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/HttpEntityMethodProcessor.java#L188)

Register `WriteListener` once, after async starts. Gate **flush as well as each
write** on `isReady()`. Only the initial callback is unconditional; further
`onWritePossible()` callbacks require an earlier false readiness result. New
data arriving while the pump was idle-but-ready must therefore trigger the
serialized pump itself. Never perform archive reads in the container callback.
Serialize producer/callback/timeout state and finish idempotently on completion,
error, deadline or shutdown. No request/response access is allowed afterward.
[Servlet output contract](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/servletoutputstream)

One bounded encoded frame per viewer, 16 admitted sessions, one outstanding
archive job per session and a fixed executor with a bounded queue make the
application budget explicit. Keep a frame pending through its flush/drain stage;
do not release that slot merely because its bytes entered a container buffer.
Measure its five-second deadline from initial enqueue, on an independent
scheduler. On expiry, stop producing and complete the async response; release
permits exactly once. A normal async session timeout is a separate whole-request
limit, not a per-frame write timer. Nonblocking close may leave the container
draining bytes in the background, so do not describe five seconds as a guaranteed
TCP-reset deadline. Keep connector connection/output budgets finite and test a
client that stops reading, rather than only a mock callback that reports false.
[AsyncContext lifecycle](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/asynccontext),
[Tomcat connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)

`SseEmitter` is simpler but is not an equivalent five-second cancellation
guarantee: MVC response writes remain blocking, and emitter `send` and `complete`
take the same write lock. A watchdog calling `complete()` can wait behind the
stalled send. Fixed threads/permits cap damage but do not establish timely slot
recovery. Tomcat NIO initializes its socket write timeout from connector
`connectionTimeout`; its blocking write loop resets the allowance after progress,
so this is not a total frame deadline either. Prefer the nonblocking route for
the proposed contract, without changing shared connector timeouts merely to make
an emitter watchdog appear effective.
[MVC streaming behavior](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html),
[emitter lock](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/ResponseBodyEmitter.java#L201),
[Tomcat NIO implementation](https://github.com/apache/tomcat/blob/11.0.25/java/org/apache/tomcat/util/net/NioEndpoint.java#L1818)
