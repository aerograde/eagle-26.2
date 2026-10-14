#!/usr/bin/env python3
"""Tier two: both peers enter the same six-character code at the same time.

The plan's code path derives the ICE credentials from the code, so the two pages
are synchronized by construction rather than by a handshake of their own.  This
runner is run-peers.py with one shared code on both URLs; everything else - the
two browser processes, the invite/answer round trip, the result dump - is the
same.
"""

import json
import sys

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
code = sys.argv[2] if len(sys.argv) > 2 else "AC24LAN"
timeout_ms = int(sys.argv[3]) if len(sys.argv) > 3 else 340000

url = "http://127.0.0.1:%d/tmp/autoconnect/peer.html" % port

with sync_playwright() as p:
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

    for i, page in enumerate(pages):
        page.goto("%s?name=%s&code=%s" % (url, "alice" if i == 0 else "bob", code))

    for page in pages:
        try:
            page.wait_for_function(
                "() => window.__result && window.__result.terminal", timeout=timeout_ms
            )
        except Exception as exc:  # noqa: BLE001 - report, do not hide
            print("WAIT TIMEOUT:", exc, flush=True)

    for page in pages:
        page.wait_for_timeout(3000)

    print("=== RESULTS (code %s) ===" % code, flush=True)
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
