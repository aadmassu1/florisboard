# :lib:ampk — Amharic prediction engine

Pure Kotlin/JVM port of the reference engine in the **am-predictive-kb** project
(`github.com/aadmassu1/am-predictive-kb`, `ampk/`). Fidel normalization, an n-gram LM, a
completion dictionary, conservative autocorrect, on-device personalization, and a structured
audit trail — behind the `SuggestionProvider` contract the keyboard talks to.

## Test

```
./gradlew :lib:ampk:test
```

`GoldenVectorTest` replays a call script captured from the Python reference
(`src/test/resources/golden/parity.json`) and asserts identical suggestions, commits, and
autocorrect decisions. Regenerate the golden file from the reference project:

```
py -3.12 scripts/export_golden.py
```

## Notes

- Source is currently duplicated from am-predictive-kb. Long-term this should be a git submodule
  or a published artifact so it stays in sync with the reference engine + its Python test suite.
- Apache license headers to be added before upstreaming.
