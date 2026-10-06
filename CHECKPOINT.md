# NexusChat-KMM Checkpoint

Updated: October 6, 2026

## Current state

The Android app now uses a WhatsApp-inspired NexusChat shell based on the reference screens in `/home/shafi/Downloads/WhatsApp`.

Implemented screens and navigation:

- Chats list
- Chat detail with streaming AI messages
- Updates / status / channels
- Communities
- Calls
- Profile
- Overflow menu and settings/profile navigation

## UI work

- WhatsApp-style green palette and white surfaces
- Search and archived chat row
- Conversation list with previews and timestamps
- Bottom navigation for Chats, Updates, Communities, and Calls
- Floating action buttons
- Status and channel follow rows
- Profile information layout
- Chat wallpaper, message bubbles, typing/streaming states, Markdown code blocks, copy action, and composer
- Material icons replacing text glyph controls

## Important files

- `shared/src/commonMain/kotlin/com/example/nexuschat/ui/NexusApp.kt`
- `shared/src/commonMain/kotlin/com/example/nexuschat/ui/ChatScreen.kt`
- `app/src/main/kotlin/shafi/example/nexuschat/MainActivity.kt`

## Verification

JDK used:

`/home/shafi/.jdks/jbr-17.0.14`

Verified successfully:

```bash
JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :app:compileDebugKotlin
JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :app:assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Known warnings

Compilation succeeds with non-blocking deprecation warnings for `Icons.Filled.ArrowBack`, `Icons.Filled.Chat`, and `LocalClipboardManager`. These do not block the build.
