# ZeroTranslater

On-device translation for Android, with a system-wide text-selection action.

Type or paste text directly, or pull text in from any other app — select text
and pick **Ztranslate** from the menu, share to ZeroTranslater, or tap the
floating button. Translation runs entirely on the device: no server, no
account, no API key, no cost per query. Requires Google Play services for
model downloads.

## Features

| Feature | Notes |
| --- | --- |
| Manual input | Debounced live translation (450 ms), plus an explicit action on the IME key. |
| Source / target pickers | Searchable; source supports auto-detect. |
| Swap | With auto-detect active it swaps using the detected language, falling back to the default target. |
| Copy translation | Platform clipboard, so Android 13+ shows its own confirmation. |
| Language packs | Per-language download and delete, with a Wi-Fi-only switch. |
| Selection menu | `PROCESS_TEXT` opens a bottom sheet over the host app, with Copy and "Open in ZeroTranslater". |
| Share sheet | Share any plain text to ZeroTranslater. |
| Floating button | Optional draggable pill over all apps; tap it to translate what you last copied. |

## Entry points

- **Selection menu** — requires the host app to implement the `ActionMode`
  text-selection API. WebView apps, games and many custom editors don't, and
  some OEM ROMs (notably MIUI) strip third-party `PROCESS_TEXT` handlers
  entirely. Not fixable from this side.
- **Share sheet** — works everywhere the selection menu doesn't, including
  WebView apps such as NotebookLM. Plain text only: no images, no streams.
- **Floating button** — a user-initiated clipboard trigger. Android 10+ blocks
  background clipboard reads outright, so there is no clipboard listener to
  build; instead the tap starts a transparent activity that reads the clipboard
  once one of the app's windows holds focus. The button itself is
  `FLAG_NOT_FOCUSABLE` and never steals focus while sitting on screen.

## Build

Needs JDK 21 to run Gradle (targets Java 17 bytecode), Android SDK platform 36,
build-tools 36.0.0, and a `local.properties` containing `sdk.dir=...`. Use
`gradlew.bat` on Windows.

```bash
./gradlew assembleDebug          # installable, signed with the debug key
./gradlew assembleRelease        # R8-minified
./gradlew bundleRelease          # .aab - the artifact to publish
./gradlew test                   # 36 unit tests
./gradlew lintDebug
```

`assembleRelease` succeeds without signing material but emits
`app-release-unsigned.apk`, which cannot be installed or uploaded. To sign,
copy `keystore.properties.example` to `keystore.properties` (git-ignored) and
fill it in; output becomes `app-release.apk`, signed under schemes v2 and v3
(v1 is skipped automatically because `minSdk` is 24).

### Size

The release APK is ~66 MB, almost entirely ML Kit's native libraries — about
16 MB per ABI, shipped for all four. This is deliberate: ABI splits are
mutually exclusive with App Bundles
([issuetracker #402800800](https://issuetracker.google.com/issuetracker#402800800)),
and the bundle is what you publish — Play serves one ABI per install, while
sideloading the APK pulls all four.

## Architecture

```
engine/         TranslationManager, LanguagePair, TranslationError
data/           SettingsStore (DataStore preferences)
processtext/    entry-point intent parsing + its ViewModel
quicktranslate/ overlay permission, floating pill, foreground service
ui/             Compose screens and ViewModels
```

Three things to know before changing this code:

- **`TranslationManager` closes its translator.** An ML Kit `Translator` holds
  native model memory, so caching one across keystrokes leaks until the process
  dies. The outgoing translator is closed on every pair change, in `close()`,
  and before deleting a loaded pack; each ViewModel calls `close()` in
  `onCleared()`.
- **Every ML Kit call is a `suspend` function.** ML Kit returns Play services
  `Task`s; `Tasks.await` on the main thread would freeze the UI, so all of them
  go through `kotlinx-coroutines-play-services`.
- **`ProcessTextIntentParser` is a plain object with no Android imports.** Any
  app can put anything in a `PROCESS_TEXT` intent, so the parsing is unit
  tested instead of living in an Activity that only runs on hardware.

## Design notes

**ML Kit's models are English-pivoted.** There is no direct German→French
model; every non-English pair runs German→English→French, needing three packs
instead of two and losing measurable quality. The UI says so — "Routed via
English — needs 3 packs" — rather than hiding it. Japanese→Korean is a known
upstream defect (the Japanese model emits English) and is rejected with a clear
error: [issuetracker #369752306](https://issuetracker.google.com/issues/369752306).

## Permissions

Six permissions: three arrive transitively from dependencies, three belong to
the floating button, which is off by default.

| Permission | Source | Why |
| --- | --- | --- |
| `INTERNET` | ML Kit | Fetching language packs. |
| `ACCESS_NETWORK_STATE` | ML Kit | The Wi-Fi-only download condition. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | Internal broadcast scoping. |
| `SYSTEM_ALERT_WINDOW` | ours | Draws the floating button over other apps. |
| `FOREGROUND_SERVICE` | ours | Hosts that floating button. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | ours | Android 14+ requires a declared type. |

`SYSTEM_ALERT_WINDOW` has no runtime dialog on any Android version; it must be
granted from a system settings page. No clipboard permission is declared
because none exists — clipboard access is granted by window focus, not by a
permission.

## Limitations

- **`PROCESS_TEXT` does not appear in every app** — see
  [Entry points](#entry-points). Use the share sheet or the floating button
  instead.
- **Requires Google Play services.** Models download through Play services;
  there is no fallback engine, so the app cannot translate on a degoogled
  device.
- **No download progress or size.** `RemoteModelManager.download` exposes
  neither a byte count nor a progress callback; the UI shows an indeterminate
  indicator and names the packs being fetched.
- **Input is capped at 5,000 characters**, translated as a single unit.
  Beyond a few thousand characters the models are slow enough to look hung, so
  longer input is truncated and the user is told.
- **Auto-detect asks instead of guessing below 0.60 confidence** — the
  selection menu often delivers a single word, which carries little evidence.

## Tests

```bash
./gradlew test
```

36 tests: `LanguagePairTest` (pivot rules, pack counts, rejected pairs),
`IncomingTextResolverTest` and `ProcessTextIntentParserTest` (null/blank/
oversized input, the 5,000-char boundary, `CharSequence` that is not a
`String`), and `ProcessTextManifestTest`, which reads the real merged manifest
and asserts the intent filters resolve to the overlay activity, the share
filter claims text but not images, and the overlay service exists and is not
exported.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

ML Kit is a Google product subject to
[Google's ML Kit Terms of Service](https://developers.google.com/ml-kit/terms),
which govern redistribution of the model files and apply on top of this
project's license.
