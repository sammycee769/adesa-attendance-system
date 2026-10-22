# Zoho Mail HTTPS delivery on Render free

The application supports `MAIL_PROVIDER=zoho-api` while retaining `smtp` for
hosts that permit it. Zoho API calls use HTTPS port 443 and the existing mailbox.
No mailbox password or SMTP app password is needed for this provider.
API access and sending limits must be permitted by your Zoho account and policy.

## Private one-time OAuth provisioning

1. Sign in as the intended sending mailbox in Zoho Mail. Check the final browser
   hostname to identify its region. Do not assume a region based on your location.

   | Region value | Mail hostname |
   | --- | --- |
   | US | mail.zoho.com |
   | EU | mail.zoho.eu |
   | IN | mail.zoho.in |
   | AU | mail.zoho.com.au |
   | JP | mail.zoho.jp |
   | CA | mail.zohocloud.ca |
   | CN | mail.zoho.com.cn |
   | AE | mail.zoho.ae |
   | SA | mail.zoho.sa |

2. Open [Zoho API Console](https://api-console.zoho.com/) using that account
   and its matching data centre. Add a **Self Client** named ADESA Attendance.
   Keep its client ID and client secret private. No public callback is needed.
3. Open a private local terminal in this repository and prepare:

   ```sh
   python3 scripts/provision_zoho_oauth.py
   ```

   Enter the region, sender mailbox, client ID and secret at the prompts.
   Secret inputs are hidden and do not become shell-history arguments.
4. In the Self Client's **Generate Code** tab enter these scopes:

   ```text
   ZohoMail.messages.CREATE,ZohoMail.accounts.READ
   ```

   Description: ADESA account setup and recovery email. Generate the code and
   promptly enter it at the terminal's final hidden prompt (default expiry is
   three minutes). This grants sending and mailbox-account metadata access;
   it does not grant message-reading permission.
5. The helper exchanges the code, discovers the matching account ID and writes
   `.env.zoho-render` with owner-only permissions. It never sends email, prints
   credentials or changes Render. A failure produces a generic message; check
   region, code expiry and scope permissions, then generate a fresh code.
6. Open the existing Render service's **Environment** page. Privately import the
   file's seven settings using **Add from .env**, or enter them directly:
   `MAIL_PROVIDER`, `MAIL_FROM`, `ZOHO_DATA_CENTER`, `ZOHO_MAIL_ACCOUNT_ID`,
   `ZOHO_CLIENT_ID`, `ZOHO_CLIENT_SECRET`, `ZOHO_REFRESH_TOKEN`.
   Save for the next deployment. Do not import any of them into Vercel.
7. Keep the refresh token and client secret in an approved password manager,
   then delete the local private file. It is ignored by Git and Docker builds.
   Never paste the file, authorization code or tokens into chat or screenshots.

`MAIL_HOST`, SMTP username/password and SMTP TLS flags are ignored in API mode.
Production startup rejects incomplete API configuration. Access tokens refresh
in memory before expiry; refresh tokens remain private server configuration.
Requests have timeouts, use fixed regional HTTPS hosts, and do not follow
redirects. Delivery errors redact provider responses, OAuth values and setup links.
An HTTP 200 with an unsuccessful Zoho application status is a delivery failure.

## Verification and revocation

After backend health succeeds, test account setup/recovery with an explicitly
approved account and verify actual mailbox delivery and link origin. Local mock
tests prove request construction and error handling; they do not prove live
OAuth permission, quota, inbox delivery or Render connectivity.
Do not create the first production admin merely to test transport before the
other security gates are ready. Never expose setup links in deployment logs.

If a credential is exposed, revoke the OAuth grant/client in Zoho, authorize a
fresh grant, update Render privately and redeploy. Removing provisioning inputs
means deleting the temporary authorization code and local file; the application
still needs its refresh token and client secret to send future emails.

References: [Self Client authorization](https://www.zoho.com/developer/oauth/self-client/authorization-code-flow.html),
[regional endpoints](https://www.zoho.com/mail/help/api/getting-started-with-api.html),
[account lookup](https://www.zoho.com/mail/help/api/get-all-users-accounts.html),
[send API](https://www.zoho.com/mail/help/api/post-send-an-email.html),
[Render free limits](https://render.com/docs/free).
