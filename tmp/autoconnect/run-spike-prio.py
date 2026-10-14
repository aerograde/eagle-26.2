import json
import sys

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
addr = sys.argv[2] if len(sys.argv) > 2 else "192.168.0.100"
n = int(sys.argv[3]) if len(sys.argv) > 3 else 8

base = "http://127.0.0.1:%d/tmp/autoconnect/spike-prio.html" % port

with sync_playwright() as p:
    browser = p.chromium.launch(
        headless=True,
        args=[
            "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks",
            "--no-first-run",
        ],
    )
    recv = browser.new_context().new_page()
    send = browser.new_context().new_page()
    recv.on("console", lambda m: print("[recv] " + m.text, flush=True))
    send.on("console", lambda m: print("[send] " + m.text, flush=True))
    recv.on("pageerror", lambda e: print("[recv] PAGEERROR " + str(e), flush=True))
    send.on("pageerror", lambda e: print("[send] PAGEERROR " + str(e), flush=True))

    recv.goto(base + "?mode=recv")
    recv.wait_for_function("() => window.__spike.ports.length > 0", timeout=20000)
    ports = recv.evaluate("() => window.__spike.ports")
    print("listener ports:", ports, flush=True)

    send.goto("%s?mode=send&addr=%s&ports=%s&n=%d" % (base, addr, ",".join(str(x) for x in ports), n))
    send.wait_for_function("() => window.__spike.ok || window.__spike.error", timeout=90000)
    send.wait_for_timeout(4000)

    result = {
        "addr": addr,
        "listener": recv.evaluate("() => window.__spike"),
        "sender": send.evaluate("() => window.__spike"),
    }

print("=== SPIKE PRIO RESULT ===", flush=True)
print(json.dumps(result, indent=2), flush=True)
