# Log Scraper

Satu ID, semua server, satu timeline. Log Scraper searches one request ID across every server of an app
over read-only SSH, merges the hits into one timeline, says where the request broke, and masks personal data
before anything is shown or copied into a ticket. Nothing is installed on the servers: it runs the `grep` already there.

## What's in here

| Folder | What it is |
|---|---|
| `log-scraper/` | The tool: Spring Boot 3.5 (Java 21) + Apache MINA SSHD client, one web page in Bahasa |
| `sample-app/` | A small sample system on four "servers" to search: api-gateway, process-service, data-service, notification-service. Each runs an SSH server whose `logreader` account may only run `grep`/`zgrep`/`sort`/`uniq`/`head` |
| `docker-compose.yml` | Starts the four servers and Log Scraper |

## Run it

```bash
(cd sample-app && mvn -B package)
(cd log-scraper && mvn -B package)        # also runs the unit tests
docker compose up -d --build
open http://localhost:8090                # wait ~10 s for the first requests
```

Try `REQ-88231` (fails, but the data service still saves the record twice), `REQ-88232` (succeeds), `REQ-88233` (rejected: quota exceeded),
a typo like `req-8823l`, or `docker compose stop app-04` to see a server that is down.

## How it works

1. **Search:** `GET /api/search?q=REQ-88231&hours=24`. The term is tidied (spaces, pasted brackets, case), checked against
   an allow-list of characters, single-quoted, and sent as `grep -h -i -F -A 40 -- '<term>' <log paths>` to every server
   in parallel (virtual threads, 5 s timeout per server).
2. **Merge:** lines are parsed by timestamp; stack-trace lines stay with the entry above them; context lines from other
   requests are dropped; everything is sorted into one timeline.
3. **Mask:** NIK (only when the province code and birth date are valid), card numbers (only when they pass Luhn), phone
   numbers, emails, tokens, passwords, PINs and OTPs, NPWP, IP addresses (last octet), and, by field name, account numbers, personal names, addresses, birth dates, mother's maiden name and CVV. Reference numbers that are neither stay readable.
4. **Diagnose:** rules, not AI. The first ERROR (or failure-like WARN) gives "Putus di app-02, 4,0 detik setelah request
   masuk". If a step was already committed (`COMMITTED`) but the caller was told it failed (`status=FAILED`), it raises an
   alert, and it flags a step that ran twice. Both markers are configurable.
5. **Fail gracefully:** each server reports OK / KOSONG / TIDAK_TERJANGKAU / LOGIN_GAGAL / TIMEOUT / DITOLAK; a down
   server never blocks the others. No hits → near-miss IDs are suggested ("REQ-8823l" → "REQ-88231").
6. **Audit:** every search is appended to `audit.log` (who, what, how many hits, how long).

## Configure for real servers

Edit `log-scraper/src/main/resources/application.yml` (or override with environment variables): one entry per server
with `name`, `role`, `host`, `port`, `username`, `password` and `log-paths`. Use a read-only OS account on each server.

Demo shortcuts to change before real use: servers without a pinned `hostKeyFingerprint` accept any host key, and a password written in `application.yml` is plain text (use the settings API, which encrypts it, or a key).

## Server settings (no config editing)

Servers and their log folders can be set through the settings API instead of `application.yml`. Changes apply to the next
search without a restart and are saved in `servers.json`. Set `SETUP_TOKEN` to require an `X-Setup-Token` header on every call
(leave it empty only on a machine nobody else can reach).

```bash
H='-H Content-Type:application/json -H X-Setup-Token:...'
# 1. access: add a server, then test it (network, server identity, login, grep available)
curl -X PUT  $H localhost:8090/api/setup/servers/app-02 -d '{"role":"worker","host":"10.1.2.3","username":"logreader","password":"..."}'
curl -X POST $H localhost:8090/api/setup/servers/app-02/test
# better than a password: Log Scraper makes a key; the answer holds the one line the server admin adds to authorized_keys
curl -X POST $H localhost:8090/api/setup/servers/app-02/key
# pin the server's identity with the SHA256 shown by the test; a changed key is then refused (KUNCI_BERUBAH)
curl -X PUT  $H localhost:8090/api/setup/servers/app-02 -d '{"hostKeyFingerprint":"SHA256:..."}'
# 2. logs: paste an ID that really happened and get the folders that contain it (rotated files folded into one pattern)
curl -X POST $H localhost:8090/api/setup/servers/app-02/discover -d '{"id":"REQ-88231"}'
curl -X PUT  $H localhost:8090/api/setup/servers/app-02 -d '{"logPaths":["/opt/worker/logs/worker*.log"]}'
curl -X POST $H localhost:8090/api/setup/servers/app-02/check -d '{"path":"/opt/worker/logs/worker*.log"}'
# 3. format: first lines of a log, how many the parser understands, masked
curl -X POST $H localhost:8090/api/setup/servers/app-02/preview -d '{"path":"/opt/worker/logs/worker.json.log"}'
```

Formats understood: Spring Boot / Logback, ISO with `T` (also Spring Boot 3), JSON lines, Log4j with square brackets, Tomcat / WebLogic.
A line that mentions the searched ID but fits none of them is reported as `FORMAT_TIDAK_DIKENALI` with a count and masked samples,
so a format problem never looks like "ID not found". Compressed (`.gz`) rotated logs are not searched yet.

**Passwords are write-only.** No call returns one, none is logged or written to `audit.log`, and in `servers.json` they are
AES-256-GCM encrypted (key from `SETTINGS_KEY`, base64 of 32 bytes, or generated once into `keys/.settings-key`, owner-only).
Keys made by the `/key` call stay in `keys/` and only the public half is returned. Every path is checked against an allow-list
before it reaches a remote shell (absolute, no spaces, quotes, `$`, `;`, `|`, `` ` `` or `..`; only `*` and `?` expand), and every
setting change is appended to `audit.log`.

## Choose what gets masked

Masking can be changed in **Pengaturan server → Penyamaran data** (admin token; switching filters off needs `SETUP_TOKEN` set, and every change is audited), or in `application.yml` under `scraper.masking`. The search page cannot change it, so nobody can switch it off while looking at a ticket. Changes made in the settings are saved to `masking.json` and win over `application.yml`.

```yaml
scraper:
  masking:
    disabled: [IP, TGL_LAHIR]        # turn off built-in filters (NIK KARTU TELEPON RAHASIA EMAIL NPWP REKENING NAMA ALAMAT TGL_LAHIR KARTU_CVV IP KUSTOM)
    fields: [kodeAgen, noPolis]      # extra field names: kodeAgen=... is masked wherever it appears
    custom:                          # extra regex rules, with characters to leave readable
      - { name: nomor-polis, regex: "POL-\\d{8}", keep-head: 4, keep-tail: 2 }
```

`GET /api/masking` shows which filters are active. A wrong regex stops the app at startup instead of failing on the first search.
