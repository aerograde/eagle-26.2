"""Does the serverless path need the LocalNetworkAccess disable flags?

    python3 tmp/autoconnect/lna-probe.py [httpPort] [grant]

Serves dist/direct-connect.html over plain static HTTP on the machine's LAN
address and boots the inlined engine in two stock Chrome processes with **no
feature-disabling flags**.  It scores one thing: does the engine find the peer
(a `locate`/`peer` phase log, or a peer in its list) inside the wait window?

With `grant` it first grants the `local-network-access` permission through CDP,
which is what clicking Allow on Chrome's local-network prompt does.

Anything the browser refuses (a permission error, a blocked ICE candidate) shows
up on the console and is printed verbatim.
"""

import json
import subprocess
import sys
import time

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8125
grant = len(sys.argv) > 2 and sys.argv[2] not in ("0", "no", "false")
wait_s = 150

lan = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True).stdout.strip()
url = "http://%s:%d/dist/direct-connect.html" % (lan or "127.0.0.1", port)
print("url: %s | granted local-network-access: %s" % (url, grant), flush=True)

ARGS = ["--no-first-run", "--no-default-browser-check"]


def describe(page, name):
    return page.evaluate(
        "() => ({ state: window.__eaglerAC.state(), status: window.__eaglerAC.status(),"
        " peers: (window.__eaglerAC.peers ? window.__eaglerAC.peers() : []),"
        " logs: (window.__eaglerAC.logs ? window.__eaglerAC.logs() : []).map(l => l.phase + ': ' + l.message) })"
    ) if page.evaluate("() => !!window.__eaglerAC") else {"state": "no-engine", "logs": []}


with sync_playwright() as p:
    procs, pages = [], []
    for name in ("alice", "bob"):
        browser = p.chromium.launch(channel="chrome", headless=True, args=ARGS)
        context = browser.new_context()
        if grant:
            errs = []
            try:
                context.grant_permissions(["local-network-access"])
            except Exception as exc:  # noqa: BLE001
                errs.append("grant_permissions: %s" % exc)
            try:
                cdp = context.new_cdp_session(context.new_page())
                cdp.send("Browser.grantPermissions",
                         {"permissions": ["localNetworkAccess", "localNetwork", "local-network-access"],
                          "origin": "http://%s:%d" % (lan or "127.0.0.1", port)})
            except Exception as exc:  # noqa: BLE001
                errs.append("Browser.grantPermissions: %s" % exc)
            if errs:
                print("[%s] grant notes: %s" % (name, "; ".join(errs)), flush=True)
        page = context.new_page()
        page.on("console", lambda m, n=name: print("[%s] %s" % (n, m.text), flush=True)
                if ("permission" in m.text.lower() or "denied" in m.text.lower()
                    or "blocked" in m.text.lower() or "locate" in m.text
                    or "sweep" in m.text or "error" in m.text) else None)
        page.on("pageerror", lambda e, n=name: print("[%s] PAGEERROR %s" % (n, str(e)), flush=True))
        procs.append(browser)
        pages.append(page)

    for page in pages:
        page.goto(url, wait_until="load", timeout=120000)
    for page, name in zip(pages, ("alice", "bob")):
        page.wait_for_function("() => !!window.__eaglerAC", timeout=120000)
        print("[%s] engine present" % name, flush=True)
    for page, name in zip(pages, ("alice", "bob")):
        page.evaluate("() => window.__eaglerAC.start({ name: %r })" % name)

    deadline = time.time() + wait_s
    found = {}
    while time.time() < deadline:
        for page, name in zip(pages, ("alice", "bob")):
            if name in found:
                continue
            data = describe(page, name)
            hit = [l for l in data["logs"] if l.startswith("locate") or l.startswith("peer")]
            if data.get("peers") or hit:
                found[name] = {"peers": data.get("peers"), "log": hit[-3:]}
                print("[%s] PEER FOUND after %.0fs: %s" % (name, wait_s - (deadline - time.time()), hit[-1:]), flush=True)
        if len(found) == 2:
            break
        time.sleep(5)

    print("=== RESULT ===", flush=True)
    for page, name in zip(pages, ("alice", "bob")):
        data = describe(page, name)
        print("--- %s: state=%s peers=%s" % (name, data["state"], data.get("peers")), flush=True)
        print(json.dumps(data["logs"][-14:], indent=1), flush=True)
    print("VERDICT: %s" % ("discovery works with no LNA flags (%s)"
                           % ("granted" if grant else "ungranted") if len(found) == 2
                           else "discovery did NOT find the peer: %s" % list(found)), flush=True)

    for browser in procs:
        browser.close()

sys.exit(0 if len(found) == 2 else 1)
