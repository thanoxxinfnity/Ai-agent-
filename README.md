# Aura

Android AI agent app (Kotlin, Jetpack Compose). Chats with any OpenAI-compatible endpoint
(Gemini, NVIDIA, custom, Chat Completions or Responses format), creates GitHub repositories,
pushes code and builds APKs through GitHub Actions. Runs continue in the background with a
foreground service and notifications. Chat history is stored locally (Room) and backed up to Supabase.

Build: `gradle :app:assembleRelease` (the workflow in `.github/workflows/build-apk.yml` does this and publishes a release).
