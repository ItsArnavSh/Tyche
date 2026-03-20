package org.Tyche;

import org.Tyche.src.server.Server;

public class App {

    public static void main(String[] args) throws Exception {
        Server server = new Server();
        server.StartServer();
    }
}
