#!/usr/bin/env python3
"""Authenticated, read-only network diagnostics. Password and cookies stay in memory."""
import getpass
import http.client
from http.cookies import SimpleCookie
import json
import ssl
import sys

HOST = "adesa-attendance-system.onrender.com"
DIAGNOSTIC = "/api/v1/admin/network/client-address"
FIELDS = (
    "resolvedClientAddress", "withinApprovedNetwork", "enforcementEnabled",
    "approvedNetworkCount", "trustedProxyCount", "clientIpHeader",
    "resolvedAddressIsIpv4", "untrustedForwardedClientAddress",
)


class ProbeError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class Client:
    def __init__(self):
        self.cookies = {}

    def request(self, method, path, body=None, extra_headers=()):
        # Fixed host, verified TLS, no redirects or persisted session files.
        connection = http.client.HTTPSConnection(HOST, timeout=45,
                                                context=ssl.create_default_context())
        try:
            encoded = None if body is None else json.dumps(body).encode()
            connection.putrequest(method, path)
            connection.putheader("Accept", "application/json")
            if self.cookies:
                connection.putheader("Cookie", "; ".join(
                    name + "=" + value for name, value in self.cookies.items()))
            if encoded is not None:
                connection.putheader("Content-Type", "application/json")
                connection.putheader("Content-Length", str(len(encoded)))
            # Preserve repeated header lines for the duplicate-header test.
            for name, value in extra_headers:
                connection.putheader(name, value)
            connection.endheaders(encoded)
            response = connection.getresponse()
            for name, value in response.getheaders():
                if name.lower() == "set-cookie":
                    cookie = SimpleCookie()
                    cookie.load(value)
                    for key, item in cookie.items():
                        if item["max-age"] == "0":
                            self.cookies.pop(key, None)
                        else:
                            self.cookies[key] = item.value
            data = response.read(65537)
            if response.status >= 300:
                raise ProbeError("HTTP " + str(response.status)
                                 + "; response body suppressed. A 403 may be an edge rejection, not a login failure.",
                                 response.status)
            if len(data) > 65536:
                raise ProbeError("Unexpectedly large response; suppressed")
            return json.loads(data) if data else {}
        finally:
            connection.close()

    def csrf(self):
        token = self.request("GET", "/api/v1/auth/csrf").get("token")
        if not isinstance(token, str) or not token:
            raise ProbeError("CSRF initialization failed")
        return [("X-XSRF-TOKEN", token)]


def cases():
    # Include both the original allowlist and the currently observed office IP.
    return [
        ("baseline", []),
        ("forged_original_office_xff", [("X-Forwarded-For", "105.127.6.174")]),
        ("forged_observed_office_xff", [("X-Forwarded-For", "105.127.8.52")]),
        ("forged_entire_chain", [("X-Forwarded-For", "105.127.8.52, 172.71.190.248, 10.27.74.163")]),
        ("repeated_xff", [("X-Forwarded-For", "105.127.8.52"),
                          ("X-Forwarded-For", "198.51.100.23")]),
        ("malformed_xff", [("X-Forwarded-For", "105.127.8.52,not-an-ip,")]),
        ("forged_gateway_proof", [("X-Adesa-Ingress-Key", "b" * 64),
                                    ("X-Adesa-Client-IP", "105.127.8.52")]),
        ("forged_vercel_client_ip", [("x-vercel-forwarded-for", "105.127.8.52")]),
        ("forged_alternative_headers", [("True-Client-IP", "105.127.8.52"),
                                         ("CF-Connecting-IP", "105.127.8.52"),
                                         ("X-Real-IP", "105.127.8.52"),
                                         ("Forwarded", 'for=105.127.8.52;proto=https')]),
    ]


def main():
    if not sys.stdin.isatty():
        raise ProbeError("Run in a private interactive terminal")
    print("This signs in once, reads admin diagnostics, and signs out. No attendance is recorded.")
    label = input("Current connection (office Wi-Fi / personal / hotspot): ").strip()
    email = input("Admin email: ").strip()
    password = getpass.getpass("Current admin password (hidden): ")
    client = Client()
    signed_in = False
    try:
        identity = client.request("POST", "/api/v1/auth/login",
                                  {"email": email, "password": password}, client.csrf())
        signed_in = True
        password = None
        if identity.get("mustChangePassword"):
            raise ProbeError("Complete the required password change in the website first")
        print(json.dumps({"connection": label, "host": HOST}))
        for name, headers in cases():
            try:
                result = client.request("GET", DIAGNOSTIC, extra_headers=headers)
            except ProbeError as error:
                if name == "baseline" or error.status is None:
                    raise
                print(json.dumps({"case": name, "httpStatus": error.status,
                                  "note": "Request rejected; not a successful backend diagnostic"}))
                continue
            if not all(field in result for field in FIELDS):
                raise ProbeError("Unexpected diagnostic response; suppressed")
            print(json.dumps({"case": name, "diagnostic": {field: result[field] for field in FIELDS}}))
        print("These observations do not prove header authenticity or justify changing proxy trust.")
    finally:
        password = None
        if signed_in:
            try:
                client.request("POST", "/api/v1/auth/logout", extra_headers=client.csrf())
                print("Probe session signed out.")
            except Exception:
                print("Probe logout could not be confirmed; session may remain until expiry.", file=sys.stderr)
        client.cookies.clear()


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["--gateway"]:
            HOST = "adesa-attendance-frontend.vercel.app"
        elif sys.argv[1:]:
            raise ProbeError("Usage: python3 scripts/check_office_network.py [--gateway]")
        main()
    except ProbeError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (Exception, KeyboardInterrupt):
        print("Probe stopped; raw errors suppressed to protect credentials. Check connectivity and try again.",
              file=sys.stderr)
        sys.exit(1)
