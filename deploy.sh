#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# deploy.sh - set up and launch a StasisBot instance in one command.
#
# Run it from inside a fresh clone of the repo:
#     git clone https://github.com/NyuDev/StasisBot.git && cd StasisBot
#     ./deploy.sh
#
# It handles: config + a random control secret, unique per-instance ports,
# the one-time Microsoft login (no X11, no SSH tunnel needed), and starting
# the bot detached. Run it again in another clone (a different folder) to add
# a second bot on the same host - it auto-picks the next free ports.
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1;36m%s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null   || die "docker manquant. Installe docker + le plugin compose d'abord."
docker compose version >/dev/null 2>&1 || die "le plugin 'docker compose' manque."
command -v openssl >/dev/null  || die "openssl manquant (apt install openssl)."

# --- pick the first free control port (6969+) and login callback (3000+) ---
port_used() { ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${1}$"; }
CTL=6969;  while port_used "$CTL"; do CTL=$((CTL+1)); done
CB=3000;   while port_used "$CB";  do CB=$((CB+1));  done

# --- instance name from the folder (stasisbot, stasisbot2, mybot, ...) -----
NAME="$(basename "$(pwd)" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
[ -n "$NAME" ] || NAME=stasisbot

say "StasisBot deploy  ->  instance '$NAME' | control port $CTL | login callback $CB"

if [ -f .env ] || [ -f run/config/stasisbot.json ]; then
	read -rp "Une config existe deja ici. La reecraser ? [y/N] " ans
	[ "${ans:-N}" = "y" ] || die "annule (rien touche)."
fi

read -rp "Ton pseudo Minecraft (le master qui pilote le bot): " MASTER
[ -n "$MASTER" ] || die "master requis."
read -rp "Serveur a rejoindre [2b2t.org]: " SRV; SRV="${SRV:-2b2t.org}"

# --- config + secret -------------------------------------------------------
mkdir -p run/config run/devauth
SECRET="$(openssl rand -hex 24)"
cat > run/config/stasisbot.json <<JSON
{
  "master": "$MASTER",
  "triggerWords": ["!home", "pearl", "warp"],
  "maxChamberDistance": 96,
  "controllerMode": false,
  "controlSecret": "$SECRET",
  "controlPort": 6969,
  "discordEnabled": false,
  "stayDisconnected": false
}
JSON
printf '[accounts.main]\ntype = "microsoft"\n' > run/devauth/config.toml
printf 'STASIS_SERVER=%s\nSTASIS_CONTROL_PORT=%s\n' "$SRV" "$CTL" > .env

# --- compose: unique service / container / image / callback ----------------
sed -i "s/^  stasisbot:/  ${NAME}:/"                     docker-compose.yml
sed -i "s/container_name: stasisbot/container_name: ${NAME}/" docker-compose.yml
sed -i "s/image: stasisbot-headless/image: ${NAME}-headless/" docker-compose.yml
sed -i "s|127.0.0.1:3000:3000|127.0.0.1:${CB}:3000|"     docker-compose.yml

# --- one-time Microsoft login (copy-the-code, no X11 / no tunnel) ----------
if [ -f run/devauth/microsoft_accounts.json ]; then
	say "Compte deja authentifie - on saute le login."
else
	say "Login Microsoft (une seule fois pour ce compte)"
	docker rm -f "${NAME}-login" >/dev/null 2>&1 || true
	docker compose run --service-ports -d --name "${NAME}-login" "$NAME" >/dev/null
	printf 'Build + demarrage (1-2 min)'
	URL=""
	for _ in $(seq 1 90); do
		URL="$(docker logs "${NAME}-login" 2>&1 | grep -aoE 'https://login\.live\.com/oauth20_authorize[^ ]*' | tail -1 || true)"
		[ -n "$URL" ] && break
		printf '.'; sleep 5
	done
	printf '\n'
	[ -n "$URL" ] || die "Pas d'URL de login trouvee. Regarde: docker logs ${NAME}-login"

	cat <<STEP

1) Ouvre cette URL dans un navigateur (ton PC, ton telephone, peu importe) et
   connecte-toi avec le compte du BOT:

   $URL

2) A la fin la page echoue sur  http://127.0.0.1:3000/?code=...  (c'est NORMAL).
   Copie l'URL COMPLETE depuis la barre d'adresse et colle-la ci-dessous.

STEP
	read -rp "> URL de redirection (127.0.0.1:3000/?code=...): " RURL
	# keep everything from the path onwards, retarget it at the real callback port
	QS="/${RURL#*://*/}"
	curl -s "http://127.0.0.1:${CB}${QS}" >/dev/null 2>&1 || true

	printf 'Finalisation'
	for _ in $(seq 1 40); do
		docker logs "${NAME}-login" 2>&1 | grep -aq 'Setting user' && break
		printf '.'; sleep 3
	done
	printf '\n'
	docker logs "${NAME}-login" 2>&1 | grep -a 'Setting user' | tail -1 \
		|| say "Pas encore vu 'Setting user' - verifie run/devauth/microsoft_accounts.json"
	docker rm -f "${NAME}-login" >/dev/null 2>&1 || true
fi

# --- launch ----------------------------------------------------------------
say "Demarrage du bot"
docker compose up -d --build

IP="$(curl -s -4 -m 5 ifconfig.me 2>/dev/null || echo '<ip-ou-domaine>')"
cat <<DONE

============================================================
 StasisBot '$NAME' est lance.
 Control endpoint : http://${IP}:${CTL}
 Secret           : $SECRET
 Logs             : docker compose logs -f
 Couper / relancer: docker compose stop  |  docker compose up -d --build
============================================================
 Pense a ouvrir le port ${CTL}/tcp (ufw, et/ou la redirection de ta box).
DONE
