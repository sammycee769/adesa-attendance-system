# External Render health checks

Render free web services spin down after 15 minutes without inbound traffic.
This script requests the public database health endpoint every 10 minutes.
It requires Bash and curl and does not use credentials or change application data.
It must run outside Render: a process inside a sleeping service cannot wake it.

## GitHub-hosted schedule

`.github/workflows/render-health.yml` runs the script once every five minutes
on a GitHub-hosted runner. Your computer can be off. The workflow also runs when
its configuration/script changes on main, and supports manual runs. It uses
only the public health URL; no Render, database, SMTP or OAuth secrets are needed.
Checks do not sign in, record attendance, change the office allowlist, deploy the
backend or modify production records.

After pushing the files to main, open the repository's Actions tab and select
**Render health check**. Verify a successful run. To start another check, choose
**Run workflow** on main. To stop recurring checks, use the workflow's menu and
choose **Disable workflow**. Do not also run the local loop/cron unless needed.

GitHub scheduled jobs can be delayed or dropped during high load. In a public
repository they are disabled after 60 days without repository activity; re-enable
the workflow in Actions when needed. An interval shorter than Render's idle
timeout is useful but does not make the service reliably always-on.
See https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule.

## Optional local or external Linux host

On a Linux machine that stays powered on and connected:

```sh
cd ~/adesa-attendance
bash scripts/keep-render-awake.sh
```

To continue after closing the terminal:

```sh
nohup bash "$HOME/adesa-attendance/scripts/keep-render-awake.sh" > "$HOME/adesa-render-health.log" 2>&1 &
echo $! > "$HOME/adesa-render-health.pid"
```

Stop that background process:

```sh
kill "$(cat "$HOME/adesa-render-health.pid")"
```

Alternatively, add this to `crontab -e` on an external always-online Linux host.
Use cron instead of the continuous loop, so there is only one scheduler:

```cron
*/10 * * * * /bin/bash /home/semi/adesa-attendance/scripts/keep-render-awake.sh --once >> /home/semi/adesa-render-health.log 2>&1
```

Adjust paths if using a different host/account. Cron handles host reboots when
its daemon is enabled; the nohup command alone does not. Laptop sleep, shutdown,
lost connectivity, a failed scheduler, or provider restarts can still interrupt
checks. An external HTTP monitoring service can instead request the same health
URL every 5–10 minutes without needing this script or a running laptop.

This does not guarantee always-on hosting. Render can restart free services and
grants 750 free instance hours per workspace each calendar month, shared across
its free services. Keeping one service awake continuously uses 720 hours in a
30-day month or 744 in a 31-day month, leaving little capacity for other free
services. Exhausting that allowance suspends the workspace's free services for
the rest of the month. Monitor Render billing/usage. Paid compute removes idle
spin-down; this script does not change your free plan.

Source: https://render.com/docs/free
