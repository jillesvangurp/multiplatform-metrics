## Overview

- Kotlin multiplatform project with most compilation targets supported
- Unless specifically required, new code goes in the commonMain source tree and cannot use JVM dependencies

## New dependencies

- don't add any unless specifically agreed/requested

## Tests

- Tests should use kotest-assertions and @Test annotated functions as is common in multiplatform kotlin.

## Build

- Use JVM 21 or newer
- Quick compilation and tests: `./gradlew jvmTest`
- Before pull request: `./gradlew build` (slow build because of all the platforms)

## Documentation

- README is generated via the ReadmeGenerationTest using kotlin4example. Documentation should be updated there.
- `./gradlew jvmTest` also regenerates the documentation. Mind line overflows and other errors/warnings.

