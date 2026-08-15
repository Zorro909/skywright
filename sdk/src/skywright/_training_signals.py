"""Process-owned cooperative interruption handling."""

import os
import signal
import threading


class SignalRequests:
    def __init__(self, shutdown_grace_seconds: float) -> None:
        self.interruption_requested = False
        self._shutdown_grace_seconds = shutdown_grace_seconds
        self._forced_exit: threading.Timer | None = None

    def install(self) -> None:
        signal.signal(signal.SIGINT, self._handle)
        signal.signal(signal.SIGTERM, self._handle)

    def _handle(self, _signal_number: int, _frame: object) -> None:
        if self.interruption_requested:
            os._exit(1)
        self.interruption_requested = True
        self._forced_exit = threading.Timer(
            self._shutdown_grace_seconds, os._exit, args=(1,)
        )
        self._forced_exit.daemon = True
        self._forced_exit.start()

    def finalize(self) -> None:
        if self._forced_exit is not None:
            self._forced_exit.cancel()
