# unNCM for Android

A native Android app that converts Netease Cloud Music (NCM) files to standard audio formats and enhances music metadata.

## Technology

- **Kotlin** with MVVM architecture
- **jAudioTagger** for metadata processing

## Note

This tool is for personal use only. Please respect copyright laws and terms of service.

Semi-Vibe Coding Project, half of the code is audited.

## Version & Release

- Tag format: `vMAJ.MIN.PATCH-(alpha|beta|rc|release).N`
- Example tags: `v1.4.0-alpha.1`, `v1.4.0-release.0`
- GitHub Actions workflow `.github/workflows/release-on-tag.yml` will build and publish a release automatically when a matching tag is pushed.

Required repository secrets:

- `UNNCM_RELEASE_STORE_FILE_BASE64`: base64 content of `.jks`
- `UNNCM_RELEASE_STORE_PASSWORD`
- `UNNCM_RELEASE_KEY_ALIAS`
- `UNNCM_RELEASE_KEY_PASSWORD`
