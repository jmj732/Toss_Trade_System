#!/usr/bin/env python3
"""Read-only inventory of Investment OS tabs before the first Sheet mirror.

Run on the backend host with:
  cd /opt/toss && doppler run --project trade --config stg -- python3 trading-backend/scripts/investment-sheet-readonly-preflight.py

This script never writes Sheet cells or prints credential, token, or cell-body data.
Row counts include the header row and exclude trailing empty rows omitted by Sheets.
"""

import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request


TABS = (
    "Security Snapshot",
    "Thesis State",
    "Consensus History",
    "Watchlist",
    "Decision Ledger",
    "Alpha State",
    "Risk Policy",
)
TOKEN_URL = "https://oauth2.googleapis.com/token"
SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets"
SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unknown(reason):
    print("UNKNOWN: " + reason)
    return 2


def access_token(credentials):
    email = credentials.get("client_email")
    private_key = credentials.get("private_key")
    if not isinstance(email, str) or not email or not isinstance(private_key, str) or not private_key:
        raise ValueError("credentials unavailable")

    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64url(json.dumps({
        "iss": email,
        "scope": SHEETS_SCOPE,
        "aud": TOKEN_URL,
        "iat": now,
        "exp": now + 3600,
    }, separators=(",", ":")).encode())
    unsigned = (header + "." + claims).encode("ascii")

    fd, key_path = tempfile.mkstemp(prefix="investment-sheet-key-")
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as key_file:
            key_file.write(private_key.encode("utf-8"))
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=unsigned,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
            timeout=10,
        ).stdout
    finally:
        try:
            os.unlink(key_path)
        except FileNotFoundError:
            pass

    assertion = unsigned.decode("ascii") + "." + b64url(signature)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode("ascii")
    request = urllib.request.Request(
        TOKEN_URL,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        token_response = json.loads(response.read())
    token = token_response.get("access_token")
    if not isinstance(token, str) or not token:
        raise ValueError("authentication unavailable")
    return token


def get_json(url, token):
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def main():
    enabled = os.environ.get("INVESTMENT_OS_SHEET_ENABLED", "").strip().lower()
    if enabled not in ("true", "1", "yes"):
        return unknown("Sheets disabled or enablement unknown; no Sheet data fetched")

    service_account = os.environ.get("GOOGLE_SHEETS_SERVICE_ACCOUNT_JSON")
    spreadsheet_id = os.environ.get("GOOGLE_SHEETS_SPREADSHEET_ID")
    if not service_account or not spreadsheet_id:
        return unknown("credentials unavailable; no Sheet data fetched")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", spreadsheet_id):
        return unknown("spreadsheet configuration unavailable; no Sheet data fetched")

    try:
        credentials = json.loads(service_account)
        token = access_token(credentials)
        base = SHEETS_API + "/" + urllib.parse.quote(spreadsheet_id, safe="")
        metadata = get_json(base + "?fields=sheets.properties.title", token)
        existing = {
            sheet.get("properties", {}).get("title")
            for sheet in metadata.get("sheets", [])
        }
        results = []
        for title in TABS:
            if title not in existing:
                results.append({"tab": title, "rowCount": "UNKNOWN", "headers": "UNKNOWN"})
                continue
            range_name = "'" + title.replace("'", "''") + "'!A:ZZ"
            url = base + "/values/" + urllib.parse.quote(range_name, safe="")
            values = get_json(url, token).get("values", [])
            headers = values[0] if values else []
            results.append({"tab": title, "rowCount": len(values), "headers": headers})
        for result in results:
            print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 0
    except Exception:
        return unknown("authentication or read unavailable; no cell data printed")


if __name__ == "__main__":
    sys.exit(main())
