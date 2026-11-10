package org.Tyche;

import org.Tyche.src.core.producer.RealVal.zerodha.ZerodhaAPI;
import org.Tyche.src.server.Server;

public class App {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && isAuthCommand(args[0])) {
            runKiteAuth(args);
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("Thread crashed: " + thread.getName());
            throwable.printStackTrace();
        });
        Server server = new Server();
        server.StartServer();
    }

    private static boolean isAuthCommand(String command) {
        return "kite-login-url".equals(command) || "kite-session".equals(command);
    }

    /**
     * Small operator CLI for the one-time Kite Connect login flow.
     *
     * <pre>
     *   app kite-login-url            # prints the URL to open in a browser
     *   app kite-session &lt;request_token&gt;  # exchanges it for an access token
     * </pre>
     */
    private static void runKiteAuth(String[] args) throws Exception {
        if (Config.KITE_API_KEY.isBlank()) {
            System.err.println("TYCHE_KITE_API_KEY is required");
            System.exit(1);
        }
        if ("kite-session".equals(args[0]) && Config.KITE_API_SECRET.isBlank()) {
            System.err.println("TYCHE_KITE_API_SECRET is required to exchange a request token");
            System.exit(1);
        }
        ZerodhaAPI api = new ZerodhaAPI(Config.KITE_BASE_URL, Config.KITE_API_KEY,
                Config.KITE_API_SECRET, Config.KITE_ACCESS_TOKEN, Config.KITE_DEFAULT_EXCHANGE,
                Config.HTTP_RETRIES, 350L);
        if ("kite-login-url".equals(args[0])) {
            System.out.println(api.loginUrl());
            return;
        }
        if (args.length < 2 || args[1].isBlank()) {
            System.err.println("usage: app kite-session <request_token>");
            System.exit(1);
        }
        System.out.println(api.generateSession(args[1]));
    }
}
