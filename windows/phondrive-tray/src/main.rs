use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;
use tray_icon::menu::{Menu, MenuEvent, MenuItem};
use tray_icon::{Icon, TrayIconBuilder};

const APP_NAME: &str = "PhonDrive";
const AUTO_LAUNCH_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

#[derive(serde::Deserialize, serde::Serialize, Clone)]
struct Config {
    phone_ip: String,
    port: u16,
    user: String,
    pass: String,
    drive_letter: String,
    rclone_path: String,
    tailscale_hostname: String,
    auto_mount: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            phone_ip: String::new(),
            port: 8080,
            user: "user".into(),
            pass: "pass".into(),
            drive_letter: "Z".into(),
            rclone_path: "rclone".into(),
            tailscale_hostname: "cll-a".into(),
            auto_mount: true,
        }
    }
}

fn config_path() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("phondrive")
        .join("config.toml")
}

fn load_config() -> Config {
    let path = config_path();
    if path.exists() {
        let data = fs::read_to_string(&path).unwrap_or_default();
        toml::from_str(&data).unwrap_or_default()
    } else {
        let cfg = Config::default();
        save_config(&cfg);
        cfg
    }
}

fn save_config(cfg: &Config) {
    let path = config_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::write(path, toml::to_string_pretty(cfg).unwrap_or_default());
}

fn get_phone_ip(hostname: &str) -> Option<String> {
    let out = Command::new("tailscale")
        .arg("status")
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    for line in stdout.lines() {
        if line.to_lowercase().contains(&hostname.to_lowercase()) {
            for part in line.split_whitespace() {
                if part.starts_with("100.") && part[4..].contains('.') {
                    return Some(part.to_string());
                }
            }
        }
    }
    None
}

fn is_mounted(drive: &str) -> bool {
    Command::new("cmd")
        .args(["/C", "dir", &format!("{drive}:\\")])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn mount_drive(cfg: &Config) -> Result<String, String> {
    let ip = if cfg.phone_ip.is_empty() {
        get_phone_ip(&cfg.tailscale_hostname)
            .ok_or("Phone IP not found via Tailscale")?
    } else {
        cfg.phone_ip.clone()
    };

    let url = format!("http://{}:{}", ip, cfg.port);
    let drive = format!("{}:", cfg.drive_letter);

    let rclone_cfg_dir = dirs::config_dir()
        .unwrap_or_default()
        .join("rclone");
    let _ = fs::create_dir_all(&rclone_cfg_dir);
    let rclone_cfg_file = rclone_cfg_dir.join("rclone.conf");

    let existing = fs::read_to_string(&rclone_cfg_file).unwrap_or_default();
    if !existing.contains("[phondrive]") {
        let remote_cfg = format!(
            "[phondrive]\ntype = webdav\nurl = {url}\nvendor = other\nuser = {}\npass = {}\n",
            cfg.user, cfg.pass
        );
        fs::write(&rclone_cfg_file, format!("{existing}\n{remote_cfg}"))
            .map_err(|e| format!("Failed to write rclone config: {e}"))?;
    }

    let status = Command::new(&cfg.rclone_path)
        .args([
            "mount",
            "phondrive:/",
            &drive,
            "--volname",
            "PhonDrive",
            "--vfs-cache-mode",
            "writes",
            "--network-mode",
            "--dir-cache-time",
            "5s",
        ])
        .spawn();

    match status {
        Ok(_) => Ok(format!("Mounting at {drive}\\")),
        Err(e) => Err(format!("Failed to start rclone: {e}")),
    }
}

fn unmount_drive() -> Result<String, String> {
    let _ = Command::new("taskkill")
        .args(["/F", "/IM", "rclone.exe"])
        .output();
    Ok("Unmounted".into())
}

fn is_auto_launch_enabled() -> bool {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    let hkey = RegKey::predef(HKEY_CURRENT_USER);
    hkey.open_subkey(AUTO_LAUNCH_KEY)
        .and_then(|k| k.get_value::<String, _>(APP_NAME))
        .is_ok()
}

fn set_auto_launch(enable: bool) {
    use winreg::enums::*;
    use winreg::RegKey;
    let hkey = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok((key, _)) = hkey.create_subkey(AUTO_LAUNCH_KEY) {
        if enable {
            let exe = std::env::current_exe().unwrap_or_default();
            let _ = key.set_value(APP_NAME, &exe.to_string_lossy().to_string());
        } else {
            let _ = key.delete_value(APP_NAME);
        }
    }
}

fn create_icon_data(r: u8, g: u8, b: u8) -> Icon {
    let mut rgba = vec![0u8; 32 * 32 * 4];
    for y in 0..32 {
        for x in 0..32 {
            let i = (y * 32 + x) * 4;
            let in_phone = x >= 8 && x <= 24 && y >= 4 && y <= 28;
            let in_screen = x >= 10 && x <= 22 && y >= 6 && y <= 24;
            if in_phone && !in_screen {
                rgba[i] = r;
                rgba[i + 1] = g;
                rgba[i + 2] = b;
                rgba[i + 3] = 255;
            } else if in_screen {
                rgba[i] = 30;
                rgba[i + 1] = 30;
                rgba[i + 2] = 30;
                rgba[i + 3] = 255;
            }
        }
    }
    Icon::from_rgba(rgba, 32, 32).unwrap()
}

fn main() {
    let mut cfg = load_config();

    if cfg.phone_ip.is_empty() {
        if let Some(ip) = get_phone_ip(&cfg.tailscale_hostname) {
            cfg.phone_ip = ip;
            save_config(&cfg);
        }
    }

    let menu = Menu::new();
    let mount_item = MenuItem::new("Mount", true, None);
    let unmount_item = MenuItem::new("Unmount", true, None);
    let status_item = MenuItem::new("Status", true, None);
    let refresh_item = MenuItem::new("Refresh IP", true, None);
    let auto_launch_item = MenuItem::new(
        if is_auto_launch_enabled() {
            "Disable auto-launch"
        } else {
            "Enable auto-launch"
        },
        true,
        None,
    );
    let quit_item = MenuItem::new("Quit", true, None);

    menu.append(&mount_item).unwrap();
    menu.append(&unmount_item).unwrap();
    menu.append(&status_item).unwrap();
    menu.append(&refresh_item).unwrap();
    menu.append(&auto_launch_item).unwrap();
    menu.append(&quit_item).unwrap();

    let mounted = is_mounted(&cfg.drive_letter);
    let color = if mounted { (0, 200, 0) } else { (128, 128, 128) };

    let _tray_icon = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip(APP_NAME)
        .with_icon(create_icon_data(color.0, color.1, color.2))
        .build()
        .unwrap();

    let menu_channel = MenuEvent::receiver();

    loop {
        if let Ok(event) = menu_channel.try_recv() {
            match event.id {
                id if id == mount_item.id() => {
                    match mount_drive(&cfg) {
                        Ok(msg) => println!("Mount: {msg}"),
                        Err(e) => eprintln!("Mount failed: {e}"),
                    }
                }
                id if id == unmount_item.id() => {
                    match unmount_drive() {
                        Ok(msg) => println!("Unmount: {msg}"),
                        Err(e) => eprintln!("Unmount failed: {e}"),
                    }
                }
                id if id == status_item.id() => {
                    let mounted = is_mounted(&cfg.drive_letter);
                    let status = if mounted { "Mounted" } else { "Not mounted" };
                    println!("Status: {status} at {}:{}", cfg.drive_letter, cfg.port);
                }
                id if id == refresh_item.id() => {
                    if let Some(ip) = get_phone_ip(&cfg.tailscale_hostname) {
                        cfg.phone_ip = ip.clone();
                        save_config(&cfg);
                        println!("Phone IP: {ip}");
                    } else {
                        eprintln!("Could not discover phone IP");
                    }
                }
                id if id == auto_launch_item.id() => {
                    let current = is_auto_launch_enabled();
                    set_auto_launch(!current);
                    println!(
                        "Auto-launch {}",
                        if current { "disabled" } else { "enabled" }
                    );
                }
                id if id == quit_item.id() => {
                    let _ = unmount_drive();
                    break;
                }
                _ => {}
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
