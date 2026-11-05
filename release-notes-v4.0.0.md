# Pixel AI 4.0.1

## Highlights
- Android app package: com.pixelagent.app
- Version name: 4.0.1
- Version code: 5
- Release artifact: app-release.apk

## Included capabilities
- Local-first AI chat with on-device model runtime
- File and archive tools
- Web search/fetch/download tools
- Reverse image search helpers
- Device actions for apps, URLs, alarms, timers, notifications, clipboard, and wait
- Screen, shell, memory, and user-interaction tool hooks
- User-controlled local/cloud routing with explicit cloud-fallback consent and inference timeout limits
- Gemini thought-signature preservation through tool calls, chat persistence, and provider switching
- Tool execution checkpoints and interrupted-operation recovery to prevent automatic duplicate actions
- Project-scoped searchable memory, opt-in document indexing, PDF text extraction, and on-device OCR
- Image requests limited to models with user-verified image-input support

## Build status
- Release APK built and verified with Java 17 and Android SDK 35
- Target: https://github.com/jettex161-cmd/Clone-

## Notes
- The LiteRT-LM CPU backend is used. The actual Gemma model file is not bundled and must be imported by the user.
- Device-specific inference performance has not been validated on Snapdragon 685 or Dimensity 8350 Ultra hardware.
- The release APK uses the project's debug signing key for sideloading; it is not Play Store-signed.
- The app is intended for local and privacy-aware agent workflows with explicit user approval for sensitive actions.
