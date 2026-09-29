# A5 CCTV

Universal Android CCTV client with ONVIF and RTSP support.

## Platforms

- Android phones
- Android tablets
- Android TV / Google TV
- Android TV set-top boxes

## Current version

**0.11**

### Included

- ONVIF discovery in the local network
- ONVIF authentication and media profile discovery
- Automatic RTSP stream URI retrieval
- Manual RTSP URL connection
- Device and group management
- Camera grid: 1 / 4 / 9 / 16
- Full-screen playback
- Android TV remote-friendly focus/navigation
- No camera IP, username, password or location hardcoded in the source

## Build APK on GitHub

1. Create a new GitHub repository, for example `A5CCTV`.
2. Upload the contents of this folder to the repository root.
3. Open **Actions**.
4. Run **Build APK**.
5. After the workflow finishes, open the workflow run and download the `A5CCTV-debug-apk` artifact.

The repository contains a GitHub Actions workflow under:

`.github/workflows/build-apk.yml`

The workflow installs Gradle 8.9 and builds the debug APK automatically.

## Build locally

Use Android Studio with an Android SDK containing API 35, or install Gradle 8.9 and run:

```bash
gradle assembleDebug
```

The APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Important

Passwords and device addresses are entered by the user at runtime. Do not commit real camera passwords, private keys, `local.properties`, or generated APKs to the repository.

## Architecture

The application uses ONVIF as the interoperability layer and RTSP as the video transport. Vendor-specific P2P/cloud modules can be added later without hardcoding one manufacturer into the core application.
