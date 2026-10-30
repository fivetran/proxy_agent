#!/usr/bin/env python3
"""Stand-in for the remote endpoints the installers call.

- ``GET /v2/<repository>/tags/list``: a container registry tag listing with the
  tags given on the command line.
- ``POST /proxy-agent-configure``: see below; the Fivetran configure endpoint.
  Returns the ``--config`` file as the agent configuration when the request
  carries ``Authorization: Basic <--token>``, and 401 otherwise.
- Anything else: a static file from ``--root`` (the repository checkout), which
  is how the installers download the management script under test instead of
  the one on GitHub's main branch.
"""
import argparse
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CONFIGURE_PATH = "/proxy-agent/configure"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8181)
    parser.add_argument("--root", required=True, help="directory to serve static files from")
    parser.add_argument("--tags", required=True, help="comma-separated image tags to list")
    parser.add_argument("--token", required=True, help="base64 credential the configure endpoint accepts")
    parser.add_argument("--config", required=True, help="config.json the configure endpoint returns")
    args = parser.parse_args()
    tags = args.tags.split(",")
    config_body = Path(args.config).read_bytes()

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *handler_args, **handler_kwargs):
            super().__init__(*handler_args, directory=args.root, **handler_kwargs)

        def send_json(self, status, body):
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path.startswith("/v2/") and self.path.endswith("/tags/list"):
                self.send_json(200, json.dumps({"name": "stub", "tags": tags}).encode())
                return
            super().do_GET()

        def do_POST(self):
            if self.path != CONFIGURE_PATH:
                self.send_error(404)
                return
            if self.headers.get("Authorization") == f"Basic {args.token}":
                self.send_json(200, config_body)
            else:
                self.send_json(401, json.dumps({"message": "unauthorized"}).encode())

        def log_message(self, fmt, *log_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"stub registry listening on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
