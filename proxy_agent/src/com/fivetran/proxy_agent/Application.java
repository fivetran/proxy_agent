package com.fivetran.proxy_agent;

import picocli.CommandLine;

/** Entry point for the native Proxy Agent manager. */
@CommandLine.Command(name = "manager")
public final class Application implements Runnable {
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Application()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
