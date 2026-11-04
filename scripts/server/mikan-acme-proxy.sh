#!/usr/bin/env bash
# Lets mikan get Let's Encrypt certificates while the messenger's Caddy (ethic-caddy-1) holds port 80:
# Caddy passes only /.well-known/acme-challenge/ of mikan's domains to mikan on 127.0.0.1:18080.
#   bash <(curl -fsSL .../mikan-acme-proxy.sh) [domain ...]   (default: ethicpanel and ethicvpn on duckdns.org)
set -euo pipefail
DOMAINS=("$@")
[ ${#DOMAINS[@]} -gt 0 ] || DOMAINS=(ethicpanel.duckdns.org ethicvpn.duckdns.org)
CADDYFILE=/opt/ethic/Caddyfile
CADDY=ethic-caddy-1
MIKAN_CONFIG=/opt/mikan/config.json
LISTEN=127.0.0.1:18080

backup="$CADDYFILE.bak.$(date +%s)"
cp "$CADDYFILE" "$backup"
echo "Копия Caddyfile: $backup"

# Drop earlier blocks of this script (marked) and the old hand-written techteamvpn block, then add one for DOMAINS.
python3 - "$CADDYFILE" "$LISTEN" "${DOMAINS[@]}" <<'PY'
import sys
path, listen, domains = sys.argv[1], sys.argv[2], sys.argv[3:]
lines = open(path).read().splitlines()
out, skip, depth = [], False, 0
for line in lines:
    s = line.strip()
    if not skip and (s == "# mikan-acme-proxy" or s.startswith("http://techteamvpn.duckdns.org")):
        skip, depth = True, 0
        if s == "# mikan-acme-proxy":
            continue
    if skip:
        depth += line.count("{") - line.count("}")
        if "{" in line and depth == 0 or "}" in line and depth <= 0:
            skip = False
        continue
    out.append(line)
while out and not out[-1].strip():
    out.pop()
sites = ", ".join("http://" + d for d in domains)
out += ["", "# mikan-acme-proxy", sites + " {",
        "\thandle /.well-known/acme-challenge/* {", "\t\treverse_proxy " + listen, "\t}",
        "\thandle {", "\t\trespond 404", "\t}", "}", ""]
open(path, "w").write("\n".join(out))
PY

if ! docker exec "$CADDY" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; then
    echo "Caddy не принял новый файл — возвращаю прежний, мессенджер не затронут"
    cp "$backup" "$CADDYFILE"
    docker exec "$CADDY" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile || true
    exit 1
fi
echo "Caddy: правило для ${DOMAINS[*]} добавлено"

python3 - "$MIKAN_CONFIG" "$LISTEN" <<'PY'
import json, sys
p, listen = sys.argv[1], sys.argv[2]
c = json.load(open(p))
c["MIKAN_ACME_LISTEN"] = listen
json.dump(c, open(p, "w"), indent=2)
PY
echo "mikan: MIKAN_ACME_LISTEN=$LISTEN"

mikan restart
echo "Жду 90 секунд, пока mikan получит сертификат…"
sleep 90
mikan doctor
