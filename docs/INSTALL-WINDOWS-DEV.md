# Windows development environment

Run the backend and frontend on a Windows 10/11 workstation for development. Detailed commands are
in README.md ("Development without Docker"); this page lists the steps and the Windows specifics.

## 1. Install (as an administrator)
| Tool | Notes |
|---|---|
| **JDK 21** | Eclipse Temurin 21 MSI; tick *Set JAVA_HOME* |
| **Node.js 22 LTS** | nodejs.org installer |
| **Docker Desktop** | Needed for the backend tests (Testcontainers) and the simplest database; WSL 2 backend |
| **PostgreSQL 16** | Either `docker compose up -d db` (uncomment its `ports` line), or the EDB installer |
| **Wireshark** (TShark) | Only for packet analysis; set `TSHARK_PATH=C:\Program Files\Wireshark\tshark.exe` |
| **Git** | Git for Windows; enable "checkout as-is, commit Unix-style" so scripts keep LF line endings |

Administrator rights are needed to install these tools and Docker Desktop. Running the backend and
frontend does not need them.

## 2. Run
**Easiest:** the helper script asks for the database password once, saves it in
`backend\.env.local` (ignored by Git) and starts the backend; later runs need no typing:
```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```
(`-Reset` asks for the settings again.) It also stops early with a clear message if port 8080 is
already in use.

**By hand**, PowerShell, in the repository folder:
```powershell
cd backend
$env:DB_URL="jdbc:postgresql://localhost:5432/netmon"; $env:DB_USERNAME="netmon"; $env:DB_PASSWORD="choose-a-password"
$env:JWT_SECRET="<base64 of 48 random bytes>"; $env:REFRESH_COOKIE_SECURE="false"
$env:CAPTURE_STORAGE_DIR="C:\NetMon\captures"
.\mvnw.cmd spring-boot:run
```
In a second window:
```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```
Open http://localhost:5173. The setup token is printed in the backend window.

## 3. Windows agent (optional, for testing Windows monitoring)
Build the agent with `cd agent; .\mvnw.cmd package`. Then follow
[AGENT-INSTALL-WINDOWS.md](AGENT-INSTALL-WINDOWS.md); it needs an elevated PowerShell. For packet
capture, install Npcap without the "restrict to Administrators" option. For firewall control use
`-EnableFirewall`: the service then runs as LocalSystem.

## 4. Tests
```powershell
cd backend; .\mvnw.cmd test        # Docker Desktop must be running
```
```powershell
cd agent; .\mvnw.cmd test
```
```powershell
cd frontend; npm.cmd test
```

## 5. Common problems
| Problem | Fix |
|---|---|
| `npm.ps1 cannot be loaded` | Use `npm.cmd`, or `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| Port 8080 in use | `$env:SERVER_PORT="8081"` and start the frontend with `$env:VITE_BACKEND_URL="http://localhost:8081"` |
| Backend tests fail with "Could not find a valid Docker environment" | Start Docker Desktop |
| Sign-in works but you are signed out at once | `REFRESH_COOKIE_SECURE=false` is required on plain `http://localhost` |
