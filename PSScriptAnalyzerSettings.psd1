@{
    Severity     = @('Error', 'Warning')
    ExcludeRules = @(
        # The scripts are interactive installers; Write-Host is the intended output channel.
        'PSAvoidUsingWriteHost',
        # Start/Stop-ProxyAgent are internal helpers of a CLI that already takes an explicit command.
        'PSUseShouldProcessForStateChangingFunctions',
        # `docker inspect` is used as an existence probe; its failure is the expected outcome.
        'PSAvoidUsingEmptyCatchBlock',
        # Naming style only.
        'PSUseSingularNouns',
        # Write-Log is a local helper; the rule flags it against a cmdlet of the same name in a PowerShell Core build.
        'PSAvoidOverwritingBuiltInCmdlets'
    )
}
