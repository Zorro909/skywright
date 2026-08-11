---
status: accepted
---

# Resolve configuration with structural overlays

Skywright resolves Run Configuration by applying three JSON object layers in fixed order: library defaults, Training Project Version defaults, then Run Submission overrides. Objects merge recursively only when both values are objects; every other higher-precedence value replaces the lower one, including arrays and `null`. Omission preserves the inherited value, an empty object changes nothing, and there is no generic deletion, reset, append or keyed-array operation. A schema that needs disabling or clearing exposes an ordinary value for it, such as `false`, `[]` or nullable `null`, so every overlay has exactly the same shape as the Run Configuration it contributes to.

## Validation contract

Each layer is a duplicate-free JSON object. Property names are compared exactly without Unicode normalization, and numbers remain lossless JSON numbers through resolution. The fully resolved value is compared structurally — object order is irrelevant and array order is significant — rather than by serialized bytes.

The composed schema is a closed-world JSON Schema Draft 2020-12 document: an undeclared property is rejected unless its schema explicitly admits free-form keys. The Project Configuration Contract declares that dialect, bundles every referenced schema into its content-addressed artifact, and uses the same enabled format assertions in Java and Python; unsupported vocabularies or mutable external references make the Training Project Version not runnable.

Defaults may omit required project values that a user must supply. Because JSON Schema validates complete instances rather than overlays, each Project Configuration Contract carries a **Defaults Completion Witness**. Starting from the merged library and project defaults, the witness may recursively fill absent properties but may never replace an existing value, even with an equal value. Project CI and the backend independently require the completed document to pass the unchanged whole-document schema. The witness proves the defaults have at least one valid completion but is never used as a default or included in a Run Definition.

For a real run, the backend overlays the submission on the defaults and validates the result against that same complete schema before creating the immutable Run Definition. A missing required property therefore rejects the Run Submission rather than making its Training Project Version unrunnable. An invalid schema, defaults/witness pair, dialect, vocabulary or reference rejects the Training Project Version. Errors carry a stable code, source layer, instance JSON Pointer and schema keyword; independently detectable errors are ordered by pointer and code, while explanatory prose need not match across languages.

## Conformance contract

One versioned fixture corpus is consumed by both the Java and Python implementations. Fixtures contain the three layers, complete schema and Defaults Completion Witness, plus either the structurally exact resolved document or the expected error codes and pointers. The corpus covers all precedence pairs; recursive object contribution; empty-object no-op; object/scalar replacement in both directions; whole-array replacement; accepted and rejected `null`; omission; unknown properties and admitted free-form keys; required values supplied or omitted at submission; witness fill and forbidden replacement; an invalid completed baseline; duplicate names and non-object roots; distinct composed and decomposed Unicode names; lossless large integers and decimals; and conditionals, dependencies and combinators evaluated only against the completed witness or final Run Configuration.

These examples are normative; `L`, `P`, `S` and `W` denote library defaults, project defaults, submission overrides and the witness:

| Case | Inputs | Expected result |
| --- | --- | --- |
| Three-layer precedence | `L={"x":0}`, `P={"x":1}`, `S={"x":2}` | `{"x":2}` |
| Recursive objects | `L={"x":{"a":1,"b":2}}`, `S={"x":{"b":3,"c":4}}` | `{"x":{"a":1,"b":3,"c":4}}` |
| Empty object | `L={"x":{"a":1}}`, `S={"x":{}}` | `{"x":{"a":1}}` |
| Type replacement | `L={"x":{"a":1}}`, `S={"x":2}` | `{"x":2}`; the reverse direction likewise replaces |
| Array replacement | `L={"x":[1,2]}`, `S={"x":[3]}` | `{"x":[3]}` |
| Explicit null | `L={"x":1}`, `S={"x":null}` | `{"x":null}` when allowed by the schema; otherwise a schema error at `/x` |
| Omission | `L={"x":1}`, `S={}` | `{"x":1}` |
| Required user value | `L={}`, `P={}`, `W={"x":0}` with required integer `/x` | The contract is runnable; `S={}` fails at `/x`, while `S={"x":1}` resolves to `{"x":1}` |
| Witness replacement | merged defaults `{"x":1}`, `W={"x":1}` | Contract error at `/x`, despite equality |
| Exact numbers | `L={"large":9007199254740993,"decimal":0.1}`, `S={}` | Both numbers remain exact |

Fixtures additionally pair a closed schema with an unknown key, the same shape with an explicitly free-form map, duplicate input names, non-object roots, and canonically composed and decomposed Unicode names. They also prove that conditional and cross-property keywords see the completed witness or final Run Configuration rather than any source layer in isolation.

Canonical JSON serialization is deliberately separate. If a later design content-addresses Run Configuration, it must define that byte representation without changing these resolution semantics.
