# Android Kotlin Quality Checks

Run these commands from `LyrebirdApp/android-sdk-v5-as`.

## Lyrebird-owned code

These are the required local/CI gates (pre-commit `android-spotless` / `android-tests`, and the `android-quality` GitHub Actions job):

```sh
./gradlew :app:spotlessKotlinCheck :app:compileCurrentDebugKotlin :app:testCurrentDebugUnitTest
./gradlew qualityLyrebird
```

`qualityLyrebird` runs Spotless, Detekt on Lyrebird-owned Kotlin, and `:app:lintCurrentDebug`. Spotless is intentionally scoped to Lyrebird-owned Kotlin files so vendor code is not reformatted. Detekt/Lint currently report without failing the build (`ignoreFailures` / `abortOnError false`); Spotless, compile, and unit tests are the hard gates.

## DJI/vendor code

```sh
./gradlew detektDji :uxsdk:lintDebug
```

This keeps DJI sample and UXSDK findings separate from the code we can act on. Use these reports for awareness, but avoid changing DJI-derived code unless the project intentionally carries a local patch.

## Reports

- Detekt: `build/reports/detekt/`
- Android Lint app: `../lyrebird-app/build/reports/lint-results-debug.html`
- Android Lint UXSDK: `../android-sdk-v5-uxsdk/build/reports/lint-results-debug.html`
