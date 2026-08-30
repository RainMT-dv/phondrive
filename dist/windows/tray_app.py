"""
PhonDrive Windows Tray App
Mounts Android phone storage as a Windows drive via rclone + WebDAV over Tailscale.
"""
import os
import sys
import json
import time
import subprocess
import threading
import ctypes
import winreg
from pathlib import Path

try:
    import pystray
    from PIL import Image, ImageDraw
    HAS_PYSTRAY = True
except ImportError:
    HAS_PYSTRAY = False
    print("WARNING: pystray/Pillow not installed. Run: pip install pystray Pillow")

# ── Config ────────────────────────────────────────────────────────────────

CONFIG_DIR = Path.home() / ".phondrive"
CONFIG_FILE = CONFIG_DIR / "config.json"

DEFAULT_CONFIG = {
    "phone_ip": "",
    "port": 8080,
    "user": "user",
    "pass": "pass",
    "drive_letter": "Z",
    "rclone_path": "rclone",
    "tailscale_hostname": "cll-a",
    "auto_mount": True,
    "vfs_cache_mode": "writes",
    "dir_cache_time": "5s",
}

# ── Globals ───────────────────────────────────────────────────────────────

config = {}
mount_process = None
is_mounted = False
tray_icon = None

# ── Config management ─────────────────────────────────────────────────────

def load_config():
    global config
    CONFIG_DIR.mkdir(exist_ok=True)
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE, "r") as f:
            config = {**DEFAULT_CONFIG, **json.load(f)}
    else:
        config = DEFAULT_CONFIG.copy()
        save_config()

def save_config():
    CONFIG_DIR.mkdir(exist_ok=True)
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

# ── Tailscale IP discovery ───────────────────────────────────────────────

def get_phone_ip():
    """Try to discover phone IP from tailscale status."""
    hostname = config.get("tailscale_hostname", "")
    if not hostname:
        return None
    
    try:
        result = subprocess.run(
            ["tailscale", "status"],
            capture_output=True, text=True, timeout=10,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )
        if result.returncode == 0:
            for line in result.stdout.splitlines():
                if hostname.lower() in line.lower():
                    parts = line.split()
                    for part in parts:
                        if part.startswith("100.") and "." in part[4:]:
                            return part
    except Exception:
        pass
    return None

# ── rclone mount/unmount ─────────────────────────────────────────────────

def build_rclone_url():
    ip = config["phone_ip"]
    port = config["port"]
    return f"http://{ip}:{port}"

def mount_drive():
    global mount_process, is_mounted
    
    if is_mounted:
        return True, "Already mounted"
    
    # Get IP
    ip = config["phone_ip"]
    if not ip:
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()
    
    if not ip:
        return False, "Phone IP not configured"
    
    # Check rclone exists
    rclone = config["rclone_path"]
    try:
        subprocess.run([rclone, "version"], capture_output=True, timeout=5,
                      creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
    except FileNotFoundError:
        return False, "rclone not found. Install: winget install Rclone.Rclone"
    
    # Build remote name
    remote = f"phondrive:{config['drive_letter']}:"
    
    # Check if remote exists, create if not
    try:
        result = subprocess.run(
            [rclone, "listremotes"],
            capture_output=True, text=True, timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )
        if "phondrive:" not in result.stdout:
            # Create remote config
            url = build_rclone_url()
            config_content = f"""[phondrive]
type = webdav
url = {url}
vendor = other
user = {config['user']}
pass = {rclone_obfuscate(config['pass'])}
"""
            # ponytail: write to default rclone location so `rclone mount` finds it
            rclone_config_dir = Path.home() / "AppData" / "Roaming" / "rclone"
            rclone_config_dir.mkdir(parents=True, exist_ok=True)
            rclone_config_file = rclone_config_dir / "rclone.conf"
            
            # Append or create
            existing = ""
            if rclone_config_file.exists():
                existing = rclone_config_file.read_text()
            
            if "[phondrive]" not in existing:
                with open(rclone_config_file, "a") as f:
                    f.write(config_content)
    except Exception as e:
        return False, f"Failed to check/create rclone remote: {e}"
    
    # Mount
    drive = config["drive_letter"] + ":"
    url = build_rclone_url()
    
    cmd = [
        rclone, "mount", "phondrive:/", drive,
        "--volname", "PhonDrive",
        "--vfs-cache-mode", config["vfs_cache_mode"],
        "--network-mode",
        "--dir-cache-time", config["dir_cache_time"],
    ]
    
    try:
        mount_process = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )

        time.sleep(5)
        
        # Verify mount by checking drive
        check = subprocess.run(
            ["dir", drive + "\\"],
            capture_output=True, text=True, timeout=5,
            shell=True,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )
        
        if check.returncode == 0:
            is_mounted = True
            return True, f"Mounted at {drive}\\"
        else:
            return False, f"Mount may have failed: {check.stderr}"
            
    except Exception as e:
        return False, f"Mount failed: {e}"

def unmount_drive():
    global mount_process, is_mounted
    
    drive = config["drive_letter"] + ":"
    
    try:
        # Kill rclone processes for this drive
        subprocess.run(
            ["taskkill", "/F", "/IM", "rclone.exe"],
            capture_output=True, timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW
        )
        mount_process = None
        is_mounted = False
        return True, f"Unmounted {drive}"
    except Exception as e:
        return False, f"Unmount failed: {e}"

def rclone_obfuscate(password):
    """rclone obscure password (basic obfuscation)."""
    try:
        result = subprocess.run(
            ["rclone", "obscure", password],
            capture_output=True, text=True, timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception:
        pass
    # Fallback: return as-is (rclone accepts plain text too)
    return password

# ── Status ────────────────────────────────────────────────────────────────

def get_status():
    ip = config.get("phone_ip") or get_phone_ip() or "?"
    port = config.get("port", 8080)
    drive = config.get("drive_letter", "Z") + ":"
    
    status_lines = [
        f"Phone IP: {ip}",
        f"Port: {port}",
        f"Drive: {drive}",
        f"Status: {'Mounted' if is_mounted else 'Not mounted'}",
    ]
    return "\n".join(status_lines)

# ── Auto-launch ────────────────────────────────────────────────────────────

AUTO_LAUNCH_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_NAME = "PhonDrive"

def is_auto_launch_enabled():
    """Check if auto-launch is enabled in registry."""
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTO_LAUNCH_KEY, 0, winreg.KEY_READ)
        winreg.QueryValueEx(key, APP_NAME)
        winreg.CloseKey(key)
        return True
    except FileNotFoundError:
        return False

def set_auto_launch(enable):
    """Enable or disable auto-launch."""
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTO_LAUNCH_KEY, 0, winreg.KEY_SET_VALUE)
        if enable:
            # Get path to current executable
            if getattr(sys, 'frozen', False):
                exe_path = sys.executable
            else:
                exe_path = f'"{sys.executable}" "{os.path.abspath(__file__)}"'
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, exe_path)
        else:
            try:
                winreg.DeleteValue(key, APP_NAME)
            except FileNotFoundError:
                pass
        winreg.CloseKey(key)
        return True
    except Exception as e:
        print(f"Failed to set auto-launch: {e}")
        return False

# ── Mount with retry ──────────────────────────────────────────────────────

def mount_with_retry(max_retries=3, delay=10):
    """Mount with retry logic."""
    for attempt in range(max_retries):
        ok, msg = mount_drive()
        if ok:
            return True, msg
        if attempt < max_retries - 1:
            time.sleep(delay)
    return False, f"Failed after {max_retries} attempts"

# ── Tray icon ─────────────────────────────────────────────────────────────

def create_icon(color="green"):
    """Create a simple tray icon."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Phone shape
    draw.rounded_rectangle([16, 8, 48, 56], radius=4, fill=color, outline="white", width=2)
    # Screen
    draw.rectangle([20, 14, 44, 48], fill="black")
    # Home button
    draw.ellipse([28, 50, 36, 58], fill=color, outline="white")
    
    return img

def on_mount(icon, item):
    """Mount drive in background thread."""
    def _mount():
        ok, msg = mount_drive()
        update_icon()
        if not ok:
            show_notification("PhonDrive", f"Mount failed: {msg}", is_error=True)
    threading.Thread(target=_mount, daemon=True).start()

def on_unmount(icon, item):
    """Unmount drive."""
    ok, msg = unmount_drive()
    update_icon()

def on_status(icon, item):
    """Show status."""
    show_notification("PhonDrive Status", get_status())

def on_refresh_ip(icon, item):
    """Refresh phone IP from Tailscale."""
    def _refresh():
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()
            show_notification("PhonDrive", f"Phone IP: {ip}")
        else:
            show_notification("PhonDrive", "Could not discover phone IP", is_error=True)
    threading.Thread(target=_refresh, daemon=True).start()

def on_quit(icon, item):
    """Quit the app."""
    unmount_drive()
    icon.stop()

def on_toggle_auto_launch(icon, item):
    current = is_auto_launch_enabled()
    set_auto_launch(not current)
    show_notification("PhonDrive", f"Auto-launch {'enabled' if not current else 'disabled'}")

def update_icon():
    """Update tray icon color based on mount status."""
    global tray_icon
    if tray_icon:
        color = "green" if is_mounted else "gray"
        tray_icon.icon = create_icon(color)
        tray_icon.title = f"PhonDrive - {'Mounted' if is_mounted else 'Not mounted'}"

def show_notification(title, msg, is_error=False):
    """Show Windows notification."""
    if HAS_PYSTRAY and tray_icon:
        tray_icon.notify(msg, title)

def build_menu():
    """Build the tray context menu."""
    mount_text = "Unmount" if is_mounted else "Mount"
    mount_action = on_unmount if is_mounted else on_mount
    auto_launch_text = "Disable auto-launch" if is_auto_launch_enabled() else "Enable auto-launch"
    
    return pystray.Menu(
        pystray.MenuItem(mount_text, mount_action, default=True),
        pystray.MenuItem("Status", on_status),
        pystray.MenuItem("Refresh IP", on_refresh_ip),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem(auto_launch_text, on_toggle_auto_launch),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit", on_quit),
    )

# ── Main ──────────────────────────────────────────────────────────────────

def main():
    global tray_icon
    
    if not HAS_PYSTRAY:
        print("ERROR: pystray not installed. Run: pip install pystray Pillow")
        print("Starting in console mode...")
        console_mode()
        return
    
    load_config()
    
    # Auto-discover IP if not set
    if not config.get("phone_ip"):
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()
    
    # Auto-mount if configured
    if config.get("auto_mount") and config.get("phone_ip"):
        def _auto_mount():
            time.sleep(5)
            ok, msg = mount_with_retry(max_retries=3, delay=10)
            update_icon()
            if not ok:
                show_notification("PhonDrive", f"Auto-mount failed: {msg}", is_error=True)
        threading.Thread(target=_auto_mount, daemon=True).start()
    
    # Create tray icon
    icon = create_icon("gray")
    tray_icon = pystray.Icon(
        "PhonDrive",
        icon,
        "PhonDrive - Not mounted",
        build_menu()
    )
    
    # Run (blocks)
    tray_icon.run()

def console_mode():
    """Run without tray icon (for debugging)."""
    load_config()
    
    print("PhonDrive Console Mode")
    print("Commands: mount, unmount, status, ip, quit")
    print()
    
    while True:
        cmd = input("> ").strip().lower()
        
        if cmd == "mount":
            ok, msg = mount_drive()
            print(f"{'OK' if ok else 'FAIL'}: {msg}")
        elif cmd == "unmount":
            ok, msg = unmount_drive()
            print(f"{'OK' if ok else 'FAIL'}: {msg}")
        elif cmd == "status":
            print(get_status())
        elif cmd == "ip":
            ip = get_phone_ip()
            print(f"Phone IP: {ip or 'not found'}")
        elif cmd in ("quit", "exit", "q"):
            unmount_drive()
            break
        else:
            print(f"Unknown command: {cmd}")

if __name__ == "__main__":
    main()
