# PhonDrive

Expose your Android phone's storage as a network drive on Windows — browse, edit, copy, move files natively in Explorer over Tailscale.

## How It Works

```
┌─────────────────┐     Tailscale      ┌─────────────────┐
│  Android Phone  │ ◄──── tunnel ────► │   Windows PC    │
│                 │                     │                 │
│  WebDAV Server  │ ──── HTTP:8080 ───► │  rclone mount   │
│  (Kotlin/Ktor)  │                     │  (Z: drive)     │
└─────────────────┘                     └─────────────────┘
```

1. **Android app** runs a WebDAV server on port 8080, serving files from your phone's storage
2. **Windows tray app** mounts the WebDAV server as a native drive letter (Z:) using rclone + WinFsp
3. **Tailscale** provides the encrypted tunnel — works across different networks (no Wi-Fi required)

## Requirements

### Android
- Android 7.0+ (API 24)
- [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) installed and connected
- Grant "All Files Access" permission when prompted

### Windows
- Windows 10/11
- [Tailscale](https://tailscale.com/download) installed and connected (same account as phone)
- [rclone](https://rclone.org/downloads/) v1.65+ 
- [WinFsp](https://winfsp.dev/rel/) v2.0+ (required by rclone for drive mounting)
- Python 3.8+ (for tray app) OR the pre-built .exe

## Quick Start

### 1. Install Android App

Build from source:
```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or install the pre-built APK (see Releases).

On first launch:
1. Grant "All Files Access" permission
2. Tap **Start Server** — note the IP address shown (e.g., `100.84.246.7:8080`)

### 2. Setup Windows

**Option A: Run the tray app (recommended)**
```bash
cd windows
pip install -r requirements.txt
python tray_app.py
```

**Option B: Build the .exe**
```bash
cd windows
pip install -r requirements.txt pyinstaller
.\..\scripts\build-windows.ps1
# Output: dist/tray_app.exe
```

**Option C: Use the Rust tray app**
```bash
cd windows/phondrive-tray
cargo build --release
# Output: target/release/phondrive-tray.exe
```

### 3. Mount the Drive

The tray app auto-discovers your phone's Tailscale IP. Click the tray icon → **Mount**.

Or manually:
```powershell
rclone mount phondrive:/ Z: --volname PhonDrive --vfs-cache-mode writes --network-mode --dir-cache-time 5s
```

### 4. Browse Files

Open Explorer → **This PC** → **PhonDrive (Z:)** — your phone's files are now a native drive.

## Configuration

### Default Credentials
- **Username:** `user`
- **Password:** `pass`

Change these in the Android app's settings (Todo 5+).

### rclone Config
Auto-created by `scripts/setup-windows.ps1` or manually:
```ini
[phondrive]
type = webdav
url = http://<PHONE_IP>:8080
vendor = other
user = user
pass = <obfuscated>
```

### Tray App Config
Stored at `~/.phondrive/config.json` (Python) or `%APPDATA%/phondrive/config.toml` (Rust).

## Building

### Android
```bash
cd android
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (needs signing config)
```

### Windows (Python)
```bash
cd windows
pip install -r requirements.txt
python tray_app.py
```

### Windows (Rust)
```bash
cd windows/phondrive-tray
cargo build --release
```

### Windows (.exe via PyInstaller)
```bash
.\scripts\build-windows.ps1
```

## Testing

### WebDAV Server Tests
```bash
cd android
./gradlew test
```

### E2E Verification
```powershell
.\scripts\verify-e2e.ps1 -IP "100.84.246.7" -Port 8080
```

### Manual curl Test
```bash
# Ping
curl http://100.84.246.7:8080/ping -u user:pass

# List root
curl -X PROPFIND http://100.84.246.7:8080/ -u user:pass \
  -H "Depth: 1" -d '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
```

## Project Structure

```
Rain_COFG/
├── android/                    # Android WebDAV server app
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/phondrive/webdavspike/
│   │       ├── MainActivity.kt      # UI + permission flow
│   │       ├── WebDavServer.kt      # Ktor WebDAV server
│   │       └── WebDavService.kt     # Foreground service
│   └── build.gradle.kts
├── windows/                    # Windows tray apps
│   ├── tray_app.py             # Python tray (pystray)
│   ├── requirements.txt
│   └── phondrive-tray/         # Rust tray (tray-icon)
│       ├── src/main.rs
│       └── Cargo.toml
├── scripts/
│   ├── setup-windows.ps1       # One-time Windows setup
│   ├── mount-phonedrive.ps1    # Manual mount script
│   ├── build-windows.ps1       # PyInstaller build
│   ├── validate-webdav.ps1     # WebDAV API tests
│   └── verify-e2e.ps1          # Full E2E verification
└── README.md
```

## Known Limitations

- **Windows WebClient (net use) doesn't work with Tailscale** — the built-in Mini-Redirector cannot route HTTP WebDAV through virtual network adapters. We use rclone + WinFsp instead.
- **50MB file size limit** — Windows WebClient caps uploads at 50MB. rclone bypasses this, but very large files may be slow over Tailscale.
- **Doze mode** — Android may kill the foreground service after 8+ hours with screen off. Exempt the app from battery optimization for best results.
- **No file locking** — editing the same file on phone and PC simultaneously may cause corruption.
- **Single direction** — phone→PC only (PC pulls files). No PC→phone push.
- **Tailscale required** — Basic auth over HTTP is only safe inside the Tailscale tunnel.

## Security

- Basic auth credentials are base64-encoded (trivially decodable) — **only safe over Tailscale's encrypted tunnel**
- Never expose the WebDAV server to the open internet
- Change default credentials before regular use
- The APK requests broad storage permissions — this is required for the WebDAV server to function

## License

MIT
