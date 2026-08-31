# PhonDrive

Monte o armazenamento do seu celular Android como um drive nativo no Windows — navegue, edite, copie e mova arquivos pelo Explorer via [Tailscale](https://tailscale.com).

```
┌─────────────────┐     Tailscale       ┌─────────────────┐
│  Celular Android│ ◄──── túnel ─────►  │   PC Windows    │
│  Servidor WebDAV│ ──── HTTP:8080 ───► │  rclone mount   │
│  (Kotlin/Ktor)  │                     │  (letra Z:)     │
└─────────────────┘                     └─────────────────┘
```

## Funcionalidades

- **Integração nativa com o Explorer** — o armazenamento do celular aparece como uma letra de drive comum (Z:)
- **Funciona em qualquer lugar** — o túnel do Tailscale permite que celular e PC estejam em redes diferentes
- **Montagem com um clique** — app de system tray monta/desmonta com um único clique
- **Operações completas de arquivo** — criar, ler, renomear, mover, copiar e apagar arquivos direto pelo Explorer
- **Auto-start** — o app de tray pode iniciar junto com o Windows e montar automaticamente

## Requisitos

| Componente | Versão | Notas |
|------------|--------|-------|
| Android | 7.0+ (API 24) | |
| Tailscale | Mais recente | Mesma conta no celular e no PC |
| Windows | 10+ | |
| rclone | 1.65+ | Cliente WebDAV |
| WinFsp | 2.0+ | Necessário para o rclone montar drives |

## Início Rápido

### 1. Instalar no Celular

Baixe o `PhonDrive.apk` nas [Releases](https://github.com/RainMT-dv/phondrive/releases) e instale. Conceda "Acesso a Todos os Arquivos" quando solicitado.

Abra o app, digite o IP do Tailscale do celular e toque em **Ligar servidor**.

### 2. Instalar no Windows

Baixe o `PhonDrive-Tray.exe` nas [Releases](https://github.com/RainMT-dv/phondrive/releases). Execute — o ícone aparece na Notification Area (system tray).

> [!NOTE]
> Se o ícone não aparecer, clique na setinha `^` ao lado do relógio.

### 3. Montar

Clique com o botão direito no ícone da tray → **Mount**. O armazenamento do celular aparece como `Z:\` no Explorer.

### 4. Navegar

Abra o Explorer → **Este Computador** → **PhonDrive (Z:)** — pronto.

## Compilar a Partir do Código

### Android

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requer Android SDK com `platforms;android-34` e `build-tools;34.0.0`.

### Tray Windows (Python)

```bash
cd windows
pip install -r requirements.txt
python tray_app.py
```

### Tray Windows (.exe)

```bash
pip install pyinstaller
pyinstaller --onefile --noconsole --name PhonDrive-Tray tray_app.py
```

### Tray Windows (Rust)

```bash
cd windows/phondrive-tray
cargo build --release
# Saída: target/release/phondrive-tray.exe
```

## Configuração

### Configuração do rclone

Criado automaticamente pelo app de tray, ou configure manualmente em `%APPDATA%\rclone\rclone.conf`:

```ini
[phondrive]
type = webdav
url = http://<IP_CELULAR>:8080
vendor = other
user = user
pass = pass
```

### Credenciais Padrão

- **Usuário:** `user`
- **Senha:** `pass`

> [!WARNING]
> Altere essas credenciais antes de usar além da sua rede Tailscale local.

### Configuração do App de Tray

| Versão | Local |
|--------|-------|
| Python | `~/.phondrive/config.json` |
| Rust | `%APPDATA%/phondrive/config.toml` |

## Testes

```powershell
# Suite completa de testes E2E
.\scripts\verify-e2e.ps1

# Ou teste manualmente com curl
curl http://<IP_CELULAR>:8080/ping -u user:pass
```

## Estrutura do Projeto

```
phondrive/
├── android/                    # Servidor WebDAV Android
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/phondrive/webdavspike/
│   │       ├── MainActivity.kt      # UI + permissões
│   │       ├── WebDavServer.kt      # Servidor WebDAV (Ktor)
│   │       └── WebDavService.kt     # Serviço em foreground
│   └── build.gradle.kts
├── windows/                    # Apps de tray Windows
│   ├── tray_app.py             # Python (pystray)
│   ├── phondrive-tray/         # Rust (tray-icon)
│   └── requirements.txt
├── scripts/                    # Scripts de automação
│   ├── verify-e2e.ps1          # Verificação E2E completa
│   ├── validate-webdav.ps1     # Testes da API WebDAV
│   ├── setup-windows.ps1       # Setup inicial do Windows
│   └── build-windows.ps1       # Build com PyInstaller
└── dist/                       # Binários pré-compilados
    ├── android/PhonDrive.apk
    └── windows/PhonDrive-Tray.exe
```

## Limitações Conhecidas

- **rclone necessário** — o `net use` nativo do Windows não consegue rotear WebDAV pelo adaptador virtual do Tailscale. Usamos rclone + WinFsp no lugar.
- **Modo Doze** — o Android pode matar o serviço em foreground depois de 8+ horas com a tela desligada. Ispense o app da otimização de bateria para melhor confiabilidade.
- **Sem bloqueio de arquivo** — editar o mesmo arquivo no celular e no PC simultaneamente pode causar corrupção.
- **Somente celular para PC** — direcional. O PC lê do celular; sem envio de PC para celular.
- **Tailscale obrigatório** — Basic auth via HTTP só é seguro dentro do túnel criptografado do Tailscale.

## Segurança

- As credenciais são transmitidas como Base64 (facilmente decodificável) — **somente seguro via Tailscale**
- Nunca exponha o servidor WebDAV à internet aberta
- O APK solicita permissões de armazenamento amplas necessárias para o funcionamento do servidor

## Licença

[MIT](LICENSE)
