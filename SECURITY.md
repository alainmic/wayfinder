# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Use GitHub's private vulnerability reporting instead ("Security" tab → "Report a vulnerability") on this repository. Include steps to reproduce and the affected version.

We aim to acknowledge reports within 7 days.

## Scope

Wayfinder has broad file-system access ("All files access") and connects to network servers (SMB, WebDAV, FTP). Issues of particular interest include:

- Path traversal: archive entries, remote listings, renames, drag & drop imports.
- Exposure of files through the FileProvider or the picker results.
- Credential handling for saved servers.
- TLS validation for WebDAVS / FTPS.
- Intent handling by exported activities.
