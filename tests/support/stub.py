"""Command stubs shared by the Bash and PowerShell test adapters.

Every invocation records an argument array, preserving spaces and quoting.
State lives in files so separate script processes see the same container.
"""
import json
import os
from pathlib import Path
import sys


def record(root, command, args):
    with (root / "calls.jsonl").open("a") as log:
        log.write(json.dumps([command, *args]) + "\n")


def powershell_parameters(command, args):
    """Bind the small cmdlet interfaces used by the scripts, failing on unknown options."""
    positional, allowed = {
        'Invoke-RestMethod': ('uri', {'uri', 'method', 'timeoutsec'}),
        'Invoke-WebRequest': ('uri', {'uri', 'method', 'timeoutsec', 'headers', 'outfile', 'usebasicparsing'}),
        'Get-CimInstance': ('classname', {'classname'}),
        'Get-PSDrive': ('name', {'name'}),
    }[command]
    parameters = {}
    arguments = iter(args)
    for argument in arguments:
        if argument.startswith('-'):
            name = argument[1:].lower()
            if name not in allowed | {'erroraction'}:
                raise ValueError(f'Unexpected {command} parameter: {argument}')
            if name == 'usebasicparsing':
                parameters[name] = True
            else:
                try:
                    parameters[name] = next(arguments)
                except StopIteration:
                    raise ValueError(f'Missing value for {command} parameter: {argument}') from None
        elif positional not in parameters:
            parameters[positional] = argument
        else:
            raise ValueError(f'Unexpected {command} argument: {argument}')
    if positional not in parameters:
        raise ValueError(f'Missing {command} parameter: {positional}')
    return parameters


def powershell_command(root, options, command, args):
    """PowerShell mocks return JSON, which the bridge converts to native objects."""
    parameters = powershell_parameters(command, args)
    if command in ('Invoke-RestMethod', 'Invoke-WebRequest'):
        record(root, 'request', [parameters['uri'], parameters.get('method', ''),
                                 str(parameters.get('timeoutsec', 0))])
        if command == 'Invoke-RestMethod':
            return {'tags': options['tags']}
        if 'outfile' in parameters:
            Path(parameters['outfile']).write_text(
                # Use the same JSON bridge for the downloaded manager's arguments.
                '$OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n'
                'ConvertTo-Json -InputObject @($args) -Compress | '
                '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB manager-json\n', encoding='ascii')
            return None
        return {'StatusCode': 200, 'Content': '{"agent_id":"abc123"}'}
    if command == 'Get-CimInstance':
        fixtures = {
            'win32_operatingsystem': {'ProductType': options['product_type'],
                                     'BuildNumber': options['build'], 'Caption': 'Stub Windows'},
            'win32_computersystem': {'TotalPhysicalMemory': 64 * 1024 ** 3,
                                    'NumberOfLogicalProcessors': 8},
        }
        name = parameters['classname']
        if name.lower() not in fixtures:
            raise ValueError(f'Unexpected CIM class: {name}')
        return fixtures[name.lower()]
    record(root, 'drive', [parameters['name']])
    return {'Name': parameters['name'], 'Free': options['free_mb'] * 1024 ** 2}


def main():
    command, *args = sys.argv[1:]
    root = Path(os.environ["AGENT_TEST_ROOT"])
    options = json.loads((root / "options.json").read_text())
    # PowerShell sends UTF-8 JSON instead of native arguments: Windows
    # PowerShell 5.1 otherwise strips embedded quotes and empty strings.
    if command.endswith('-json'):
        command = command.removesuffix('-json')
        args = json.loads(sys.stdin.buffer.read().decode('utf-8-sig'))
        if command != 'powershell':
            args = [str(argument) for argument in args]
    if command == 'powershell':
        name, *command_args = args
        try:
            response = powershell_command(root, options, name, command_args)
        except (KeyError, ValueError) as error:
            print(json.dumps({'error': str(error)}))
            return 1
        print(json.dumps(response))
        return 0
    record(root, command, args)
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
        elif args[0] != 'logs':
            raise ValueError(f'Unexpected docker command: {args[0]}')
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
    elif command not in ('request', 'drive', 'manager'):
        raise ValueError(f'Unexpected stub command: {command}')
    return 0


if __name__ == "__main__":
    sys.exit(main())
