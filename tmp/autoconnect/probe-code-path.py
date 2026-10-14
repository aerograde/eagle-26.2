import json, sys
from playwright.sync_api import sync_playwright
port = int(sys.argv[1]); code = sys.argv[2]
url = "http://127.0.0.1:%d/tmp/autoconnect/peer.html?name=alice&code=%s" % (port, code)
lines = []
with sync_playwright() as p:
    b = p.chromium.launch(headless=True, args=["--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks"])
    pg = b.new_context().new_page()
    pg.on("console", lambda m: lines.append("CONSOLE " + m.text))
    pg.on("pageerror", lambda e: lines.append("PAGEERROR " + str(e)))
    pg.goto(url)
    pg.wait_for_timeout(9000)
    out = pg.evaluate("() => (window.__result ? {state: window.__result.state, status: window.__result.status, failure: window.__result.failure, enteredCode: window.__result.enteredCode, logs: (window.__eaglerAC && window.__eaglerAC.logs ? window.__eaglerAC.logs().slice(-8) : null)} : 'no result')")
    print(json.dumps(out, indent=2))
    b.close()
print("--- console ---")
for l in lines[:25]:
    print(l)
