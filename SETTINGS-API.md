# Log Scraper settings API

Base: `http://localhost:8090/api/setup`. If `SETUP_TOKEN` is set, send `X-Setup-Token: <token>` on every call (401 without it).
Bodies are JSON. Errors are `{"error": "..."}` with 400 (bad input), 401, 404 (server not saved), 409 (key exists) or 500.
Passwords can be written but are never returned, logged or stored in plain text.

| Call | What it does |
|---|---|
| `GET /servers` | List saved servers (no passwords) |
| `GET /servers/{name}` | One server |
| `PUT /servers/{name}` | Add or change a server. Missing fields keep their saved value; `""` clears `password`, `keyFile`, `hostKeyFingerprint` |
| `DELETE /servers/{name}` | Remove a server |
| `POST /servers/{name}/test` | Step 1: network, server identity, login, grep. Optional body tries other values without saving |
| `POST /servers/{name}/key` | Make an SSH key for this server (`?force=true` replaces it) |
| `POST /servers/{name}/discover` | Step 2: find log folders from an ID that really happened |
| `POST /servers/{name}/check` | Which files does a path or pattern match? |
| `POST /servers/{name}/preview` | Step 3: first lines of a log, format, masked |
| `GET /masking` | Masking rules in force and the list of filters |
| `PUT /masking` | Replace the rules: `{"disabled":["EMAIL"],"fields":["polis"],"custom":[{"name":"x","regex":"POL-\\d{8}","keepHead":0,"keepTail":0}]}`. Making masking weaker needs `SETUP_TOKEN` to be set (403 otherwise); every change is audited |
| `POST /masking/test` | Mask a sample `{"text":"...","config":{...}}` without saving |

There is also a web page for all of this: `http://localhost:8090/settings.html` (it asks for the token when `SETUP_TOKEN` is set).

Server name: letters, digits, `.`, `_`, `-` (max 40). Log paths: absolute, no spaces, quotes, `$ ; | & \` ` or `..`; only `*` and `?` expand.

## Save a server

```http
PUT /api/setup/servers/app-02
{ "role": "worker", "host": "10.1.2.3", "port": 22, "username": "logreader",
  "password": "...", "logPaths": ["/opt/worker/logs/worker*.log"] }
```
```json
{ "name": "app-02", "role": "worker", "host": "10.1.2.3", "port": 22, "username": "logreader",
  "hasPassword": true, "keyFile": null, "hostKeyFingerprint": null,
  "logPaths": ["/opt/worker/logs/worker*.log"] }
```

Fields: `role`, `host`, `port` (default 22), `username`, `password`, `keyFile` (a file in the key folder, set by `/key`),
`hostKeyFingerprint` (`SHA256:...`, pins the server's identity), `logPaths` (up to 20).

## Step 1: test the connection

`POST /servers/app-02/test`
```json
{ "ok": true,
  "steps": [
    { "step": "Jaringan", "ok": true, "detail": "Port 22 di 10.1.2.3 terbuka" },
    { "step": "Identitas server", "ok": true, "detail": "SHA256:Yv... (belum disimpan: isi hostKeyFingerprint untuk mengunci)" },
    { "step": "Login", "ok": true, "detail": "Berhasil sebagai logreader" },
    { "step": "Perintah baca", "ok": true, "detail": "grep tersedia: grep (GNU grep) 3.11" } ],
  "fingerprint": "SHA256:Yv...", "hint": null }
```
When a step fails the rest are skipped and `hint` says what to do. A pinned fingerprint that no longer matches stops at "Identitas server" (`KUNCI_BERUBAH` in searches).

Key instead of password: `POST /servers/app-02/key`
```json
{ "keyFile": "app-02.key", "publicKey": "ecdsa-sha2-nistp256 AAAA...",
  "authorizedKeysLine": "restrict ecdsa-sha2-nistp256 AAAA... log-scraper-app-02",
  "installCommand": "mkdir -p ~/.ssh && ... >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys" }
```
The private key stays in the key folder; the server admin runs `installCommand` as the read-only account.

## Step 2: find the logs

`POST /servers/app-02/discover` with `{ "id": "REQ-88231", "roots": ["/opt"] }` (`roots` optional, default `/var/log /opt /app /srv /u01 /home`)
```json
{ "status": "OK", "note": null,
  "patterns": [ { "pattern": "/opt/worker/logs/worker*.log", "files": 1,
                  "examples": ["/opt/worker/logs/worker.json.log"], "compressedRotated": 1 } ],
  "filesFound": 2, "hiddenSystemLogs": 1, "unreadableFolders": 0, "searchedRoots": ["/opt"] }
```
Rotated files are folded into one pattern; system logs (auth.log, syslog, ...) are hidden. `compressedRotated` counts `.gz` files, which are not searched yet.
Save a pattern with `PUT` (`logPaths`), then confirm it: `POST /servers/app-02/check` with `{ "path": "/opt/worker/logs/worker*.log" }`
```json
{ "status": "OK", "count": 1, "files": ["/opt/worker/logs/worker.json.log"], "note": null }
```

## Step 3: check the format

`POST /servers/app-02/preview` with `{ "path": "/opt/worker/logs/worker.json.log", "lines": 20 }` (max 50)
```json
{ "status": "OK", "lines": 20, "recognized": 20, "unrecognized": 0, "percent": 100, "ok": true,
  "format": "JSON satu baris per log", "formats": { "JSON": 20 },
  "rows": [ { "time": "2026-11-06 09:14:02.118", "level": "INFO", "thread": "", "logger": "pay",
              "message": "request received ref=REQ-55120 account=12****90", "format": "JSON" } ],
  "unrecognizedSamples": [], "masked": { "REKENING": 1 }, "advice": null }
```
`ok` is true when at least 80% of lines are understood. Otherwise `advice` explains, and `unrecognizedSamples` shows (masked) lines that fit no format.
Formats: Spring Boot / Logback, ISO with `T`, JSON lines, Log4j with square brackets, Tomcat / WebLogic.

## Search status values

`OK`, `KOSONG`, `TIDAK_TERJANGKAU`, `LOGIN_GAGAL`, `TIMEOUT`, `DITOLAK`, `ERROR`, plus new: `KUNCI_BERUBAH` (pinned key changed),
`BELUM_DIATUR` (no log folders saved), `FORMAT_TIDAK_DIKENALI` (lines mention the ID but fit no known format).

## Copy-paste

```bash
H='-H Content-Type:application/json -H X-Setup-Token:YOURTOKEN'
curl -X PUT  $H localhost:8090/api/setup/servers/app-02 -d '{"role":"worker","host":"10.1.2.3","username":"logreader","password":"..."}'
curl -X POST $H localhost:8090/api/setup/servers/app-02/test
curl -X POST $H localhost:8090/api/setup/servers/app-02/discover -d '{"id":"REQ-88231"}'
curl -X PUT  $H localhost:8090/api/setup/servers/app-02 -d '{"logPaths":["/opt/worker/logs/worker*.log"]}'
curl -X POST $H localhost:8090/api/setup/servers/app-02/preview -d '{"path":"/opt/worker/logs/worker.json.log"}'
```
