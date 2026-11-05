#!/usr/bin/env python3
"""Checks the VPN node from the server itself, bypassing the client's network.

Fetches the subscription and, for each vless://, trojan:// and hysteria2:// (hy2://) link, runs
Xray-core (same core as the app) as a local SOCKS client against it and requests a page through it.

    python3 node-selftest.py 'https://<server>:<port>/<subscription path>'
"""
import base64, json, os, re, ssl, subprocess, sys, time, urllib.parse, urllib.request, zipfile

XRAY_VERSION = "v26.10.21"
WORK = "/root/xray-selftest"
SCHEMES = ("vless", "trojan", "hysteria2", "hy2")


def fetch_subscription(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "EthicVPN/1.0", "x-hwid": "server-selftest",
        "x-device-os": "Linux", "x-ver-os": "selftest", "x-device-model": "server",
    })
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE  # own panel on an IP address
    body = urllib.request.urlopen(req, context=ctx, timeout=15).read().decode()
    if "://" not in body:
        body = base64.b64decode(body.strip() + "=" * (-len(body.strip()) % 4)).decode()
    links = [l.strip() for l in body.splitlines() if l.strip().split("://")[0].lower() in SCHEMES]
    if not links:
        sys.exit("В подписке нет vless://, trojan:// или hysteria2:// ссылок")
    return links


def outbound(link):
    scheme = link.split("://")[0].lower()
    if scheme in ("hysteria2", "hy2"):
        # Port hopping ("443,20000-30000") is not a URL port; connect to the first one.
        head, at, tail = link.rpartition("@")
        link = head + at + re.sub(r"(:\d+)[,-][\d,-]*", r"\1", tail)
    u = urllib.parse.urlsplit(link)
    q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query.replace("+", "%2B")).items()}
    secret = urllib.parse.unquote(u.netloc.rpartition("@")[0])
    if scheme in ("hysteria2", "hy2"):
        tls = {"serverName": q.get("sni") or u.hostname, "alpn": (q.get("alpn") or "h3").split(",")}
        if q.get("pinSHA256"):
            tls["pinnedPeerCertSha256"] = q["pinSHA256"]
        s = {"network": "hysteria", "security": "tls", "tlsSettings": tls,
             "hysteriaSettings": {"version": 2, "auth": secret}}
        if q.get("obfs") == "salamander":
            s["finalmask"] = {"udp": [{"type": "salamander", "settings": {"password": q.get("obfs-password", "")}}]}
        return {"tag": "proxy", "protocol": "hysteria",
                "settings": {"version": 2, "address": u.hostname, "port": u.port}, "streamSettings": s}
    net = {"": "tcp", "raw": "tcp", "splithttp": "xhttp"}.get(q.get("type", ""), q.get("type"))
    sec = q.get("security") or ("tls" if scheme == "trojan" else "none")
    s = {"network": net, "security": sec}
    sni = q.get("sni") or q.get("host", "")
    if sec == "reality":
        s["realitySettings"] = {"serverName": sni, "fingerprint": q.get("fp") or "chrome",
                                "password": q.get("pbk", ""), "shortId": q.get("sid", ""),
                                **({"spiderX": q["spx"]} if q.get("spx") else {})}
    elif sec == "tls":
        s["tlsSettings"] = {"serverName": sni, **({"fingerprint": q["fp"]} if q.get("fp") else {})}
    if net == "xhttp":
        x = {"path": q.get("path") or "/", **({"host": q["host"]} if q.get("host") else {}),
             **({"mode": q["mode"]} if q.get("mode") else {})}
        if q.get("extra"):
            x.update(json.loads(q["extra"]))
        s["xhttpSettings"] = x
    elif net in ("ws", "httpupgrade"):
        s[net + "Settings" if net == "ws" else "httpupgradeSettings"] = {"path": q.get("path") or "/", "host": q.get("host", "")}
    elif net == "grpc":
        s["grpcSettings"] = {"serviceName": q.get("serviceName", ""), "multiMode": q.get("mode") == "multi"}
    if scheme == "trojan":
        return {"tag": "proxy", "protocol": "trojan", "streamSettings": s,
                "settings": {"address": u.hostname, "port": u.port, "password": secret}}
    user = {"address": u.hostname, "port": u.port, "id": urllib.parse.unquote(u.username),
            "encryption": q.get("encryption") or "none", **({"flow": q["flow"]} if q.get("flow") else {})}
    return {"tag": "proxy", "protocol": "vless", "settings": user, "streamSettings": s}


def ensure_xray():
    exe = os.path.join(WORK, "xray")
    if not os.path.exists(exe):
        os.makedirs(WORK, exist_ok=True)
        url = f"https://github.com/XTLS/Xray-core/releases/download/{XRAY_VERSION}/Xray-linux-64.zip"
        print("Скачиваю Xray", XRAY_VERSION)
        urllib.request.urlretrieve(url, os.path.join(WORK, "xray.zip"))
        with zipfile.ZipFile(os.path.join(WORK, "xray.zip")) as z:
            z.extract("xray", WORK)
        os.chmod(exe, 0o755)
    return exe


def test(exe, link, port):
    name = urllib.parse.unquote(urllib.parse.urlsplit(link).fragment) or link[:40]
    cfg = {"log": {"loglevel": "warning"},
           "inbounds": [{"listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": {"udp": False}}],
           "outbounds": [outbound(link)]}
    path = os.path.join(WORK, f"c{port}.json")
    json.dump(cfg, open(path, "w"))
    log = open(os.path.join(WORK, f"c{port}.log"), "w")
    proc = subprocess.Popen([exe, "run", "-c", path], stdout=log, stderr=subprocess.STDOUT)
    time.sleep(1.5)
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "12", "-w", "%{http_code} %{time_total}s",
                        "--socks5-hostname", f"127.0.0.1:{port}", "https://www.gstatic.com/generate_204"],
                       capture_output=True, text=True)
    proc.terminate()
    proc.wait()
    ok = r.stdout.startswith("204")
    print(f"\n== {name}\n   {'РАБОТАЕТ' if ok else 'НЕ РАБОТАЕТ'}: {r.stdout or r.stderr.strip()}")
    if not ok:
        print("   журнал Xray:\n" + "".join("   " + l for l in open(log.name).readlines()[-12:]))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    exe = ensure_xray()
    for i, link in enumerate(fetch_subscription(sys.argv[1])):
        test(exe, link, 10808 + i)
