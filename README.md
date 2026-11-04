# Pixel Agent 4.0 — a personal LLM agent for Android

A pure-Kotlin (Jetpack Compose) app. You bring an LLM (Claude, OpenAI, OpenRouter, Gemini, Ollama/llama.cpp...), the app gives it tools to act on your phone: files, archives, web, device actions and (optionally) full screen control.

> **Honest status:** the agent core (`core/`: LLM clients, agent loop, tools, stores) was compiled and tested on a JVM (89 checks pass). The Android/Compose layer could not be compiled where this was written (no access to Google Maven), so **the APK has not been built yet**. It was carefully reviewed, but expect to fix a small compile error or two on first build (the on-device engine code in `platform/LiteRtClient.kt` was written from Google's docs and is the part most likely to need a tweak). Build it with GitHub Actions or Android Studio (below).

## Build
**GitHub Actions (no setup):** push this folder to a GitHub repo, open Actions → "Build APK" → download `PixelAgent-debug-apk`.
**Android Studio:** open the folder, let Gradle sync, Run. **CLI:** `./gradlew assembleDebug` (JDK 17 + Android SDK 35) → `app/build/outputs/apk/debug/`.
Install the APK (allow "install unknown apps").

## First run
1. Providers tab → pick a preset, paste your API key, "Fetch models", "Test".
2. Settings → grant: All-files access, Notifications; optionally Accessibility (turn on "Pixel Agent"; on Android 13+ first open App info → ⋮ → *Allow restricted settings*) and Overlay.
3. Chat. Share files/images from other apps to Pixel Agent to attach them.

## On-device model (no server, no Termux)
Add a provider → **On-device model**. Download a `.litertlm` model file to your phone (Google's LiteRT Community on Hugging Face has Gemma builds; some need a free login/licence acceptance), then tap **Pick model file…**. The app copies it into its own storage once and runs it itself with Google's LiteRT-LM engine, fully offline. You can also paste a path like `/sdcard/Download/model.litertlm`.
- Needs a fairly recent phone: models are ~1-4 GB, loading takes several seconds, and replies run on the CPU.
- Tools work in "Text" mode (prompt-based). Small models follow tool instructions less reliably than Claude/GPT; use ones with 2B+ parameters and keep Confirm mode on.
- The model's own context window is small (often 4-32k tokens), so long chats are trimmed automatically.
- The LiteRT-LM dependency is set to `latest.release`; if a future release changes its API, pin an older version in `app/build.gradle.kts`.
- You can still use Ollama/llama.cpp or any cloud provider; they're optional.

## Tools
Files (list/read/write/edit/delete/move/copy/search/info/organize/find duplicates), archives (zip/cbz/7z create+extract, zip-slip safe), web_search, web_fetch, download_file, http_request, reverse_image_search, device tools (time, info, apps, open app/url, alarm, timer, clipboard, notify, wait), screen_* phone control, shell, persistent memory, ask_user.

## Safety
- Confirm modes: ask for everything / ask for risky only / never ask. Dangerous tools (screen control, shell, delete) are gated by default.
- Files are sandboxed to the workspace unless "Allow access outside the workspace" is on.
- Web content is wrapped as untrusted, but **prompt injection is a real risk**: don't let the agent browse unknown pages with shell/screen control on and approvals off.
- API keys are stored in the app's private storage, not encrypted.

## Structure
`core/` Android-free agent logic · `platform/` Android bindings (settings, services, accessibility) · `ui/` Compose screens · `AgentViewModel.kt` · `MainActivity.kt`.
**Add a tool:** create a `Tool(name, description, Risk, objectSchema(...)) { args, ctx -> "result" }` and add it to `ToolRegistry.default()` in `core/Tools.kt`.

## Troubleshooting
- Agent can't see the screen → accessibility service off or restricted settings not allowed.
- "Cleartext" errors → allowed in the manifest; check the base URL.
- Model rejects tools → set the provider's tool mode to TEXT or NONE.
