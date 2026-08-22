# Deploying StasisBot (headless, on any server)

## One-time, on the server
Install Docker + the compose plugin (Debian/Ubuntu):

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git openssl
sudo usermod -aG docker "$USER"   # then reconnect your SSH session
```

## Deploy a bot
```bash
git clone https://github.com/NyuDev/StasisBot.git && cd StasisBot
./deploy.sh
```

`deploy.sh` asks two things and does the rest:

1. **Your Minecraft name** (the master who controls the bot) and the target server.
2. It builds, then prints a **Microsoft login URL**. Open it in *any* browser, sign in
   with the **bot's** account. The page ends on `http://127.0.0.1:3000/?code=...` and
   fails to load - that's expected. **Copy that whole URL** from the address bar and
   paste it back into the script. No X11, no SSH tunnel needed.

When it finishes it prints the **control endpoint** and the **secret** to use in the
in-game controller.

## Add another bot on the same host
Clone into a **different folder** and run the script there - it auto-picks the next
free ports:

```bash
git clone https://github.com/NyuDev/StasisBot.git StasisBot2 && cd StasisBot2
./deploy.sh
```

Each bot needs its own Minecraft account and gets its own secret.

## Day-to-day
```bash
docker compose logs -f            # live logs
docker compose stop               # take it offline (stays off, even on reboot)
git pull && docker compose up -d --build   # update to the latest version
```

## Reaching the controller from outside
- **Public-IP server** (OVH, Hetzner, ...): `sudo ufw allow <controlPort>/tcp`, then the
  controller connects to `http://<server-ip>:<controlPort>`.
- **Behind a home router/NAT**: forward that TCP port on the router to the server's LAN IP.
- Optional: put nginx in front for a clean `https://...` endpoint (and, with path routing,
  several bots behind one port). The control channel is already encrypted (AES-256-GCM with
  the secret), so exposing it is safe; TLS just adds transport-level cover.

## Never log into a bot's Minecraft account while its bot is running
It invalidates the bot's session (they can't both be online at once). If you must use the
account, hit **Disconnect** in the controller first - that now persists across restarts.
