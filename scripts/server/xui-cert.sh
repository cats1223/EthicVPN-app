#!/usr/bin/env bash
# Issues a Let's Encrypt certificate for the 3x-ui panel through DuckDNS DNS (port 80 stays with other services),
# installs it into the panel, hides the panel behind a random path and prints how to open it.
set -euo pipefail
DOMAIN=${DOMAIN:-techteampanel.duckdns.org}
XUI=/usr/local/x-ui/x-ui
ACME="$HOME/.acme.sh/acme.sh"
CERT_DIR=/root/cert/$DOMAIN

[ -x "$XUI" ] || { echo "3x-ui не установлена"; exit 1; }
[ -x "$ACME" ] || curl -fsSL https://get.acme.sh | sh -s email=admin@$DOMAIN

read -rp "Токен DuckDNS (с сайта duckdns.org, строка token): " DuckDNS_Token </dev/tty
export DuckDNS_Token

"$ACME" --set-default-ca --server letsencrypt
"$ACME" --issue --dns dns_duckdns -d "$DOMAIN" --dnssleep 60 || [ $? -eq 2 ]   # 2 = already issued and valid
mkdir -p "$CERT_DIR"
"$ACME" --install-cert -d "$DOMAIN" \
  --key-file "$CERT_DIR/privkey.pem" --fullchain-file "$CERT_DIR/fullchain.pem" \
  --reloadcmd "systemctl restart x-ui"

path=$("$XUI" setting -show 2>/dev/null | awk '/webBasePath/ {print $2}')
if [ -z "$path" ] || [ "$path" = "/" ]; then path="/tt$(openssl rand -hex 4)/"; fi
"$XUI" setting -webCert "$CERT_DIR/fullchain.pem" -webCertKey "$CERT_DIR/privkey.pem" -webBasePath "$path"
systemctl restart x-ui
sleep 2

port=$("$XUI" setting -show 2>/dev/null | awk '/^port/ {print $2}')
echo
echo "================================================================"
echo " Панель: https://$DOMAIN:${port:-2053}${path}"
echo "================================================================"
echo
echo "Кто занимает порт 443 (нужен для VPN):"
ss -ltnp | grep -E ':443 ' || echo "  никто, порт свободен"
