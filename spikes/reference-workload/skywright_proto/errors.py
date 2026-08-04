"""PROTOTYPE — throwaway. See ../README.md.

B4 says contract misuse must fail *early* and *with a clear message*. That is
hard to judge from a prose bullet, so this prototype makes every contract
violation raise one exception type carrying a rule id, what the project did,
and what it should have done instead. Whether that actually reads well in
anger is the thing to react to.
"""


class ContractError(Exception):
    """A Training Project used the Training Contract incorrectly."""

    def __init__(self, rule: str, what: str, instead: str):
        self.rule = rule
        self.what = what
        self.instead = instead
        super().__init__(f"[{rule}] {what}\n  -> {instead}")


class Preempted(Exception):
    """The execution was interrupted by its target, not by a program error."""
