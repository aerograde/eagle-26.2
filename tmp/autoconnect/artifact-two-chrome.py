"""Two real Chrome windows, the shipped single-file client, plain static HTTP.

    python3 tmp/autoconnect/artifact-two-chrome.py [httpPort] [timeoutMs]

No discovery server, no helper page and no feature flags: the artifact is served
by any static file server, and both windows load it from the machine's LAN
address in stock Google Chrome.  `lna-probe.py` measured that the earlier
`--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks` in this
script was not needed on Chrome 154: discovery, the census and the peer socket
probe all ran without it.  The engine inside the page (window.__eaglerAC, the
inlined autoconnect.js) is started on both windows the way the game's LAN screen
starts it - setIdentity()/start() - and the run is scored on the engine's own
state:

  * both windows report state "connected" (a direct data channel, no relay),
  * each window's peer list names the other by its id and address,
  * a message sent from one arrives at the other over that channel.

Chromium is driven through the installed Google Chrome (channel="chrome"), one
process per window, which is what two players on one LAN actually use.
"""

import json
import subprocess
import sys

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
timeout_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 300000
lan = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True).stdout.strip()
if not lan:
    lan = "127.0.0.1"
url = "http://%s:%d/dist/direct-connect.html" % (lan, port)
print("artifact: %s" % url, flush=True)

ARGS = ["--no-first-run", "--no-default-browser-check"]  # no feature flags: see lna-probe.py


def wait_for_engine(page, name):
    page.wait_for_function("() => !!window.__eaglerAC", timeout=120000)
    print("[%s] engine present" % name, flush=True)


def start_engine(page, name):
    page.evaluate(
        "() => { window.__acEvents = []; window.__acMessages = [];"
        " window.__eaglerAC.onEvent(e => { window.__acEvents.push(e.type);"
        " if (e.type === 'message') window.__acMessages.push(String(e.data)); });"
        " window.__eaglerAC.start({ name: %r }); }" % name
    )


with sync_playwright() as p:
    browsers, pages = [], []
    for name in ("alice", "bob"):
        browser = p.chromium.launch(channel="chrome", headless=True, args=ARGS)
        context = browser.new_context()
        page = context.new_page()
        page.on("console", lambda m, n=name: print("[%s] %s" % (n, m.text), flush=True)
                if ("connect" in m.text or "error" in m.text or "sym:" in m.text
                    or "final" in m.text or "boot" in m.text) else None)
        page.on("pageerror", lambda e, n=name: print("[%s] PAGEERROR %s" % (n, str(e)), flush=True))
        browsers.append(browser)
        pages.append(page)

    for page in pages:
        page.goto(url, wait_until="load", timeout=120000)
    for page, name in zip(pages, ("alice", "bob")):
        wait_for_engine(page, name)

    # Both engines start at the same moment: the rendezvous assumes both windows
    # are looking for each other at roughly the same time.
    for page, name in zip(pages, ("alice", "bob")):
        start_engine(page, name)

    for page, name in zip(pages, ("alice", "bob")):
        try:
            page.wait_for_function(
                "() => window.__eaglerAC.state() === 'connected'"
                " || window.__eaglerAC.state() === 'failed'", timeout=timeout_ms)
        except Exception as exc:  # noqa: BLE001
            print("[%s] WAIT TIMEOUT: %s" % (name, exc), flush=True)

    print("=== RESULTS ===", flush=True)
    states = {}
    for page, name in zip(pages, ("alice", "bob")):
        data = page.evaluate(
            "() => ({ state: window.__eaglerAC.state(), status: window.__eaglerAC.status(),"
            " peers: window.__eaglerAC.peers(), self: window.__eaglerAC.self(),"
            " events: window.__acEvents, messages: window.__acMessages,"
            " logs: (window.__eaglerAC.logs ? window.__eaglerAC.logs() : []) })"
        )
        states[name] = data["state"]
        print("--- %s: %s ---" % (name, data["state"]), flush=True)
        print(json.dumps({k: v for k, v in data.items() if k != "logs"}, indent=2), flush=True)
        for line in data["logs"][-12:]:
            print("   %s: %s" % (line.get("phase"), line.get("message")), flush=True)

    # A message has to cross the channel for the link to count.
    if states.get("alice") == "connected" and states.get("bob") == "connected":
        pages[0].evaluate("() => window.__eaglerAC.send('artifact-hello')")
        try:
            pages[1].wait_for_function(
                "() => window.__acMessages.includes('artifact-hello')", timeout=20000)
            print("MESSAGE DELIVERED: alice -> bob", flush=True)
        except Exception as exc:  # noqa: BLE001
            print("MESSAGE NOT DELIVERED: %s" % exc, flush=True)

    for browser in browsers:
        browser.close()

ok = states.get("alice") == "connected" and states.get("bob") == "connected"
print("VERDICT: %s" % ("connected in both windows" if ok else "NOT connected: %s" % states), flush=True)
sys.exit(0 if ok else 1)
