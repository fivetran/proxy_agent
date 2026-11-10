"""Platform selection and script invocation; no behavior assertions here."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
WINDOWS = sys.platform == 'win32'
SHELL = os.environ.get('PROXY_AGENT_TEST_SHELL', 'powershell' if WINDOWS else 'bash')
EXT = 'ps1' if WINDOWS else 'sh'
IMAGE = 'us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent'
REGISTRY = 'https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list'
TAGS = ['1.2.9-ubuntu-26.04', '1.2.10-ubuntu-26.04', '1.2.11',
        '1.2.10-windows-ltsc2022', '1.2.11-windows-ltsc2025', '1.2.8-windows-ltsc2019']


def invoke(script, arguments=(), *, parameters=None, env=None, timeout=30):
    if WINDOWS:
        # A parent pwsh process exports its own module paths. Let each child
        # host rebuild them so PowerShell 5.1 can load its Security module.
        env = (os.environ if env is None else env).copy()
        env = {key: value for key, value in env.items() if key.upper() != 'PSMODULEPATH'}
        command = [SHELL, '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(script)]
        command.extend(str(arg) for arg in arguments)
        for name, value in (parameters or {}).items():
            command.extend([f'-{name}', str(value)])
    else:
        command = [SHELL, str(script), *map(str, arguments)]
        for name, value in (parameters or {}).items():
            if name != 'InstallDir':
                raise ValueError(f'No Bash equivalent for {name}')
            command.extend(['--install-dir', str(value)])
    return subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors='replace', timeout=timeout)


class Sandbox:
    def __init__(self, container_os='linux', *, observe_proxy=False, observe_acl=False):
        self.temp = tempfile.TemporaryDirectory(prefix='proxy agent tests ')
        # Windows TEMP can use an 8.3 alias; PowerShell expands PSScriptRoot
        # to the long path, so use that same canonical path in expectations.
        self.root = Path(self.temp.name).resolve()
        self.agent = self.root / 'agent'
        (self.agent / 'config').mkdir(parents=True)
        self.config = self.agent / 'config/config.json'
        self.config.write_text('{"agent_id": "abc123"}')
        self.version = self.agent / 'version'
        self.version.write_text('1.2.9\n')
        self.manager = self.agent / f'proxy-agent-manager.{EXT}'
        shutil.copy2(REPO / self.manager.name, self.manager)
        self.installed = self.root / 'installed'
        self.options = dict(container_os=container_os, tags=TAGS.copy(), build=20348,
                            product_type=3, free_mb=100000)
        self.observe_proxy = observe_proxy
        self.observe_acl = observe_acl
        self.proxy_probes = []
        self.env = {key: value for key, value in os.environ.items()
                    if not (key.upper().endswith('_PROXY') or key.upper().startswith('PROXY_AGENT_')
                            or key.upper() in ('TOKEN', 'RUNTIME', 'MEMORY_ALLOCATION_MB', 'FIVETRAN_API_URL',
                                               'BASH_ENV', 'ENV'))}
        self.env.update(AGENT_TEST_ROOT=str(self.root), AGENT_TEST_PYTHON=sys.executable,
                        AGENT_TEST_STUB=str(REPO / 'tests/support/stub.py'), RUNTIME='docker',
                        MEMORY_ALLOCATION_MB='5120')
        if not WINDOWS:
            bin_dir = self.root / 'bin'
            bin_dir.mkdir()
            for name in ('docker', 'curl', 'id', 'df', 'date'):
                stub = bin_dir / name
                stub.write_text('#!/usr/bin/env bash\nexec "$AGENT_TEST_PYTHON" "$AGENT_TEST_STUB" '
                                + shlex.quote(name) + ' "$@"\n')
                stub.chmod(0o755)
            self.env['PATH'] = str(bin_dir) + os.pathsep + self.env['PATH']

    def close(self):
        self.temp.cleanup()

    def settings(self, **values):
        path = self.agent / f'settings.{EXT}'
        path.write_text('\n'.join((f'${key} = {value!r}' if WINDOWS else f'{key}={shlex.quote(str(value))}')
                                  for key, value in values.items()))
        return path

    def run(self, *arguments, install=False, parameters=None):
        (self.root / 'options.json').write_text(json.dumps(self.options))
        script = REPO / f'install.{EXT}' if install else self.manager
        if WINDOWS:
            path = self.root / 'request.json'
            path.write_text(json.dumps(self.windows_request(script, arguments, parameters or {})))
            return invoke(REPO / 'tests/support/run.ps1', [path], env=self.env)
        return invoke(script, arguments, parameters=parameters, env=self.env)

    def windows_request(self, script, arguments, parameters):
        """Prepare fixtures in Python; run.ps1 only binds and observes Windows objects."""
        arguments = list(arguments)
        parameters = parameters.copy()
        # Array splatting passes strings positionally. Named PowerShell common
        # parameters must instead use a hashtable so the host consumes them.
        if '-Verbose' in arguments:
            arguments.remove('-Verbose')
            parameters['Verbose'] = True
        return dict(
            script=str(script), arguments=arguments, parameters=parameters,
            system_proxy=self.options.get('system_proxy'),
            system_credentials=self.options.get('system_credentials', False),
            observe_proxy=self.observe_proxy, proxy_probes=self.proxy_probes,
            config_path=str(self.installed / 'config/config.json') if self.observe_acl else None,
            snapshot_path=str(self.root / 'snapshot.json'),
        )

    def install(self, **parameters):
        parameters = {'InstallDir': str(self.installed), 'ConfigPath': str(self.config), **parameters}
        if WINDOWS:
            return self.run(install=True, parameters=parameters)
        config = parameters.pop('ConfigPath')
        return self.run(*([config] if config else []), install=True, parameters=parameters)

    def calls(self, command):
        path = self.root / 'calls.jsonl'
        calls = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [call[1:] for call in calls if call[0] == command]

    @property
    def image(self):
        path = self.root / 'image'
        return path.read_text() if path.exists() else None

    @property
    def snapshot(self):
        return json.loads((self.root / 'snapshot.json').read_text(encoding='utf-8-sig'))
