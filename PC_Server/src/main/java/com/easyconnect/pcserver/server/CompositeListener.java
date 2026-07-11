package com.easyconnect.pcserver.server;

import java.nio.file.Path;
import java.util.List;

/**
 * Fans every {@link ServerListener} callback out to several delegates. Lets the
 * daemon feed one event stream to both the GUI IPC broadcaster and the desktop
 * notifications without {@code ForwardingListener} needing more than one slot.
 * A throwing delegate never blocks the others.
 */
public final class CompositeListener implements ServerListener {

    private final List<ServerListener> targets;

    public CompositeListener(ServerListener... targets) {
        this.targets = List.of(targets);
    }

    private void each(java.util.function.Consumer<ServerListener> action) {
        for (ServerListener t : targets) {
            try {
                action.accept(t);
            } catch (RuntimeException e) {
                System.err.println("[listener] delegate failed: " + e.getMessage());
            }
        }
    }

    @Override public void onListening(int port) { each(t -> t.onListening(port)); }
    @Override public void onClientConnected(String remote) { each(t -> t.onClientConnected(remote)); }
    @Override public void onClientRejected(String remote) { each(t -> t.onClientRejected(remote)); }
    @Override public void onClientDisconnected(String remote) { each(t -> t.onClientDisconnected(remote)); }
    @Override public void onFileReceived(Path file) { each(t -> t.onFileReceived(file)); }
    @Override public void onError(String message) { each(t -> t.onError(message)); }
}
