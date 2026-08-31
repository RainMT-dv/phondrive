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
import logging
from datetime import datetime
from pathlib import Path

try:
    import pystray
    from PIL import Image, ImageDraw
    HAS_PYSTRAY = True
except ImportError:
    HAS_PYSTRAY = False
    print("WARNING: pystray/Pillow not installed. Run: pip install pystray Pillow")

# ── Logging ────────────────────────────────────────────────────────────────

LOG_DIR = Path.home() / ".phondrive"
LOG_FILE = LOG_DIR / "phondrive.log"

LOG_DIR.mkdir(exist_ok=True)
logging.basicConfig(
    filename=str(LOG_FILE),
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("phondrive")

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
is_server_reachable = False
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

# ── Server reachability ──────────────────────────────────────────────────

def ping_server(ip=None, port=None, timeout=3):
    _ip = ip or config.get("phone_ip")
    _port = port or config.get("port", 8080)
    if not _ip:
        return False
    try:
        import urllib.request
        req = urllib.request.Request(
            f"http://{_ip}:{_port}/",
            method="HEAD",
        )
        auth = f"{config.get('user', 'user')}:{config.get('pass', 'pass')}"
        import base64
        req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
        urllib.request.urlopen(req, timeout=timeout)
        return True
    except urllib.error.HTTPError:
        return True
    except Exception:
        return False

# ── Tailscale IP discovery ───────────────────────────────────────────────

def get_phone_ip():
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
    global mount_process, is_mounted, is_server_reachable

    if is_mounted:
        return True, "Already mounted"

    ip = config["phone_ip"]
    if not ip:
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()

    if not ip:
        return False, "Phone IP not configured. Use 'Set IP' to enter it."

    log.info(f"Pinging server at {ip}:{config['port']}...")
    is_server_reachable = ping_server(ip)
    if not is_server_reachable:
        log.warning(f"Server at {ip}:{config['port']} unreachable")
        return False, f"Phone server unreachable at {ip}:{config['port']}\nCheck if PhonDrive is running on the phone."

    log.info(f"Server reachable at {ip}:{config['port']}")

    rclone = config["rclone_path"]
    try:
        subprocess.run([rclone, "version"], capture_output=True, timeout=5,
                      creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
    except FileNotFoundError:
        return False, "rclone not found. Install: winget install Rclone.Rclone"

    try:
        result = subprocess.run(
            [rclone, "listremotes"],
            capture_output=True, text=True, timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )
        if "phondrive:" not in result.stdout:
            url = build_rclone_url()
            config_content = f"""[phondrive]
type = webdav
url = {url}
vendor = other
user = {config['user']}
pass = {rclone_obfuscate(config['pass'])}
"""
            rclone_config_dir = Path.home() / "AppData" / "Roaming" / "rclone"
            rclone_config_dir.mkdir(parents=True, exist_ok=True)
            rclone_config_file = rclone_config_dir / "rclone.conf"

            existing = ""
            if rclone_config_file.exists():
                existing = rclone_config_file.read_text()

            if "[phondrive]" not in existing:
                with open(rclone_config_file, "a") as f:
                    f.write(config_content)
                log.info("Created rclone remote config")
    except Exception as e:
        return False, f"Failed to check/create rclone remote: {e}"

    drive = config["drive_letter"] + ":"
    cmd = [
        rclone, "mount", "phondrive:/", drive,
        "--volname", "PhonDrive",
        "--vfs-cache-mode", config["vfs_cache_mode"],
        "--network-mode",
        "--dir-cache-time", config["dir_cache_time"],
    ]

    try:
        log.info(f"Mounting {drive} via rclone...")
        mount_process = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )

        time.sleep(5)

        check = subprocess.run(
            ["dir", drive + "\\"],
            capture_output=True, text=True, timeout=5,
            shell=True,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        )

        if check.returncode == 0:
            is_mounted = True
            log.info(f"Mounted at {drive}\\ (PID: {mount_process.pid})")
            return True, f"Mounted at {drive}\\"
        else:
            log.warning(f"Mount check failed: {check.stderr}")
            return False, f"Mount may have failed: {check.stderr}"

    except Exception as e:
        log.error(f"Mount failed: {e}")
        return False, f"Mount failed: {e}"

def unmount_drive():
    global mount_process, is_mounted

    drive = config["drive_letter"] + ":"

    try:
        subprocess.run(
            ["taskkill", "/F", "/IM", "rclone.exe"],
            capture_output=True, timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW
        )
        mount_process = None
        is_mounted = False
        log.info(f"Unmounted {drive}")
        return True, f"Unmounted {drive}"
    except Exception as e:
        log.error(f"Unmount failed: {e}")
        return False, f"Unmount failed: {e}"

def rclone_obfuscate(password):
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
    return password

# ── Status ────────────────────────────────────────────────────────────────

def get_status():
    ip = config.get("phone_ip") or "?"
    port = config.get("port", 8080)
    drive = config.get("drive_letter", "Z") + ":"

    server_status = "reachable" if is_server_reachable else "unreachable"
    rclone_pid = mount_process.pid if mount_process and mount_process.poll() is None else "N/A"

    status_lines = [
        f"Phone IP: {ip}:{port}",
        f"Server: {server_status}",
        f"Drive: {drive}",
        f"Status: {'Mounted' if is_mounted else 'Not mounted'}",
        f"rclone PID: {rclone_pid}",
    ]
    return "\n".join(status_lines)

# ── Auto-launch ────────────────────────────────────────────────────────────

AUTO_LAUNCH_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_NAME = "PhonDrive"

def is_auto_launch_enabled():
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTO_LAUNCH_KEY, 0, winreg.KEY_READ)
        winreg.QueryValueEx(key, APP_NAME)
        winreg.CloseKey(key)
        return True
    except FileNotFoundError:
        return False

def set_auto_launch(enable):
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTO_LAUNCH_KEY, 0, winreg.KEY_SET_VALUE)
        if enable:
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
        log.info(f"Auto-launch {'enabled' if enable else 'disabled'}")
        return True
    except Exception as e:
        log.error(f"Failed to set auto-launch: {e}")
        return False

# ── Mount with retry ──────────────────────────────────────────────────────

def mount_with_retry(max_retries=3, delay=10):
    for attempt in range(max_retries):
        ok, msg = mount_drive()
        if ok:
            return True, msg
        if attempt < max_retries - 1:
            wait = delay * (2 ** attempt)
            log.info(f"Retry {attempt + 1}/{max_retries} in {wait}s...")
            time.sleep(wait)
    return False, f"Failed after {max_retries} attempts"

# ── Tray icon ─────────────────────────────────────────────────────────────

def create_icon(color="green"):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([16, 8, 48, 56], radius=4, fill=color, outline="white", width=2)
    draw.rectangle([20, 14, 44, 48], fill="black")
    draw.ellipse([28, 50, 36, 58], fill=color, outline="white")
    return img

def on_mount(icon, item):
    def _mount():
        ok, msg = mount_with_retry(max_retries=3, delay=5)
        update_icon()
        if ok:
            show_notification("PhonDrive", f"Mounted: {msg}")
        else:
            show_notification("PhonDrive", f"Mount failed: {msg}", is_error=True)
    threading.Thread(target=_mount, daemon=True).start()

def on_unmount(icon, item):
    ok, msg = unmount_drive()
    update_icon()
    show_notification("PhonDrive", msg)

def on_status(icon, item):
    show_notification("PhonDrive Status", get_status())

def on_test_connection(icon, item):
    def _test():
        ip = config.get("phone_ip")
        if not ip:
            show_notification("PhonDrive", "No IP configured", is_error=True)
            return
        ok = ping_server(ip)
        global is_server_reachable
        is_server_reachable = ok
        if ok:
            show_notification("PhonDrive", f"Server reachable at {ip}:{config['port']}")
        else:
            show_notification("PhonDrive", f"Server unreachable at {ip}:{config['port']}", is_error=True)
        update_icon()
    threading.Thread(target=_test, daemon=True).start()

def on_refresh_ip(icon, item):
    def _refresh():
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()
            show_notification("PhonDrive", f"Phone IP: {ip}")
            log.info(f"IP refreshed: {ip}")
        else:
            show_notification("PhonDrive", "Could not discover phone IP", is_error=True)
    threading.Thread(target=_refresh, daemon=True).start()

def on_quit(icon, item):
    log.info("Quitting PhonDrive tray app")
    unmount_drive()
    icon.stop()

# ── Mount health monitor ───────────────────────────────────────────────────

_health_stop = threading.Event()

def _health_check():
    consecutive_failures = 0
    while not _health_stop.is_set():
        interval = min(30 + consecutive_failures * 10, 120)
        _health_stop.wait(interval)
        if _health_stop.is_set():
            break
        if not config.get("phone_ip"):
            continue
        if not config.get("auto_mount", True):
            continue
        if not is_mounted:
            continue

        try:
            os.listdir(f"{config['drive_letter']}:\\")
            consecutive_failures = 0
        except Exception:
            consecutive_failures += 1
            log.warning(f"Mount check failed (attempt {consecutive_failures})")
            show_notification("PhonDrive", "Mount lost, remounting...")
            time.sleep(2)
            ok, msg = mount_drive()
            update_icon()
            if ok:
                show_notification("PhonDrive", "Remounted successfully")
                consecutive_failures = 0
            else:
                show_notification("PhonDrive", f"Remount failed: {msg}", is_error=True)

def on_set_ip(icon, item):
    import tkinter as tk
    from tkinter import simpledialog

    root = tk.Tk()
    root.withdraw()
    root.attributes('-topmost', True)

    current = config.get("phone_ip", "")
    ip = simpledialog.askstring(
        "PhonDrive - IP do Celular",
        "Digite o IP do Tailscale do celular:",
        initialvalue=current,
        parent=root,
    )
    root.destroy()

    if ip and ip.strip():
        config["phone_ip"] = ip.strip()
        save_config()
        log.info(f"IP set manually: {ip.strip()}")
        show_notification("PhonDrive", f"IP definido: {ip.strip()}")
    elif ip is not None:
        show_notification("PhonDrive", "IP nao pode ser vazio", is_error=True)

def on_toggle_auto_launch(icon, item):
    current = is_auto_launch_enabled()
    set_auto_launch(not current)
    show_notification("PhonDrive", f"Auto-launch {'enabled' if not current else 'disabled'}")

def update_icon():
    global tray_icon
    if tray_icon:
        if is_mounted:
            color = "green"
            status = "Mounted"
        elif is_server_reachable:
            color = "yellow"
            status = "Server OK, not mounted"
        else:
            color = "gray"
            status = "Disconnected"

        ip = config.get("phone_ip", "?")
        tray_icon.icon = create_icon(color)
        tray_icon.title = f"PhonDrive - {status}\n{ip}:{config.get('port', 8080)}"

def show_notification(title, msg, is_error=False):
    log.info(f"Notification: {title} - {msg}")
    if HAS_PYSTRAY and tray_icon:
        tray_icon.notify(msg, title)

def build_menu():
    mount_text = "Unmount" if is_mounted else "Mount"
    mount_action = on_unmount if is_mounted else on_mount
    auto_launch_text = "Disable auto-launch" if is_auto_launch_enabled() else "Enable auto-launch"

    return pystray.Menu(
        pystray.MenuItem(mount_text, mount_action, default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Test Connection", on_test_connection),
        pystray.MenuItem("Status", on_status),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Set IP", on_set_ip),
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
    log.info("PhonDrive tray app started")

    if not config.get("phone_ip"):
        ip = get_phone_ip()
        if ip:
            config["phone_ip"] = ip
            save_config()
            log.info(f"Auto-discovered IP: {ip}")

    if config.get("auto_mount") and config.get("phone_ip"):
        def _auto_mount():
            time.sleep(5)
            ok, msg = mount_with_retry(max_retries=3, delay=10)
            update_icon()
            if not ok:
                show_notification("PhonDrive", f"Auto-mount failed: {msg}", is_error=True)
        threading.Thread(target=_auto_mount, daemon=True).start()

    icon = create_icon("gray")
    tray_icon = pystray.Icon(
        "PhonDrive",
        icon,
        "PhonDrive - Starting...",
        build_menu()
    )

    threading.Thread(target=_health_check, daemon=True).start()

    tray_icon.run()

def console_mode():
    load_config()
    print("PhonDrive Console Mode")
    print("Commands: mount, unmount, status, ip, ping, quit")
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
        elif cmd == "ping":
            ok = ping_server()
            print(f"Server: {'reachable' if ok else 'unreachable'}")
        elif cmd in ("quit", "exit", "q"):
            unmount_drive()
            break
        else:
            print(f"Unknown command: {cmd}")

if __name__ == "__main__":
    main()
