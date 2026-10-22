#!/usr/bin/env python3
"""Run locally in a private terminal; never logs tokens or sends email."""
import getpass
import json
import os
from pathlib import Path
import sys
import urllib.parse
import urllib.request

DOMAINS = {
    "US": "zoho.com", "EU": "zoho.eu", "IN": "zoho.in", "AU": "zoho.com.au",
    "JP": "zoho.jp", "CA": "zohocloud.ca", "CN": "zoho.com.cn",
    "AE": "zoho.ae", "SA": "zoho.sa",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    if not sys.stdin.isatty():
        raise ValueError("Use an interactive private terminal")
    destination = Path(__file__).resolve().parents[1] / ".env.zoho-render"
    if destination.exists():
        raise ValueError("Private output already exists; move it securely before starting again")
    region = input("Zoho mailbox region (US/EU/IN/AU/JP/CA/CN/AE/SA): ").strip().upper()
    domain = DOMAINS[region]
    sender = input("Authorized sender mailbox (email only): ").strip()
    client_id = getpass.getpass("OAuth client ID (hidden): ").strip()
    client_secret = getpass.getpass("OAuth client secret (hidden): ").strip()
    code = getpass.getpass("Fresh Self Client authorization code (hidden): ").strip()
    values = (sender, client_id, client_secret, code)
    if any(not value or any(c in value for c in "\r\n\x00") for value in values):
        raise ValueError("Required input is empty or contains a line break")
    opener = urllib.request.build_opener(NoRedirect())
    form = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": client_id,
        "client_secret": client_secret, "code": code,
    }).encode()
    request = urllib.request.Request(
        "https://accounts." + domain + "/oauth/v2/token", data=form,
        headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    with opener.open(request, timeout=20) as response:
        tokens = json.load(response)
    access = tokens.get("access_token", "")
    refresh = tokens.get("refresh_token", "")
    if not access or not refresh or tokens.get("error"):
        raise ValueError("OAuth authorization failed; verify region and issue a fresh code")
    request = urllib.request.Request("https://mail." + domain + "/api/accounts",
                                     headers={"Authorization": "Zoho-oauthtoken " + access})
    with opener.open(request, timeout=20) as response:
        accounts = json.load(response)
    if accounts.get("status", {}).get("code") != 200:
        raise ValueError("Account discovery failed; check accounts.READ permission")
    matches = []
    for account in accounts.get("data", []):
        addresses = {account.get("primaryEmailAddress", "").lower(),
                     account.get("mailboxAddress", "").lower()}
        addresses.update(item.get("mailId", "").lower()
                         for item in account.get("emailAddress", []) if item.get("isConfirmed"))
        if sender.lower() in addresses:
            matches.append(str(account.get("accountId", "")))
    if len(matches) != 1 or not matches[0].isascii() or not matches[0].isdigit():
        raise ValueError("Sender did not match exactly one authorized Zoho mailbox")
    settings = {"MAIL_PROVIDER": "zoho-api", "MAIL_FROM": sender,
                "ZOHO_DATA_CENTER": region, "ZOHO_MAIL_ACCOUNT_ID": matches[0],
                "ZOHO_CLIENT_ID": client_id, "ZOHO_CLIENT_SECRET": client_secret,
                "ZOHO_REFRESH_TOKEN": refresh}
    if any(any(c in value for c in "\r\n\x00") for value in settings.values()):
        raise ValueError("Unexpected multiline provider value")
    # Exclusive creation and owner-only permissions from the first write.
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as output:
        output.write("".join(key + "=" + value + "\n" for key, value in settings.items()))
    print("Private settings saved to .env.zoho-render (owner-only access).")
    print("Import privately into Render Environment; never paste or upload this file to chat.")


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt):
        # Provider errors can echo credentials; never emit exception bodies/tracebacks.
        print("Setup did not complete. Check region, permissions, mailbox and code expiry privately.", file=sys.stderr)
        sys.exit(1)
