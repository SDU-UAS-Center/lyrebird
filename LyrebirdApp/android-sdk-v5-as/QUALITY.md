# Android Kotlin Quality Checks

Run these commands from `LyrebirdApp/android-sdk-v5-as`.

## Lyrebird-owned code

```sh
./gradlew :app:spotlessKotlinCheck detektLyrebird :app:lintDebug
```

This checks formatting with Spotless/ktlint, Kotlin quality and complexity with Detekt, and Android platform issues with Android Lint. Spotless is intentionally scoped to Lyrebird-owned Kotlin files so vendor code is not reformatted.

## DJI/vendor code

```sh
./gradlew detektDji :uxsdk:lintDebug
```

This keeps DJI sample and UXSDK findings separate from the code we can act on. Use these reports for awareness, but avoid changing DJI-derived code unless the project intentionally carries a local patch.

## Reports

- Detekt: `build/reports/detekt/`
- Android Lint app: `../lyrebird-app/build/reports/lint-results-debug.html`
- Android Lint UXSDK: `../android-sdk-v5-uxsdk/build/reports/lint-results-debug.html`
