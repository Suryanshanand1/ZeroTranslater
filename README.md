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
| Share-sheet target | ZeroTranslater appears in the Share menu for any plain text. |
| Floating button | An optional draggable pill over all apps. Tap it to translate what you last copied. |

The language list is read at runtime from `TranslateLanguage.getAllLanguages()`,
so nothing is hardcoded.

## The floating button, and why it is not a clipboard listener

Text can reach the overlay three ways: the `PROCESS_TEXT` selection menu, the
`ACTION_SEND` share sheet, and the floating button. The share sheet is the one
that works inside WebView-based apps such as NotebookLM, which never send
`PROCESS_TEXT`. The floating button exists for everything else.

The obvious design — watch the clipboard, pop a bubble on every copy — **cannot be
built**, and it is worth being precise about why, because the workaround is widely
misdescribed:

- A background `ClipboardManager.OnPrimaryClipChangedListener` is **never called
  at all**. `ClipboardService.sendClipChangedBroadcast()` gates the dispatch
  itself on `clipboardAccessAllowed(OP_READ_CLIPBOARD, ...)`, so the callback does
  not fire and hand you a null — it simply does not fire.
- `AccessibilityService` is **not** an exemption. AOSP's `ClipboardService.java`
  contains no occurrence of the string `accessib` at all. The claim that
  accessibility services can read the clipboard in the background is not true in
  AOSP; it persists because some OEM forks ship the exemption.
- The complete allow-list is: the default IME, an app currently holding window
  focus, SystemUI, Content Capture, Augmented Autofill, VirtualDevice owners, and
  holders of the signature-only `READ_CLIPBOARD_IN_BACKGROUND`.

There *is* a technique that works — a small **focusable** overlay window, which
makes the app's UID genuinely focused so the read is permitted. Clipboard-manager
apps on the Play Store use it. This project does not, for two reasons: the
focusable window steals input focus from the app underneath, so you could not
keep typing in the very app you are translating; and silently harvesting
everything the user copies is the exact behaviour Android 10 blocked, which exists
to stop apps reading 2FA codes and passwords out of the clipboard.

So the button is a deliberate, user-initiated trigger instead. **The tap is what
grants focus**: it starts a transparent `ProcessTextActivity`, whose whole job is
to hold a focused window long enough for one clipboard read.

One detail there is sharper than it looks. The activity shows its result in a
material3 `ModalBottomSheet`, and current material3 composes that sheet into its
**own dialog window** — so the dialog, not the activity's window, is what the
platform focuses; the activity window behind it stays unfocused indefinitely.
Waiting on the activity's `onWindowFocusChanged` alone therefore never resolves,
and the sheet spins forever. The read is triggered from the **sheet's window**
focus, with the activity's callback kept as a second trigger, and a flag
collapses both into exactly one read. The button itself is `FLAG_NOT_FOCUSABLE`,
so it never takes focus while it is merely sitting on screen.

Cost: one tap per translation, rather than zero. Benefit: no polling, no
clipboard listener, no focus theft, and nothing that could read a password.


## Build

Needs JDK 21 to run Gradle (targets Java 17 bytecode), Android SDK platform 36,
build-tools 36.0.0, and a `local.properties` containing `sdk.dir=...`.

```bash
./gradlew assembleDebug          # installable, signed with the debug key
./gradlew assembleRelease        # R8-minified
./gradlew bundleRelease          # .aab - the artifact to publish
./gradlew test                   # 36 unit tests
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

ZeroTranslater opens no sockets of its own; all translation is on device. The
merged manifest contains six permissions: three arrive transitively from
dependencies, three belong to the optional floating button.

| Permission | Source | Why |
| --- | --- | --- |
| `INTERNET` | ML Kit | Fetching language packs. |
| `ACCESS_NETWORK_STATE` | ML Kit | The Wi-Fi-only download condition. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | Internal broadcast scoping. |
| `SYSTEM_ALERT_WINDOW` | ours | Draws the floating button over other apps. |
| `FOREGROUND_SERVICE` | ours | Hosts that floating button. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | ours | Android 14+ requires a declared type. |

The bottom three are only exercised if you turn the floating button on, and
Android provides no runtime dialog for `SYSTEM_ALERT_WINDOW` — it must be granted
from a system settings page. Removing the ML Kit dependencies removes the first
three; leaving the floating button off leaves the last three declared but unused.

**No clipboard permission is declared, because none exists.** Clipboard access is
not a permission; it is granted by *window focus*. See
[The floating button](#the-floating-button-and-why-it-is-not-a-clipboard-listener).

## Limitations

**`PROCESS_TEXT` does not appear in every app.** The host app must implement the
`ActionMode` text-selection API. WebView-based apps, games, and many custom
editors do not, so the entry is simply absent there. Some OEM ROMs — notably
MIUI — strip third-party `PROCESS_TEXT` handlers outright. None of this is
fixable from the app side; use the share sheet or the floating button instead.

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

36 tests: `LanguagePairTest` (pivot rules, pack counts, rejected pairs),
`IncomingTextResolverTest` and `ProcessTextIntentParserTest` (null/blank/oversized
input, the 5,000-char boundary, `CharSequence` that is not a `String`), and
`ProcessTextManifestTest`, which reads the **real merged manifest** and asserts
that the `PROCESS_TEXT` and `ACTION_SEND` intents resolve to the overlay activity,
that the share filter claims text but not images, that the overlay service exists
and is not exported, and that `SYSTEM_ALERT_WINDOW` is declared.

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
