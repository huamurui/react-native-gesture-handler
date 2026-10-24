# Android native touch cancellation regression tests

This test branch demonstrates the terminal-event cancellation bug and its fix. Its first commit adds the tests to unmodified upstream code; its second commit applies the production fix. The branch is separate from the production-only fix branch. CI configuration is unchanged.

## Prerequisites

- Node.js from the repository's `.nvmrc` (currently 24.19).
- Yarn 4, using the version pinned by `packageManager` (currently 4.13.0).
- JDK 17, with `JAVA_HOME` configured.
- Android SDK Platform 37, Build Tools 37.0.0, and NDK 27.1.12297006, matching `apps/basic-example/android/build.gradle`. Configure `ANDROID_HOME` or the example's local SDK settings.

The first run downloads Gradle, Android dependencies, and Robolectric's Android runtime. These are local JVM tests; they require neither a connected device nor Metro.

## Clone and run the fixed version

```sh
git clone --branch test/android-terminal-touch-cancellation \
  https://github.com/huamurui/react-native-gesture-handler.git
cd react-native-gesture-handler
nvm install
nvm use
corepack enable
yarn install --immutable
cd apps/basic-example/android
./gradlew :react-native-gesture-handler:testDebugUnitTest \
  --tests 'com.swmansion.gesturehandler.react.RNGestureHandlerRootHelperTest' \
  -Pkotlin.incremental=false \
  --console=plain
```

If you do not use nvm, select the Node.js version in `.nvmrc` with your preferred version manager instead. The branch tip contains the fix: **all 10 tests should pass**.

## Compare with the failing baseline

From the same checkout, switch to the parent of the test branch tip. That commit contains the identical test suite and configuration, with the original production implementation:

```sh
git switch test/android-terminal-touch-cancellation
git switch --detach HEAD^
./gradlew :react-native-gesture-handler:testDebugUnitTest \
  --tests 'com.swmansion.gesturehandler.react.RNGestureHandlerRootHelperTest' \
  -Pkotlin.incremental=false \
  --console=plain
```

Keep the working directory at `apps/basic-example/android` for these commands. The `-Pkotlin.incremental=false` flag forces fresh Kotlin compilation to avoid stale compiler caches when switching revisions. The baseline is expected to report **7 failures and 3 passing controls**, and Gradle exits unsuccessfully. For example, a successful tap leaves the native view with only `DOWN`, where the test expects `DOWN → CANCEL`.

Return to the fixed version and rerun:

```sh
git switch test/android-terminal-touch-cancellation
./gradlew :react-native-gesture-handler:testDebugUnitTest \
  --tests 'com.swmansion.gesturehandler.react.RNGestureHandlerRootHelperTest' \
  -Pkotlin.incremental=false \
  --console=plain
```

The HTML report is written to `packages/react-native-gesture-handler/android/build/reports/tests/testDebugUnitTest/index.html`, relative to the repository root. Each run replaces the previous report.

## What the tests cover

The suite uses the real root helper, orchestrator, gesture handlers, and Android `ViewGroup` touch dispatch. Only the React context's native-module lookup is mocked; no JavaScript runtime is started.

- Tap activation on the final `UP` closes the ordinary native view's touch stream with `CANCEL`.
- A two-pointer sequence still cancels once when the remaining pointer lifts.
- Disabled and timed-out taps allow native `UP`; interception that starts on `MOVE` does not cancel again on `UP`.
- An external `CANCEL` and a subsequent touch stream leave no stale interception state.
- A native container that receives `UP` retains ownership, including simultaneous recognition with Tap, while its ordinary child is cancelled. The container receives neither an extra `CANCEL` nor an extra nested-scroll stop.
- A native view calling `requestDisallowInterceptTouchEvent` during synthetic cancellation cannot cancel the winning RNGH handler.

The native container is a recording test fixture. These tests verify event delivery and ownership, not actual `ScrollView` fling physics or selectable-text selection UI; those behaviors require separate physical-device regression checks.
