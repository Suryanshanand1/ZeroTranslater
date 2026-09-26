# ZeroTranslater

On-device translation for Android, with a system-wide text-selection action.

Paste text in, or select text in any other app and pick **Ztranslate** from the
selection menu. Translation runs entirely on the device — no server, no account,
no API key, no cost per query. Requires Google Play services for model downloads.

## Features

| Feature | Notes |
| --- | --- |
| Manual input | Debounced live translation (450 ms), plus an explicit action on the IME key. |
| Source / target pickers | Searchable. Source supports auto-detect. |
| Swap | Enabled. With auto-detect active it swaps using the detected language, falling back to the default target if nothing has been detected yet. |
| Clear | One tap, visible whenever there is input. |
| Copy translation | Platform clipboard, so Android 13+ shows its own confirmation. |
| Language packs | Per-language download and delete, with a Wi-Fi-only switch. |
| `PROCESS_TEXT` overlay | A bottom sheet over the host app, with Copy and "Open in ZeroTranslater". |

The language list is read at runtime from `TranslateLanguage.getAllLanguages()`,
so nothing is hardcoded.

## Build

Needs JDK 21 to run Gradle (targets Java 17 bytecode), Android SDK platform 36,
build-tools 36.0.0, and a `local.properties` containing `sdk.dir=...`.

```bash
./gradlew assembleDebug          # installable, signed with the debug key
./gradlew assembleRelease        # R8-minified
./gradlew bundleRelease          # .aab - the artifact to publish
./gradlew test                   # 23 unit tests
./gradlew lintDebug
```

Use `gradlew.bat` on Windows.

### Signing

`assembleRelease` succeeds even with no signing material, emitting
`app-release-unsigned.apk` — which cannot be installed or uploaded to Play. Check
the filename, or verify with `apksigner verify --verbose`. To sign, copy
`keystore.properties.example` to `keystore.properties` (git-ignored) and fill it
in; output then becomes `app-release.apk`, verified under signature schemes v2
and v3. v1 is skipped automatically because `minSdk` is 24.

### Size

The release APK is ~66 MB, almost entirely ML Kit's native libraries — about
16 MB per ABI, shipped for all four. This is deliberate: **ABI splits are
mutually exclusive with App Bundles**
([issuetracker #402800800](https://issuetracker.google.com/402800800)), and the
bundle is what you should publish. Play serves one ABI per install; sideloading
the APK pulls all four.

## Architecture

```
engine/      TranslationManager, LanguagePair, TranslationError
data/        SettingsStore (DataStore preferences)
processtext/ PROCESS_TEXT intent parsing + its ViewModel
ui/          Compose screens and ViewModels
```

Two things worth knowing before changing this code:

**`TranslationManager` closes its translator.** A ML Kit `Translator` holds native
model memory, so caching one across keystrokes leaks until the process dies. The
manager closes the outgoing translator on every pair change, in `close()`, and
before deleting a loaded pack. Each ViewModel calls `close()` in `onCleared()`.

**Every ML Kit call is a `suspend` function.** ML Kit returns Play services
`Task`s; `Tasks.await` on the main thread would freeze the UI, so all of them go
through `kotlinx-coroutines-play-services`.

## Design notes

**ML Kit's models are English-pivoted.** There is no direct German→French model;
every non-English pair runs as German→English→French. That needs **three** packs
instead of two, and quality is measurably worse because the text is converted
twice. The UI says so rather than hiding it — the source/target row shows
"Routed via English — needs 3 packs" when it applies.

Japanese→Korean is a known upstream defect (the Japanese model emits English) and
is rejected with a clear error rather than returning garbage.
See [issuetracker #369752306](https://issuetracker.google.com/issues/369752306).

**`ProcessTextIntentParser` is a plain object with no Android imports.** The
`PROCESS_TEXT` contract is the highest-risk surface in the app, since any app can
put anything in those extras, so the logic is unit tested rather than buried in
an Activity that can only be exercised on hardware.

## Permissions

The app declares **no** `<uses-permission>` of its own and opens no sockets. The
merged manifest contains exactly three, all contributed by dependencies:

| Permission | Source | Why |
| --- | --- | --- |
| `INTERNET` | ML Kit | Fetching language packs. |
| `ACCESS_NETWORK_STATE` | ML Kit | The Wi-Fi-only download condition. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | Internal broadcast scoping. |

Removing the ML Kit dependencies removes all three.

## Limitations

**`PROCESS_TEXT` does not appear in every app.** The host app must implement the
`ActionMode` text-selection API. WebView-based apps, games, and many custom
editors do not, so the entry is simply absent there. Some OEM ROMs — notably
MIUI — strip third-party `PROCESS_TEXT` handlers outright. None of this is
fixable from the app side.

**Requires Google Play services.** Models download through Play services, so the
app cannot translate on a degoogled device. There is no fallback engine.

**No download progress or size.** `RemoteModelManager.download` resolves with a
bare `Task<Void>`, and on-device translate models expose neither a byte count nor
a progress callback. The UI shows an indeterminate indicator and names the packs
being fetched.

**Input is capped at 5,000 characters,** translated as a single unit rather than
sentence by sentence. Beyond a few thousand characters the models are slow enough
to look hung, so longer input is truncated and the user is told.

**Auto-detect asks instead of guessing below 0.60 confidence.** `PROCESS_TEXT`
often delivers a single word, which carries little evidence.

## Tests

```bash
./gradlew test
```

23 tests: `LanguagePairTest` (pivot rules, pack counts, rejected pairs),
`ProcessTextIntentParserTest` (null/blank/oversized input, the 5,000-char boundary,
`CharSequence` that is not a `String`), and `ProcessTextManifestTest`, which reads
the **real merged manifest** and asserts the `PROCESS_TEXT` intent resolves to the
overlay activity with the right label, that it is exported, and that it is
excluded from recents.

That last one matters most: a typo in the intent filter or a missing
`category.DEFAULT` yields an app that builds, passes everything else, and never
appears in any selection menu.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

ML Kit is a Google product subject to
[Google's ML Kit Terms of Service](https://developers.google.com/ml-kit/terms),
which restrict certain uses of the models and govern redistribution of the model
files. Those terms apply on top of this project's license and are the main
constraint on how this app can be distributed.
