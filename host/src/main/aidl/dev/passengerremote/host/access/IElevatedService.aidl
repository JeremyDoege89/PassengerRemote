package dev.passengerremote.host.access;

/**
 * Runs inside Shizuku's privileged process.
 *
 * Shizuku's own newProcess() is private in api 13.x, and reflecting into it
 * fights the library's intent. A UserService is the supported mechanism, and it
 * generalises: anything else this app ever needs at shell privilege gets added
 * here rather than needing a second escape hatch.
 */
interface IElevatedService {
    /** Shizuku requires this exact transaction id to tear the service down. */
    void destroy() = 16777114;

    /** Runs a command, returns its exit code. -1 if it could not be started. */
    int runCommand(String command) = 1;

    /**
     * Runs a command and returns its combined output. Used by the in-app
     * verification so the user can see proof the privileged process is really
     * running, rather than a button that claims success.
     */
    String runForOutput(String command) = 2;
}
