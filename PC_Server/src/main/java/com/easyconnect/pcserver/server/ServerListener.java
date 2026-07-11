package com.easyconnect.pcserver.server;

import java.nio.file.Path;

/** UI-facing callbacks for server lifecycle events. All may fire off-thread. */
public interface ServerListener {

    void onListening(int port);

    void onClientConnected(String remote);

    void onClientRejected(String remote);

    void onClientDisconnected(String remote);

    void onFileReceived(Path file);

    default void onError(String message) {
        System.err.println("[server] " + message);
    }
}
