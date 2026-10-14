import json
import re
import subprocess
import sys

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
addr = sys.argv[2] if len(sys.argv) > 2 else None
if not addr:
    out = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True)
    addr = out.stdout.strip() or "127.0.0.1"

base = "http://127.0.0.1:%d/tmp/autoconnect/spike7.html" % port

with sync_playwright() as p:
    args = ["--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks", "--no-first-run"]

    recv_browser = p.chromium.launch(headless=True, args=args)
    recv = recv_browser.new_context().new_page()
    recv.on("console", lambda m: print("[recv] %s" % m.text, flush=True))
    recv.on("pageerror", lambda e: print("[recv] PAGEERROR %s" % str(e), flush=True))
    recv.goto("%s?mode=recv" % base)
    recv.wait_for_function("() => window.__spike && window.__spike.ports && window.__spike.ports.length > 0", timeout=30000)
    listener_port = recv.evaluate("window.__spike.ports[0]")
    print("listener port:", listener_port, "addr:", addr, flush=True)

    send_browser = p.chromium.launch(headless=True, args=args)
    send = send_browser.new_context().new_page()
    send.on("console", lambda m: print("[send] %s" % m.text, flush=True))
    send.on("pageerror", lambda e: print("[send] PAGEERROR %s" % str(e), flush=True))
    send.goto("%s?mode=send&addr=%s&targetPort=%d" % (base, addr, listener_port))

    send.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)", timeout=120000)
    recv.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)", timeout=60000)

    sent = send.evaluate("window.__spike.sent")
    own = send.evaluate("window.__spike.ports")
    arrivals = recv.evaluate("window.__spike.arrivals")

    print("=== SENDER ===", flush=True)
    print(json.dumps({"own_ports": own, "sent": sent}, indent=2), flush=True)
    print("=== RECEIVER ===", flush=True)
    print(json.dumps({"listener_port": listener_port, "arrivals": arrivals}, indent=2), flush=True)

    own_set = set(own)
    matched = [a for a in arrivals if a["port"] in own_set]
    print("=== VERDICT ===", flush=True)
    print("arrivals: %d, arrivals whose port is one of the sender's own ports: %d"
          % (len(arrivals), len(matched)), flush=True)
    print(json.dumps(matched, indent=2), flush=True)

    recv_browser.close()
    send_browser.close()
