# PhonDrive

Mount your Android phone's storage as a native Windows drive — browse, edit, copy, and move files in Explorer over [Tailscale](https://tailscale.com).

```
┌─────────────────┐     Tailscale      ┌─────────────────┐
│  Android Phone  │ ◄──── tunnel ────► │   Windows PC    │
│  WebDAV Server  │ ──── HTTP:8080 ───► │  rclone mount   │
│  (Kotlin/Ktor)  │                     │  (Z: drive)     │
└─────────────────┘                     └─────────────────┘
```

## Features

- **Native Explorer integration** — phone storage appears as a regular drive letter (Z:)
- **Works anywhere** — Tailscale tunnel means phone and PC don't need to be on the same network
- **One-click mount** — system tray app mounts/unmounts with a single click
- **Full file operations** — create, read, rename, move, copy, delete files directly from Explorer
- **Auto-launch** — tray app can start on Windows login and auto-mount

## Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Android | 7.0+ (API 24) | |
| Tailscale | Latest | Same account on phone and PC |
| Windows | 10+ | |
| rclone | 1.65+ | WebDAV client |
| WinFsp | 2.0+ | Required by rclone for drive mounting |

## Quick Start

### 1. Install on Phone

Download `PhonDrive.apk` from [Releases](https://github.com/RainMT-dv/phondrive/releases) and install it. Grant "All Files Access" when prompted.

Open the app, enter your phone's Tailscale IP, and tap **Ligar servidor** (Start Server).

### 2. Install on Windows

Download `PhonDrive-Tray.exe` from [Releases](https://github.com/RainMT-dv/phondrive/releases). Run it — the tray icon appears in the Notification Area.

> [!NOTE]
> If the tray icon doesn't appear, check the `^` overflow arrow next to the clock.

### 3. Mount

Right-click the tray icon → **Mount**. Your phone storage appears as `Z:\` in Explorer.

### 4. Browse

Open Explorer → **This PC** → **PhonDrive (Z:)** — done.

## Build from Source

### Android

```bash
cd android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK with `platforms;android-34` and `build-tools;34.0.0`.

### Windows Tray (Python)

```bash
cd windows
pip install -r requirements.txt
python tray_app.py
```

### Windows Tray (.exe)

```bash
pip install pyinstaller
pyinstaller --onefile --noconsole --name PhonDrive-Tray tray_app.py
```

### Windows Tray (Rust)

```bash
cd windows/phondrive-tray
cargo build --release
# Output: target/release/phondrive-tray.exe
```

## Configuration

### rclone Config

Auto-created by the tray app, or set up manually at `%APPDATA%\rclone\rclone.conf`:

```ini
[phondrive]
type = webdav
url = http://<PHONE_IP>:8080
vendor = other
user = user
pass = pass
```

### Default Credentials

- **Username:** `user`
- **Password:** `pass`

> [!WARNING]
> Change these before exposing the server beyond your local Tailscale network.

### Tray App Config

| Version | Location |
|---------|----------|
| Python | `~/.phondrive/config.json` |
| Rust | `%APPDATA%/phondrive/config.toml` |

## Testing

```powershell
# Run the full E2E test suite
.\scripts\verify-e2e.ps1

# Or test manually with curl
curl http://<PHONE_IP>:8080/ping -u user:pass
```

## Project Structure

```
phondrive/
├── android/                    # Android WebDAV server
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/phondrive/webdavspike/
│   │       ├── MainActivity.kt      # UI + permissions
│   │       ├── WebDavServer.kt      # WebDAV server (Ktor)
│   │       └── WebDavService.kt     # Foreground service
│   └── build.gradle.kts
├── windows/                    # Windows tray apps
│   ├── tray_app.py             # Python (pystray)
│   ├── phondrive-tray/         # Rust (tray-icon)
│   └── requirements.txt
├── scripts/                    # Automation scripts
│   ├── verify-e2e.ps1          # Full E2E verification
│   ├── validate-webdav.ps1     # WebDAV API tests
│   ├── setup-windows.ps1       # One-time Windows setup
│   └── build-windows.ps1       # PyInstaller build
└── dist/                       # Pre-built binaries
    ├── android/PhonDrive.apk
    └── windows/PhonDrive-Tray.exe
```

## Known Limitations

- **rclone required** — Windows built-in `net use` cannot route WebDAV through Tailscale's virtual adapter. rclone + WinFsp is used instead.
- **Doze mode** — Android may kill the foreground service after 8+ hours with screen off. Exempt the app from battery optimization for reliability.
- **No file locking** — editing the same file on phone and PC simultaneously may cause corruption.
- **Phone to PC only** — one-directional. PC reads from phone; no PC-to-phone push.
- **Tailscale required** — Basic auth over HTTP is only safe inside Tailscale's encrypted tunnel.

## Security

- Credentials are transmitted as Base64 (trivially decodable) — **only safe over Tailscale**
- Never expose the WebDAV server to the open internet
- The APK requests broad storage permissions required for the server to function

## License

[MIT](LICENSE)
