#Requires -Version 5.1
& (Join-Path $PSScriptRoot 'manager.exe') @args
exit $LASTEXITCODE
