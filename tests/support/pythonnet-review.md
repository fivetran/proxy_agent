# pythonnet dependency review

The bridge uses stable `pythonnet==3.2.1` and `clr-loader==0.3.1`. Python 3.11+
is required. Direct pins live in `tests/requirements.in`; `requirements.txt`
locks all transitive packages and artifact hashes for installation and CI audit.

## Source and artifacts checked

- pythonnet tag [`v3.2.1`](https://github.com/pythonnet/pythonnet/tree/c678e2c3c251f21582dd102b5de84a3d35eb189f): runtime selection and shutdown in
  `pythonnet/__init__.py`, assembly resolution in `AssemblyManager.cs`, and
  interface wrapping in `Types/InterfaceObject.cs`.
- clr-loader tag [`v0.3.1`](https://github.com/pythonnet/clr-loader/tree/b9497f6bf2544ae103caae3b8d4a8786c53ba4e4): `get_coreclr`, `get_netfx`, `hostfxr.py`,
  `netfx.py`, and platform library loading in `ffi/__init__.py`. CoreCLR can be
  initialized from a self-contained application's entry DLL.
- Python sources in the published wheels match their tags. Downloaded wheel
  SHA-256 values:
  - pythonnet: `dd5668b74b0c903d9a24e1ba86f728fc490f8e919d56b3c24bf06739b9ee2b7f`
  - clr-loader: `cbad189de20d202a7d621956b0fc38049e13c9bf7ca2923441eff725cd121aa1`

The bundled managed/native binaries were not independently rebuilt. Hashes
identify accepted artifacts; they do not prove their correspondence to source.

## Hosting decisions

- Query the selected `powershell` or `pwsh` executable for its engine version,
  installation directory, and automation assembly. Verify that the hosted
  engine reports exactly that version.
- PowerShell 5.1 loads .NET Framework and the discovered automation assembly.
  PowerShell 7 loads CoreCLR through its installation's `pwsh.dll` and hostfxr.
  No separate .NET SDK installation is needed.
- Fixtures in `tests/conftest.py` own the Windows runner. Each invocation spawns
  a Python child with its own CLR/runspace; fixture cleanup kills timed-out
  children and joins/closes every process. Runspace disposal and process exit
  isolate proxy state. The spawn target stays at module scope for Windows.
- Public PowerShell SDK methods bind .NET arrays, hashtables, and booleans.
  Native .NET APIs read proxy identity, credentials, bypasses, and Windows ACLs.
  The bridge has no private transport APIs or custom quoting algorithm.
- Nested script invocation preserves `$LASTEXITCODE`. SDK invocation buffers
  partial output before exceptions; Python collects the information stream
  used by `Write-Host` alongside normal/error/warning/verbose output.
- Mock callbacks retain UTF-8 JSON and fixed base64 binding expressions.
  An independent failure file prevents caught exceptions from hiding broken mocks.

PowerShell 7 checks were run locally for arguments, switches, timeout, exit codes,
proxy state, and selected manager/proxy contracts. PowerShell 5.1 and Windows
installation/ACL behavior require the existing Windows CI matrix. These are
hosted-engine tests; Docker integration still invokes the real shell executable.

## Maintenance and upgrades

pythonnet is [.NET Foundation supported](https://pythonnet.github.io/), with stable
releases and community maintainers. Microsoft's [hosting example](https://devblogs.microsoft.com/powershell/hosting-powershell-in-a-python-script/)
demonstrates this approach; it does not provide Microsoft support for pythonnet.
The project's [security page](https://github.com/pythonnet/pythonnet/security) currently
has no published security policy. Foundation support does not guarantee safe upgrades.

Update `requirements.in`, regenerate the lock, and review the version/artifact
changes before merging. CI checks known Python vulnerabilities and runs the
platform matrix. Its audit does not cover the bundled .NET/PowerShell runtime or
detect every malicious release; keep those runtimes patched through runner updates.

```sh
uv pip compile --universal --python-version 3.11 --generate-hashes tests/requirements.in -o tests/requirements.txt
```
