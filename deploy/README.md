# deploy/

| Path | Contents |
|---|---|
| `linux/install-agent.sh` | Installs and enrolls the Linux agent as a systemd service |
| `linux/uninstall-agent.sh` | Removes the Linux agent |
| `linux/agent.properties.example` | Agent configuration reference |
| `systemd/netmon-agent.service` | Hardened systemd unit (unprivileged user, read-only system view) |
| `windows/` | Windows agent service scripts (phase 3) |

The central platform itself is deployed with `docker-compose.yml` in the repository root.
See `docs/AGENT-INSTALL.md` for step-by-step instructions.
