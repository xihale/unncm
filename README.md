# unNCM for Android

A native Android app that converts Netease Cloud Music (NCM) files to standard audio formats and enhances music metadata.

## Features

- **NCM Decryption**: Convert encrypted NCM files to MP3/FLAC formats
- **Metadata Enhancement**: Fetch missing metadata, lyrics, and cover art from Netease Cloud Music API
- **Batch Processing**: Handle multiple files with configurable thread count
- **Clean UI**: Material Design interface with real-time progress tracking

## Requirements

- Android 5.0 (API level 26) or higher
- No root required

## Usage

1. Select a folder containing NCM or audio files
2. Configure thread count for batch processing
3. Start conversion - files are saved to an "unlocked" subfolder

## Technology

- **Kotlin** with MVVM architecture
- **OkHttp** for API communication
- **jAudioTagger** for metadata processing
- **Coroutines** for background processing

## Note

This tool is for personal use only. Please respect copyright laws and terms of service.
