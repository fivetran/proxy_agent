@{
    Severity     = @('Error', 'Warning')
    ExcludeRules = @(
        # The scripts are interactive installers; Write-Host is the intended output channel.
        'PSAvoidUsingWriteHost',
        # Start/Stop-ProxyAgent are internal helpers of a CLI that already takes an explicit command.
        'PSUseShouldProcessForStateChangingFunctions'
    )
}
