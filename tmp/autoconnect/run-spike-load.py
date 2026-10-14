import json, sys
from playwright.sync_api import sync_playwright
port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
url = "http://127.0.0.1:%d/tmp/autoconnect/spike-load.html" % port
with sync_playwright() as p:
    b = p.chromium.launch(headless=True, args=["--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks"])
    pg = b.new_context().new_page()
    pg.on("console", lambda m: print(m.text, flush=True))
    pg.on("pageerror", lambda e: print("PAGEERROR", e, flush=True))
    pg.goto(url)
    try:
        pg.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)", timeout=180000)
    except Exception as exc:  # noqa: BLE001
        print("WAIT TIMEOUT:", exc, flush=True)
    print("=== stages ===", flush=True)
    print(json.dumps(pg.evaluate("() => window.__spike.stages"), indent=2), flush=True)
    print("ok=%s error=%s" % (pg.evaluate("() => window.__spike.ok"), pg.evaluate("() => window.__spike.error")), flush=True)
    b.close()
