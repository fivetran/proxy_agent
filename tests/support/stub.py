"""Command stubs shared by the Bash and PowerShell test adapters.

Every invocation records an argument array, preserving spaces and quoting.
State lives in files so separate script processes see the same container.
"""
import json
import os
from pathlib import Path
import sys


def main():
    command, *args = sys.argv[1:]
    root = Path(os.environ["AGENT_TEST_ROOT"])
    options = json.loads((root / "options.json").read_text())
    with (root / "calls.jsonl").open("a") as log:
        log.write(json.dumps([command, *args]) + "\n")
    image = root / "image"
    if command == "docker":
        if args[0] == "--version":
            print("Docker version 27.0.0, build 0000000")
        elif args[0] == "info":
            if "{{.DockerRootDir}}" in args:
                print(options.get("docker_root", ""))
                return 0 if options.get("docker_root") else 1
            elif "{{.SecurityOptions}}" in args:
                print("[]")
            else:
                print(options["container_os"])
        elif args[0] == "run":
            if "--doctor" in args:
                print(options.get("doctor_report", "stub doctor report"), flush=True)
                print("stub doctor log", file=sys.stderr)
                if options.get("doctor_log"):
                    (root / "agent/logs/doctor-stub.log").write_text("stub doctor log\n")
                return options.get("doctor_exit", 0)
            image.write_text(next(arg for arg in args if "proxy-agent:" in arg))
            print("container-id")
        elif args[0] == "inspect":
            if not image.exists():
                return 1
            if "-f" in args:
                unhealthy = options.get("unhealthy_tag")
                print("unhealthy" if unhealthy and unhealthy in image.read_text() else "healthy")
        elif args[0] in ("stop", "rm"):
            image.unlink(missing_ok=True)
        elif args[0] == "ps":
            print("NAMES IMAGE STATUS")
    elif command == "curl":
        if "-o" in args:
            # The installer downloads a manager that only records its arguments.
            Path(args[args.index("-o") + 1]).write_text(
                '#!/usr/bin/env bash\nexec "$AGENT_TEST_PYTHON" "$AGENT_TEST_STUB" manager "$@"\n'
            )
        elif any("/proxy-agent/configure" in arg for arg in args):
            print(json.dumps({"agent_id": "abc123"}))
            print("200")
        else:
            print(json.dumps({"tags": options["tags"]}))
    elif command == "id":
        print(options.get("uid", 1000))
    elif command == "df":
        print("Filesystem 1M-blocks Used Available Use% Mounted on")
        print(f"stub 100000 0 {options.get('free_mb', 100000)} 0% /")
    elif command == "date":
        # Fix timestamps to exercise collisions between doctor reports.
        print("2020-01-02T03-04-05")
    return 0


if __name__ == "__main__":
    sys.exit(main())
