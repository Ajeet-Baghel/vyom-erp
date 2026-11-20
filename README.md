# Attendance Extraction — Sheet Editor

Full-stack demo of the Google Sheets ↔ web page pattern: **pull rows from a
source of truth and write changes back**, through a Java REST API and a React UI.

## Architecture

```
React (Vite, :5173) ──/api/*──> Java HttpServer (:8080) ──Sheets API v4──> Google Sheet
                                     │
                                     └─> falls back to in-memory demo data
                                         when credentials aren't configured
```

- `org.ajeet.sheets.SheetsClient` — row-store abstraction (read/append/update/delete).
- `org.ajeet.sheets.GoogleSheetsClient` — real impl; service account, A1 ranges,
  `USER_ENTERED` writes, `INSERT_ROWS` append, `batchUpdate` for row deletes.
- `org.ajeet.sheets.MemorySheetsClient` — seeded demo data so the app runs before
  Google Cloud setup.
- `org.ajeet.web.ApiServer` — REST routes + CORS (virtual threads).

### API

| Method | Path              | Body                    | Purpose                |
|--------|-------------------|-------------------------|------------------------|
| GET    | `/api/status`     | —                       | `{ "mode": ... }`      |
| GET    | `/api/rows`       | —                       | `{ "values": [[..]] }` |
| POST   | `/api/rows`       | `{ "values": [..] }`    | append row             |
| PUT    | `/api/rows/{n}`   | `{ "values": [..] }`    | overwrite row `n` (≥2) |
| DELETE | `/api/rows/{n}`   | —                       | delete row `n` (≥2)    |

Row numbers are sheet rows (1-based); row 1 is the header and is protected.

## Z200BW device sync (SBXPC SDK)

Pulls punches from the Team Office Z200BW terminal over LAN and appends them
as rows (`Employee ID | Punch Time | Verify Mode | In/Out`).

- `org.ajeet.device.Sbxpc` — JNA mapping of `SBXPCDLL64.dll` (stdcall).
- `org.ajeet.device.DeviceClient` — connect → `ReadGeneralLogData` → drain
  `GetGeneralLogData` → disconnect; dedups via a timestamp watermark stored in
  `device-sync-state.txt` (device log is never wiped).
- `POST /api/sync` — triggers a pull; optional body `{"since": "2026-11-01T00:00"}`
  resets the watermark (backfill). First run without a state file syncs today's
  punches only.

The 64-bit DLLs must be reachable via `jna.library.path` (set automatically
from `DEVICE_DLL_DIR`, which defaults to the bundled `vendor-sdk/` sample
folder). Requires a **64-bit JVM**.

Config comes from the process environment **or** a `.env` file in the project
root (env vars take precedence; `.env` is gitignored — see `.env.example`):

```dotenv
DEVICE_PASSWORD=<comm key set on the device>   # required to enable sync
DEVICE_IP=192.168.31.120                       # optional, this is the default
DEVICE_PORT=5005                               # optional
```

```powershell
& "...mvn.cmd" -q exec:java
curl -X POST http://localhost:8080/api/sync
```

If `DEVICE_PASSWORD` is unset the server still runs; `/api/sync` returns 503.
Machine number defaults to `1`. Appends are per-row — sync small windows to
stay within Sheets write quota.

### Storage: PostgreSQL

When `SPREADSHEET_ID` is unset but `PG_URL` is set, storage switches to
PostgreSQL (`PostgresSheetsClient`). It auto-creates two tables:

- `punches` (`id`, `enroll_number`, `punch_time`, `verify_label`) — UNIQUE on
  `(enroll_number, punch_time, verify_label)`, so re-syncing never duplicates
  a punch (also covers the same-timestamp watermark edge case).
- `users` (`enroll_number`, `name`) — refreshed on every sync from the device's
  enrolled-user list; `/api/rows` joins it so the UI shows real names.

```dotenv
PG_URL=jdbc:postgresql://localhost:5432/attendance
PG_USER=postgres
PG_PASSWORD=<postgres password>
```

Priority: `SPREADSHEET_ID` → `PG_URL` → in-memory demo.

## Run it (demo mode, no credentials)

```powershell
# Backend
& "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" -q exec:java

# Frontend (separate terminal)
cd frontend; npm.cmd run dev   # http://localhost:5173
```

## Connect a real Google Sheet

Auth is resolved by `SheetsAuth` in this order: service-account key → OAuth
desktop client (`credentials.json`) → Application Default Credentials.
Pick the option your org's policy allows.

### Option A — OAuth desktop app (no service-account key needed)

Use this when `iam.disableServiceAccountKeyCreation` blocks JSON keys.

1. Cloud Console → **APIs & Services → Library** → enable *Google Sheets API*.
2. **OAuth consent screen** → Internal (Workspace org) or External → set app
   name + emails → on *Scopes* add `.../auth/spreadsheets` → on *Test users*
   add your Google account → leave status **Testing**.
3. **Credentials → Create Credentials → OAuth client ID → Desktop app** →
   download the JSON → save as `credentials.json` in the project root
   (git-ignored). Desktop-app type needs no redirect URI config.
4. Sign-in account must have **edit access to the spreadsheet**.
5. Run:

```powershell
$env:SPREADSHEET_ID = "1AbC..."   # from docs.google.com/spreadsheets/d/<ID>/edit
& "...mvn.cmd" -q exec:java
```

A browser opens on first run ("unverified app" → **Advanced → continue**);
the token is cached in `tokens/` so restarts won't re-ask. Testing-mode
refresh tokens can expire after ~7 days — just re-run.

### Option B — Service account (if keys are allowed)

1. Create a service account + JSON key (save as `service-account.json`,
   git-ignored) and **share the spreadsheet** with its `client_email` (Editor).
2. Run:

```powershell
$env:SERVICE_ACCOUNT_KEY_PATH = "D:\demos\attendance_extraction\service-account.json"
$env:SPREADSHEET_ID = "1AbC..."
& "...mvn.cmd" -q exec:java
```

### Option C — Application Default Credentials

If `gcloud` is installed and allowed: `gcloud auth application-default login`,
set `SPREADSHEET_ID`, and run — no key or client-secret file needed.

The header row of the **first tab** defines the table columns. Put the expected
columns (e.g. `Employee ID, Name, Date, Status, Hours`) in row 1.

## Notes & limits

- `USER_ENTERED` input option: writes parse like typing (numbers, dates,
  formulas). Switch to `RAW` for literal storage.
- Sheets API quota: ~300 read + 300 write req/min/project — batch where possible.
- No transactions; concurrent appends can interleave. Fine for append-heavy
  operational data, not for strict consistency needs.
