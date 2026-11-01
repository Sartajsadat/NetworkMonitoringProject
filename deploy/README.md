# deploy/

| Path | Contents |
|---|---|
| `linux/install-agent.sh` | Installs and enrolls the Linux agent as a systemd service |
| `linux/uninstall-agent.sh` | Removes the Linux agent |
| `linux/agent.properties.example` | Agent configuration reference |
| `systemd/netmon-agent.service` | Hardened systemd unit (unprivileged user, read-only system view) |
| `windows/install-agent.ps1` | Installs and enrolls the Windows agent as a service (WinSW, LOCAL SERVICE) |
| `windows/uninstall-agent.ps1` | Removes the Windows agent |
| `windows/agent.properties.example` | Windows agent configuration reference |

The central platform itself is deployed with `docker-compose.yml` in the repository root.
See `docs/AGENT-INSTALL.md` (Linux) and `docs/AGENT-INSTALL-WINDOWS.md` (Windows).
