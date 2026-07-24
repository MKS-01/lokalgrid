# schema — wire-format source of truth

**Empty until Phase 05, on purpose.**

The codec is hand-written twice first — JS in `mock-node/` and Kotlin in
`android/protocol/` (Phases 00–01), C on the node in Phase 03. Codegen from
`records.yaml` + `control.proto` arrives only after hand-written drift has caused
a real bug — that bug is the lesson (PROJECT.md §2 and §6).

Do not add generators here early. The shared cross-check that will *reveal* the
drift already exists: `mock-node/golden/vectors.json`, decoded by both the JS and
Kotlin suites today, and by C once the firmware codec lands.
