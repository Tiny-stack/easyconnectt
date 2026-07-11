package com.easyconnect.pcserver.server;

import java.nio.file.Path;

/**
 * A {@link ServerListener} whose delegate can be set after the server starts,
 * which breaks the construction cycle (the UI needs the bound port, the server
 * needs a listener at construction). Always logs to the console as well.
 */
public final class ForwardingListener implements ServerListener {

    private volatile ServerListener delegate;

    public void setDelegate(ServerListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onListening(int port) {
        System.out.println("[server] listening on port " + port);
        if (delegate != null) {
            delegate.onListening(port);
        }
    }

    @Override
    public void onClientConnected(String remote) {
        System.out.println("[server] connected: " + remote);
        if (delegate != null) {
            delegate.onClientConnected(remote);
        }
    }

    @Override
    public void onClientRejected(String remote) {
        System.out.println("[server] rejected (bad token): " + remote);
        if (delegate != null) {
            delegate.onClientRejected(remote);
        }
    }

    @Override
    public void onClientDisconnected(String remote) {
        System.out.println("[server] disconnected: " + remote);
        if (delegate != null) {
            delegate.onClientDisconnected(remote);
        }
    }

    @Override
    public void onFileReceived(Path file) {
        System.out.println("[server] file received: " + file);
        if (delegate != null) {
            delegate.onFileReceived(file);
        }
    }

    @Override
    public void onError(String message) {
        System.err.println("[server] " + message);
        if (delegate != null) {
            delegate.onError(message);
        }
    }
}
