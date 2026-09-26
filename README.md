# ZeroTranslater

On-device translation for Android, with a system-wide text-selection action.

Paste text in, or select text in **any** other app and pick **Ztranslate** from the
selection menu. Translation happens entirely on the device. There is no server, no
account, no API key, and no cost per query.

## Contents

- [What it does](#what-it-does)
- [Verified on device](#verified-on-device)
- [Build and run](#build-and-run)
- [Signing](#signing)
- [APK size](#apk-size)
- [Architecture](#architecture)
- [The English-pivot problem](#the-english-pivot-problem-read-this-before-judging-quality)
- [Permissions](#permissions)
- [Known limitations](#known-limitations)
- [Tests](#tests)
- [Deviations from the original spec](#deviations-from-the-original-spec)
- [License](#license)

## What it does

| Feature | Notes |
| --- | --- |
| Manual input box | Debounced live translation (450 ms) plus an explicit Translate action on the IME action key. |
| Source / target pickers | Searchable. Source supports auto-detect; target does not. |
| Swap | Enabled; see the note in `TranslateViewModel.swapLanguages` for the auto-detect case. |
| Clear ("X") | One tap, always visible while there is input. |
| Copy translation | Uses the platform clipboard, so Android 13+ shows its own confirmation. |
| Language pack manager | Download and delete per language, plus a Wi-Fi-only download switch. |
| `PROCESS_TEXT` overlay | A bottom sheet over the host app, with Copy and "Open in ZeroTranslater". |

Language list is read at runtime from
`TranslateLanguage.getAllLanguages()` — nothing is hardcoded, so a Play services
model update that adds a language needs no code change.

## Verified on device

Tested on a physical **Xiaomi device running Android 16 (SDK 36), arm64-v8a**, with
Google Play services present. What actually ran:

| Area | Result |
| --- | --- |
| Cold / warm launch | 1386 ms / 220 ms, no `FATAL`, no `AndroidRuntime` errors |
| Auto-detect | Correctly identified Spanish from free text |
| Pack download | Real fetch: `gvt1.com/edgedl/translate/offline/v5/high/r29/en_es.zip` |
| Translation | `el gato negro duerme en la casa` → "The black cat sleeps in the house" |
| English-pivot hint | `es→af` correctly reported "Routed via English — needs 3 packs" |
| Pack manager | Downloaded packs show **On device** + Delete; others **Not downloaded** + Download |
| `PROCESS_TEXT` overlay | Activity + `ModalBottomSheet` render, with Copy and "Open in ZeroTranslater" |
| Settings | Survived a full cold restart |

The merged manifest on device requests exactly three permissions, all inherited
from dependencies: `INTERNET`, `ACCESS_NETWORK_STATE`, and
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

### Bugs this found that the test suite did not

Two defects surfaced only on hardware, and both are fixed:

1. **Stale output after a language change.** `runTranslation` read the UI state and
   then awaited the engine. Changing the source or target during that window
   published the finished translation under the *new* pair's labels — a Spanish →
   English result displayed as though it were a translation into French. Cancelling
   the job was not sufficient, because `Job.cancel()` only *requests* cancellation.
   Fixed with a `translationEpoch` counter; a translation whose epoch is stale now
   discards its result instead of publishing it.
2. **Back on the pack manager exited the app.** The pack manager is a screen inside
   `MainActivity`, not its own activity, so nothing intercepted the system back
   gesture. Pressing back finished the task and discarded the typed text. Fixed with
   a `BackHandler`.

Neither was reachable from a JVM test: the first needs real concurrency and the
second is a navigation concern.

### Not yet verified

**The selection-menu entry has not been seen in a real text-selection menu.** The
overlay was verified by firing a `PROCESS_TEXT` intent directly at it, which
proves the activity, the translucent theme, and the bottom sheet all work — but not
that a host app's selection menu renders "Ztranslate". Confirming that needs a host
app that declares `PROCESS_TEXT`; on the test device only 3 of 409 installed
packages did, and Chrome and Gmail were not among them. See
[Known limitations](#known-limitations).

## Build and run

Requirements:

- JDK 17 or newer. **The Gradle daemon needs JDK 21 on some machines** — see
  [Deviations](#deviations-from-the-original-spec).
- Android SDK with platform 36 and build-tools 36.0.0.
- `local.properties` pointing at your SDK (`sdk.dir=...`).

```bash
./gradlew assembleDebug          # debug APK, signed with the debug key, installable
./gradlew test                   # 23 unit tests
./gradlew lintDebug              # static analysis
./gradlew assembleRelease        # R8-minified; UNSIGNED unless keystore.properties exists
./gradlew bundleRelease          # App Bundle (.aab) - the artifact to publish
```

On Windows use `gradlew.bat`.

## Signing

**A successful `assembleRelease` does not mean you have a shippable build.** Without
signing material the task still succeeds and emits
`app-release-unsigned.apk`, which cannot be installed and cannot be uploaded to
Play. Check the filename, or verify directly:

```bash
apksigner verify --verbose app/build/outputs/apk/release/*.apk
# unsigned -> "DOES NOT VERIFY"
```

To produce a signed release, copy `keystore.properties.example` to
`keystore.properties` (git-ignored) and fill it in:

```bash
keytool -genkeypair -v -keystore release.jks -alias zerotranslater \
        -keyalg RSA -keysize 4096 -validity 10000
```

The signed output is then `app-release.apk`, verified under APK Signature Schemes
v2 and v3. (v1/JAR signing is skipped automatically because `minSdk` is 24, where
v2 already covers every supported device.)

The debug APK is always signed with the standard debug keystore, so
`app-debug.apk` is installable on a device without any of this setup. That is the
artifact to test on hardware.

## APK size

The release APK is **~66 MB**, and essentially all of it is ML Kit's native code:

| Entry | Size |
| --- | --- |
| `libtranslate_jni.so` × 4 ABIs | 60 MB |
| `liblanguage_id_l2c_jni.so` × 4 ABIs | 4 MB |
| `classes.dex` | 3.4 MB |
| everything else | < 1 MB |

Two things inflate this, and one of them is fixable on your machine:

1. **No NDK installed, so symbols were never stripped.** AGP logs
   `Unable to strip the following libraries, packaging them as they are`.
   Installing the NDK via SDK Manager makes AGP strip these automatically, which
   should cut them substantially.

2. **All four ABIs are bundled.** `x86` and `x86_64` (35.6 MB combined) exist
   purely for emulators; no physical device uses them.

There are deliberately **no ABI splits**, because AGP forbids combining them with
App Bundles ([issuetracker #402800800](https://issuetracker.google.com/402800800))
and the bundle is what you should publish. Play performs per-ABI selection at
install time, so Play users download roughly one ABI's worth rather than all four.
The cost is that anyone sideloading the APK downloads all 66 MB.

The launcher icon PNGs for API 24–25 are generated, not hand-drawn. To regenerate
them after editing the design:

```bash
powershell -ExecutionPolicy Bypass -File tools/generate-launcher-icons.ps1
```

## Architecture

```
engine/     TranslationManager, LanguagePair, TranslationError
data/       SettingsStore (DataStore preferences)
processtext/ PROCESS_TEXT intent parsing + its ViewModel
ui/         Compose screens and ViewModels
```

Three deliberate choices worth knowing about:

**`TranslationManager` owns the translator cache, and closes it.** A ML Kit
`Translator` holds *native* model memory. The tempting optimisation — cache the
translator so you don't reload the model on every keystroke — is exactly how you
leak until the process dies. So the manager closes the outgoing translator on
every pair change, in `close()`, and before deleting a pack that is currently
loaded. Each ViewModel calls `close()` in `onCleared()`.

**Every ML Kit call is a `suspend` function.** ML Kit returns Play services
`Task`s. `Tasks.await` on the main thread would freeze the UI, so all of them go
through `kotlinx-coroutines-play-services`.

**`ProcessTextIntentParser` is a plain object with no Android imports.** The
`PROCESS_TEXT` contract is the highest-risk surface in the app — any third-party
app can put anything in those extras — so the logic is extracted and unit tested
rather than buried in an Activity that can only be exercised on a device.

## The English-pivot problem (read this before judging quality)

ML Kit's translation models are **English-pivoted**. There is no direct
German→French model. Every non-English pair is executed as
German→English→French, which means:

- **Three** language packs are required instead of two (`de`, `en`, `fr`).
- Quality is measurably worse than translating from or to English, because the
  text is converted twice.

The UI states this rather than hiding it: the source/target row shows
"Routed via English — needs 3 packs" whenever it applies.

Japanese→Korean is a known upstream defect (the Japanese model emits English)
and is rejected with a clear error instead of returning garbage. See
[issuetracker #369752306](https://issuetracker.google.com/issues/369752306).

## Permissions

The app declares **no** `<uses-permission>` of its own. It opens no sockets.

The merged manifest, verified with `./gradlew :app:processReleaseManifest`,
contains exactly three, all contributed by dependencies:

| Permission | Source | Why |
| --- | --- | --- |
| `INTERNET` | ML Kit | Fetching language packs from Play services. |
| `ACCESS_NETWORK_STATE` | ML Kit | Honouring the Wi-Fi-only download condition. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | Internal broadcast scoping. |

If you remove the ML Kit dependencies, all three go away.

## Known limitations

These are real and deliberate, not oversights.

**No download progress or size.** `RemoteModelManager.download` resolves with a
bare `Task<Void>`; neither `RemoteModel` nor `TranslateRemoteModel` exposes a byte
count, and there is no progress callback for on-device translate models. The UI
shows an indeterminate indicator and names the packs being fetched. A fake
percentage would be worse than none.

**Requires Google Play services.** ML Kit downloads models through Play
services, so the app cannot translate on a degoogled device or on some Chinese
OEM ROMs. There is no fallback engine. A C++/NDK fallback was considered and
deferred: the app's core is a text input box, which needs the Android IME, and
every C++ UI toolchain (Qt/QML, SDL, Dear ImGui) has weak or absent soft-keyboard
text entry. C++ is the right tool for a *non*-UI component, not for this one.

**`PROCESS_TEXT` does not appear in every app.** It requires the host app to
implement the `ActionMode` text-selection API. WebView-based apps, games, and many
custom editors do not, so the Ztranslate entry will simply be absent there. Some
OEM ROMs — **notably MIUI/Xiaomi** — strip third-party `PROCESS_TEXT` handlers
outright. This cannot be fixed from the app side.

**Auto-detect asks instead of guessing below 0.60 confidence.** `PROCESS_TEXT`
frequently delivers a single word, and a single word carries very little
evidence. Guessing wrong is worse than asking the user to pick.

**Input is capped at 5,000 characters.** The translator takes a single string with
no chunking, and beyond a few thousand characters the on-device models are slow
enough to look hung. Longer input is truncated and the user is told.

**Single-task detection is not chunked.** A long document is translated as one
truncated unit, not sentence by sentence.

## Tests

```bash
./gradlew test
```

23 tests, all passing:

- `LanguagePairTest` — the English-pivot rules, pack counts, rejected pairs.
- `ProcessTextIntentParserTest` — null/blank/oversized input, the 5,000-char
  boundary from both sides, `CharSequence` that is not a `String`.
- `ProcessTextManifestTest` (Robolectric) — reads the **real merged manifest** and
  asserts that a `PROCESS_TEXT` intent resolves to the overlay activity, that the
  label renders as `Ztranslate`, that it is exported, that it is excluded from
  recents, and that the launcher entry still works.

That last file is the one that matters most. A typo in an intent filter, a missing
`android.intent.category.DEFAULT`, or a wrong label would produce an app that
builds, passes every other test, and never appears in any selection menu.

## Deviations from the original spec

- **Name / package.** The spec said `Ztranslate` / `com.ztranslate` *conditional on
  the name being free on Google Play*. It is not: `asia.zsoft.subtranslate`
  ("zTranslate: Translate subtitle") is live on Play, and `ztranslate.net` is an
  established brand. The app is therefore `ZeroTranslater` /
  `com.zerotranslater`, which also matches the repository name. The
  `PROCESS_TEXT` menu label is still the short `Ztranslate`. To rename, change
  `applicationId`, `namespace`, and the `com.zerotranslater` package directories.
- **Gradle runs on JDK 21, targets Java 17 bytecode.** Temurin 17.0.19 crashes the
  C2 compiler (`opto/loopnode.hpp`) under Gradle's instrumentation agent, and
  21.0.12 crashes in `jvm.dll`. `sourceCompatibility`/`targetCompatibility` and
  `jvmTarget` are all still 17, so the APK is unchanged; only the JVM running the
  build differs.
- **`aaptOptions { noCompress "tflite" }` removed.** ML Kit fetches models through
  Play services at runtime and never reads `.tflite` from assets. `aaptOptions` is
  also deprecated in AGP 8 and removed in AGP 9. If ever needed, the modern form
  is `androidResources { noCompress += "tflite" }`.
- **androidx stack held below latest.** The newest androidx artifacts declare
  `aar-metadata` requiring compileSdk 37 and AGP 9.1. This project targets
  compileSdk 36 with AGP 8.13, so `core-ktx`, `activity-compose`, `lifecycle`, and
  `datastore` are pinned to their last SDK-36-compatible releases. The remaining
  lint "newer version available" warnings are expected.
- **`compose-bom` pinned to 2025.10.01**, contemporaneous with Kotlin 2.2.20.
  Bump Kotlin first, then the BOM.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

ML Kit is a Google product and is subject to
[Google's ML Kit Terms of Service](https://developers.google.com/ml-kit/terms),
which restrict using the models for certain purposes and govern redistribution
of the model files. Those terms apply on top of this project's license, and they
are the main constraint on how this app can be distributed.
