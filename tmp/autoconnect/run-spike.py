import json
import sys

from playwright.sync_api import sync_playwright


def arg(i, default=None):
    return sys.argv[i] if len(sys.argv) > i else default


port = int(arg(1, "8123"))
page_name = arg(2, "spike2.html")
query = arg(3, "")
timeout = int(arg(4, "180000"))

url = "http://127.0.0.1:%d/%s" % (port, page_name)
if query:
    url += "?" + query
print("URL:", url, flush=True)

with sync_playwright() as p:
    browser = p.chromium.launch(
        headless=True,
        args=[
            "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks",
            "--no-first-run",
        ],
    )
    page = browser.new_page()
    page.on("console", lambda m: print("CONSOLE:", m.text, flush=True))
    page.on("pageerror", lambda e: print("PAGEERROR:", str(e), flush=True))
    page.goto(url)
    page.wait_for_function(
        "() => window.__spike && (window.__spike.ok || window.__spike.error)",
        timeout=timeout,
    )
    print("=== RESULT ===", flush=True)
    print(json.dumps(page.evaluate("window.__spike"), indent=2), flush=True)
    browser.close()
