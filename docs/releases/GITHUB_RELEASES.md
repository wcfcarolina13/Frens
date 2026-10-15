# GitHub Jar Releases

## What Is Automated
- Every push/PR runs `.github/workflows/ci-build.yml` and uploads the built mod jar as an Actions artifact.
- Every pushed tag matching `v*` runs `.github/workflows/release.yml` and publishes a GitHub Release with the built jar attached.

## Release Flow (Versioned Jars)
1. Update `mod_version` in `gradle.properties`.
2. Commit and push to `main`.
3. Create and push a tag matching the version, e.g. `v1.0.7-release+1.21.11`.
4. Download the jar from GitHub Releases and upload that same jar to Modrinth.

## Platform Notes
- The mod jar is Java/Fabric and runs cross-platform (Windows, Linux, macOS) when Java 21 + matching Minecraft/Fabric are installed.
- There is one build flavour. The `-PaiEnabled=true` flag and its DJL native runtimes were removed in 1.1.251 with the legacy LLM chat path; souls talk to Ollama over HTTP and bundle no native AI libraries.
