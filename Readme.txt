========================================================================
🔍 Lost & Found — Intelligent Lost/Found & Facial Recognition System
========================================================================

This is an intelligent Lost & Found and Missing Persons/Pets Tracking System built using Android (Kotlin + Jetpack Compose). In addition to standard loss reporting and map-based check-ins, the application leverages TensorFlow Lite (YoloFace + MobileFaceNet) machine learning models for facial embedding comparison, paired with Firebase cloud services for data management and real-time alerts.

------------------------------------------------------------------------
🚀 DIRECT APK DOWNLOAD (FOR QUICK EVALUATION)
------------------------------------------------------------------------
To help you easily evaluate and run the project without compiling the source code, a pre-compiled APK installer is placed directly at the root directory of this repository:

Download APK Link:
https://github.com/dx0018/LostFound/raw/main/LostFound.apk

* Tip: Download this APK file and install it on a physical Android device (recommended) or an Android Studio Emulator to run and test it immediately.

------------------------------------------------------------------------
💻 TOOLS & REQUIRED SOFTWARE (WITH DOWNLOAD LINKS)
------------------------------------------------------------------------
To execute, compile, or modify the source code, you will need the following tools installed on your local computer:

1. IDE: Android Studio (Jellyfish / Koala version or newer is recommended)
   - Purpose: Official Android Development Environment
   - Version: 2023.3.1+
   - Download Link: https://developer.android.com/studio

2. Java Development Kit (JDK): Java 17
   - Purpose: Standard compilation environment for Gradle and Kotlin
   - Version: JDK 17
   - Download Link: https://adoptium.net/temurin/releases/?version=17
   - Note: Android Studio usually comes bundled with JDK 17 automatically, which is used by default.

3. Android SDK:
   - Minimum SDK: API Level 24 (Android 7.0)
   - Target SDK: API Level 36 (Android 14/15)
   - Download/Install Method: Installed and managed directly within Android Studio via "Tools -> SDK Manager".

4. Gradle Build Tool:
   - Version: Gradle 8.13 (Configured via Gradle wrapper)
   - Download Method: Automatically downloaded by the project's gradle wrapper (gradlew) when you build the project. No manual download is required.

------------------------------------------------------------------------
📚 CORE LIBRARIES & DEPENDENCIES
------------------------------------------------------------------------
All dependencies are configured inside the build.gradle.kts file and will automatically download during Gradle sync:

- Jetpack Compose (Material 3): Modern declarative UI library.
- Firebase Client SDK (Auth, Firestore, Storage): Provides real-time cloud data storage, database synchronization with offline caching, and user authentication.
- TensorFlow Lite (org.tensorflow:tensorflow-lite:2.14.0): For on-device machine learning inference.
- Google Maps SDK for Android (maps-compose:2.14.0): Interactive maps and marker rendering.
- Coil Compose: Asynchronous image loading for Compose UI.

------------------------------------------------------------------------
📦 DATABASE & DATASET INFORMATION
------------------------------------------------------------------------
- Active Database: The project uses Google Firebase (Cloud Firestore) to store active missing cases and user-submitted sightings. A developer test Firebase configuration file (app/google-services.json) has been pre-configured and included in this repository for instant use.
- ML Models: The pre-trained YOLO Face detector and MobileFaceNet facial feature extraction models (.tflite format) are pre-loaded in the assets directory:
  - Path: app/src/main/assets/MobileFaceNet.tflite
  - Path: app/src/main/assets/yolov8n_192_192_face.tflite
  No external database or model dataset downloads are required to run this app.

------------------------------------------------------------------------
🛠️ INSTRUCTIONS TO RUN THE SOURCE CODE
------------------------------------------------------------------------
Please follow the steps below to compile and execute the project from source code:

Method A: Running via Android Studio (Recommended)
---------------------------------------------
1. Download or clone this repository to your local computer.
2. Open Android Studio.
3. Click "File" -> "Open", navigate to the directory where you cloned the project, and select the root folder of this project (which contains settings.gradle.kts).
4. Wait for Android Studio to import the project and finish the Gradle Sync (this will automatically download all required dependencies and libraries).
5. Connect your Android physical phone to your computer via USB (make sure "USB Debugging" is enabled in Developer Options), or launch a virtual emulator from the Device Manager.
6. Click the green "Run" (Play icon) button in the top toolbar to build and install the app on your device.

Method B: Building via Command Line (Gradle CLI)
---------------------------------------------
You can build the APK directly using the Gradle wrapper script included in the root folder:

- On Windows (PowerShell or Command Prompt):
  .\gradlew.bat assembleDebug

- On macOS / Linux:
  chmod +x gradlew
  ./gradlew assembleDebug

Upon successful completion, the compiled APK will be generated and saved at:
[Project Root]/app/build/outputs/apk/debug/app-debug.apk

To install the APK directly to an attached device/emulator via CLI:
- On Windows:
  .\gradlew.bat installDebug
- On macOS / Linux:
  ./gradlew installDebug

========================================================================
✨ Core Application Features Summary:
- Face Match ML: Extracts 192-dimensional face embeddings and compares cosine similarity to match sightings against reported missing cases.
- Interactive Tracking: Pinpoints where items were lost and where they were sighted on Google Maps.
- Timeline Progress: Detailed chronological track record of all sightings.
- Real-time Alerts: Pushes system notifications to owners when a face match is found.
========================================================================
