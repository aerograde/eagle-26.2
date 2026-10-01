#!/bin/bash
# Content-verified Brotli/gzip packaging; retain the existing shell entry point.
set -euo pipefail
exec node "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/precompress-web.js" "$@"
