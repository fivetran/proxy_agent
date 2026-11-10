# pypsrp dependency review

Pin: `pypsrp==1.0.0b1`, tag [`v1.0.0b1`](https://github.com/jborean93/pypsrp/tree/15fc61909d52ea040f966ab8fd8cd6ce02e282ae).
Its published wheel's `psrp` Python sources match that commit. Wheel SHA-256:
`57662f5afd1cffb0885259582bf7d664dafbde2f5fdff79ffd22dec4575b427d`.
The protocol dependency is also pinned: `psrpcore==0.3.1`.

This is the 2022 prerelease that includes the local-process API. Stable 0.9.1
supports WSMan rather than this API. We use local stdin/stdout transport, without
WinRM, a listening service, or remote authentication. Microsoft's comparable
hosting SDK is for .NET; its Python hosting example uses community `pythonnet`.

## Code reviewed

- [`process.py`](https://github.com/jborean93/pypsrp/blob/15fc61909d52ea040f966ab8fd8cd6ce02e282ae/src/psrp/_connection/process.py): process creation, pipe transport, cleanup, and the documented
  `-Version 5.1 -ServerMode` requirement for Windows PowerShell.
- [`out_of_proc.py`](https://github.com/jborean93/pypsrp/blob/15fc61909d52ea040f966ab8fd8cd6ce02e282ae/src/psrp/_connection/out_of_proc.py): acknowledgement waits, listener errors, EOF, and pool shutdown.
- [`_sync.py`](https://github.com/jborean93/pypsrp/blob/15fc61909d52ea040f966ab8fd8cd6ce02e282ae/src/psrp/_sync.py) and [`_host.py`](https://github.com/jborean93/pypsrp/blob/15fc61909d52ea040f966ab8fd8cd6ce02e282ae/src/psrp/_host.py): structured arguments, pipeline failures, output streams, and host calls.
- Upstream process fixtures and host tests; installed psrpcore 0.3.1 command and
  parameter binding code. Arguments are serialized as values, not source text.

## Findings applied in run.py

- `ProcessInfo` has no environment or timeout options. `LocalProcess` supplies
  the sandbox environment, removes inherited `PSModulePath`, and kills a hung
  shell. Its private `SyncProcessConnection` import is tied to the version pin.
- EOF clears acknowledgement waits but does not fail pending pipeline waits.
  The timeout probe reproduced a hang after killing the host. `LocalConnection`
  marks unexpected EOF as a broken runspace so those waits also finish.
- Pipeline completion does not imply script exit code zero. Invoke the script
  as a nested command and capture `$LASTEXITCODE`; Python handles terminating
  exceptions and queries observations in the same runspace afterward.
- `Write-Host` arrives on the information stream. Collect it alongside normal
  output and error/warning/verbose records to retain the suite's transcript.
- A second pipeline cannot run during a mock callback. Existing Python mock
  helpers remain subprocesses; their data is base64-bound in a fixed native
  expression. Helper failures also persist outside the shell so catching an
  exception cannot hide broken infrastructure.

Live PowerShell 7 checks cover argument transport, switch parameters, nonzero
exit status, timeout recovery, and observations after exceptions. Windows
PowerShell 5.1 and Windows ACL behavior require the existing Windows CI matrix.
Recheck these transport assumptions before changing either dependency pin.
