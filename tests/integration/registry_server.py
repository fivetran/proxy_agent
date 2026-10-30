#!/usr/bin/env python3
"""Stand-in for the two remote endpoints the installers call.

Serves ``/v2/<repository>/tags/list`` as a container registry tag listing with
the tags given on the command line, and every other path as a static file from
``--root`` (the repository checkout), which is how the installers download the
management script under test instead of the one on GitHub's main branch.
"""
import argparse
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8181)
    parser.add_argument("--root", required=True, help="directory to serve static files from")
    parser.add_argument("--tags", required=True, help="comma-separated image tags to list")
    args = parser.parse_args()
    tags = args.tags.split(",")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *handler_args, **handler_kwargs):
            super().__init__(*handler_args, directory=args.root, **handler_kwargs)

        def do_GET(self):
            if self.path.startswith("/v2/") and self.path.endswith("/tags/list"):
                body = json.dumps({"name": "stub", "tags": tags}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            super().do_GET()

        def log_message(self, fmt, *log_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"stub registry listening on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
