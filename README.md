# unNCM for Android

A lightweight, efficient Android application designed to unlock Netease Cloud Music (`.ncm`) files directly on your device.

## 🚀 Features

- **Batch Processing**: Scan and unlock multiple `.ncm` files in a selected directory simultaneously.
- **Multi-Threaded Conversion**: Leverages Kotlin Coroutines for parallel processing, ensuring fast and efficient unlocking of large file batches.
- **Automatic Output Management**: Automatically creates an `unlocked` folder within your input directory for organized output.
- **Modern Android Integration**:
  - Utilizes the Storage Access Framework (SAF) via `DocumentFile` API for secure and broad file system access (including SD cards).
  - Supports modern Android permissions handling with `ActivityResultLauncher`.
- **Format Retention**: Preserves original audio metadata and cover art (where applicable) by extracting the underlying audio stream (MP3, FLAC, etc.).
- **User-Friendly Interface**: Simple, clean UI with Material Design components.

## 🛠 Technology Stack

- **Language**: Kotlin
- **Platform**: Android (Min SDK 26, Target SDK 34)
- **Build System**: Gradle
- **Key Libraries**:
  - `androidx.documentfile`: For robust file access across modern Android versions.
  - `androidx.activity`: For modern result registration APIs.
  - `kotlinx.coroutines`: For efficient background processing of file decryption.
  - `ViewBinding`: For type-safe view interaction.

## 📦 Installation & Build

### Prerequisites
- Android Studio Koala or newer.
- JDK 17 or higher.

### Build Steps
1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/unncm.git
   ```
2. **Open in Android Studio**: Select the project root directory.
3. **Sync Gradle**: Allow Android Studio to download dependencies and configure the project.
4. **Run**: Connect an Android device or start an emulator and click **Run**.

## 📖 Usage

1. **Select Input Folder**: Tap the folder icon next to the input field to choose the directory containing your `.ncm` files.
2. **Automatic Setup**: The app will automatically set the output directory to a subfolder named `unlocked` and scan for `.ncm` files.
3. **Unlock**: Tap the **Convert** button to start the unlocking process.
4. **View Results**: The interface will update to show the progress, and your unlocked files will appear in the `unlocked` folder.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is open-source.
