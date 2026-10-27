# Contributing to Wayfinder

Thanks for your interest! Bug reports, feature ideas and pull requests are all welcome.

## Ground rules

- By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
- Add new Kotlin source files with this header:

  ```kotlin
  // Copyright 2026 The Wayfinder Authors
  // SPDX-License-Identifier: Apache-2.0
  ```

- Never commit signing keys, credentials or `local.properties`.

## Development

1. Build with `./gradlew assembleDebug` and run the tests with `./gradlew testDebugUnitTest`. Both must pass before you open a PR.
2. Keep pure logic (parsers, path handling, rename planning, filters) in `data/` with unit tests. Add fuzz or regression tests for anything that parses untrusted input: remote listings, archive entries, URIs, intents.
3. Follow the existing style: Kotlin, Jetpack Compose, Material 3, small focused files, no new dependencies without discussion.
4. Every user-facing action should have a keyboard path and a mouse path. Document new shortcuts in the F1 sheet and the command palette.

## Reporting bugs

Please include the device, Android version, the app version (Settings → Apps), steps to reproduce, and the expected and actual behavior.
