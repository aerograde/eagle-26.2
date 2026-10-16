import json
import sys

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
timeout_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 180000

url = "http://127.0.0.1:%d/tmp/autoconnect/peer.html" % port

with sync_playwright() as p:
    # Two separate browser processes: Chrome caps the number of live
    # RTCPeerConnections per renderer, and sharing one process would impose that
    # cap on both peers at once.  Two machines would each have their own.
    browsers = []
    pages = []
    for i in range(2):
        name = "alice" if i == 0 else "bob"
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks",
                "--no-first-run",
            ],
        )
        browsers.append(browser)
        page = browser.new_context().new_page()
        page.on("console", lambda m, n=name: print("[%s] %s" % (n, m.text), flush=True))
        page.on("pageerror", lambda e, n=name: print("[%s] PAGEERROR %s" % (n, str(e)), flush=True))
        pages.append(page)

    # Start both peers at the same moment: the rendezvous assumes both pages are
    # looking for each other at roughly the same time.
    diag = "&acdiag=1" if __import__("os").environ.get("ACDIAG") == "1" else ""
    for i, page in enumerate(pages):
        page.goto("%s?name=%s%s" % (url, "alice" if i == 0 else "bob", diag))

    for page in pages:
        try:
            page.wait_for_function(
                "() => window.__result && window.__result.terminal", timeout=timeout_ms
            )
        except Exception as exc:  # noqa: BLE001 - report, do not hide
            print("WAIT TIMEOUT:", exc, flush=True)

    # Let the post-connect message exchange land.
    for page in pages:
        page.wait_for_timeout(3000)

    print("=== RESULTS ===", flush=True)
    for i, page in enumerate(pages):
        name = "alice" if i == 0 else "bob"
        data = page.evaluate(
            "() => ({ name: window.__result.name, state: window.__result.state,"
            " status: window.__result.status, terminal: window.__result.terminal,"
            " failure: window.__result.failure, self: window.__result.self,"
            " peers: window.__result.peers, messages: window.__result.messages,"
            " events: window.__result.events.map(e => e.type),"
            " inviteSent: window.__result.inviteSent || null,"
            " answerReceived: window.__result.answerReceived || null,"
            " inviteAnswerComplete: !!window.__result.inviteAnswerComplete,"
            " logs: (window.__eaglerAC.logs ? window.__eaglerAC.logs() : []) })"
        )
        print("--- %s ---" % name, flush=True)
        print(json.dumps(data, indent=2), flush=True)

    for browser in browsers:
        browser.close()
