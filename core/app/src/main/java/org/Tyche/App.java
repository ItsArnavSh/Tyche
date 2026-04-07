package org.Tyche;

import org.Tyche.src.server.Server;

public class App {

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("Thread crashed: " + thread.getName());
            throwable.printStackTrace();
        });
        Server server = new Server();
        server.StartServer();
    }
}
