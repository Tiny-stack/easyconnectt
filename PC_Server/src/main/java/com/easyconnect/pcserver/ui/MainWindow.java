package com.easyconnect.pcserver.ui;

import com.easyconnect.pcserver.pairing.Pairing;
import com.easyconnect.pcserver.server.ServerListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;

/**
 * Minimal, friendly control window: shows the pairing QR, a live status line,
 * and a shortcut to the received-files folder. All mutators marshal onto the
 * Swing thread so {@link ServerListener} callbacks can arrive from any thread.
 */
public final class MainWindow implements ServerListener {

    private final Pairing pairing;
    private final Path filesDir;
    private final Runnable onQuit;

    private JFrame frame;
    private JLabel statusLabel;
    private JLabel hintLabel;
    private JLabel titleLabel;

    // The center swaps between the pairing QR (before a phone connects) and a
    // "connected" confirmation (after), so a stale QR isn't left on screen.
    private CardLayout centerCards;
    private JPanel center;
    private static final String CARD_QR = "qr";
    private static final String CARD_CONNECTED = "connected";

    public MainWindow(Pairing pairing, Path filesDir, Runnable onQuit) {
        this.pairing = pairing;
        this.filesDir = filesDir;
        this.onQuit = onQuit;
    }

    public void show() {
        SwingUtilities.invokeLater(this::build);
    }

    private void build() {
        frame = new JFrame("PC Remote — Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        root.setBackground(Color.WHITE);

        titleLabel = new JLabel("Scan to connect");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20f));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel qr = new JLabel(new ImageIcon(pairing.qrImage(8, 4)));
        qr.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel qrCard = new JPanel();
        qrCard.setBackground(Color.WHITE);
        qrCard.setLayout(new BoxLayout(qrCard, BoxLayout.Y_AXIS));
        qr.setAlignmentX(0.5f);
        qrCard.add(qr);

        // Connection confirmation shown in place of the QR once a phone pairs.
        JLabel check = new JLabel("✓", SwingConstants.CENTER);
        check.setFont(check.getFont().deriveFont(Font.BOLD, 72f));
        check.setForeground(new Color(0x2E, 0x7D, 0x32));
        check.setAlignmentX(0.5f);
        JLabel connectedText = new JLabel("Phone connected", SwingConstants.CENTER);
        connectedText.setFont(connectedText.getFont().deriveFont(Font.PLAIN, 16f));
        connectedText.setForeground(new Color(0x33, 0x33, 0x33));
        connectedText.setAlignmentX(0.5f);
        JPanel connectedCard = new JPanel();
        connectedCard.setBackground(Color.WHITE);
        connectedCard.setLayout(new BoxLayout(connectedCard, BoxLayout.Y_AXIS));
        connectedCard.add(javax.swing.Box.createVerticalGlue());
        connectedCard.add(check);
        connectedCard.add(javax.swing.Box.createVerticalStrut(8));
        connectedCard.add(connectedText);
        connectedCard.add(javax.swing.Box.createVerticalGlue());

        centerCards = new CardLayout();
        center = new JPanel(centerCards);
        center.setBackground(Color.WHITE);
        center.add(qrCard, CARD_QR);
        center.add(connectedCard, CARD_CONNECTED);

        statusLabel = new JLabel("Waiting for a device…", SwingConstants.CENTER);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 14f));

        hintLabel = new JLabel(pairing.host() + " : " + pairing.port(), SwingConstants.CENTER);
        hintLabel.setForeground(new Color(0x66, 0x66, 0x66));

        JButton openFolder = new JButton("Open received files");
        openFolder.addActionListener(e -> openFolder());

        // Window X = hide (daemon keeps running). This button stops the daemon.
        JButton quit = new JButton("Quit server");
        quit.addActionListener(e -> {
            if (onQuit != null) {
                onQuit.run();
            }
        });

        JPanel south = new JPanel();
        south.setBackground(Color.WHITE);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        statusLabel.setAlignmentX(0.5f);
        hintLabel.setAlignmentX(0.5f);
        openFolder.setAlignmentX(0.5f);
        quit.setAlignmentX(0.5f);
        south.add(statusLabel);
        south.add(javax.swing.Box.createVerticalStrut(4));
        south.add(hintLabel);
        south.add(javax.swing.Box.createVerticalStrut(10));
        south.add(openFolder);
        south.add(javax.swing.Box.createVerticalStrut(6));
        south.add(quit);

        root.add(titleLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(360, 520));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void openFolder() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(filesDir.toFile());
            }
        } catch (Exception e) {
            setStatus("Can't open folder: " + e.getMessage());
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(text);
            }
        });
    }

    /** Swap the center between the QR and the "connected" confirmation. */
    private void showCard(String card, String title) {
        SwingUtilities.invokeLater(() -> {
            if (centerCards != null) {
                centerCards.show(center, card);
                titleLabel.setText(title);
            }
        });
    }

    // --- ServerListener ---

    @Override
    public void onListening(int port) {
        setStatus("Ready — waiting for a device…");
    }

    @Override
    public void onClientConnected(String remote) {
        showCard(CARD_CONNECTED, "Connected");
        setStatus("Connected: " + remote);
    }

    @Override
    public void onClientRejected(String remote) {
        setStatus("Rejected (wrong code): " + remote);
    }

    @Override
    public void onClientDisconnected(String remote) {
        showCard(CARD_QR, "Scan to connect");
        setStatus("Disconnected — waiting for a device…");
    }

    @Override
    public void onFileReceived(Path file) {
        setStatus("Received: " + file.getFileName());
    }

    @Override
    public void onError(String message) {
        setStatus("Error: " + message);
    }
}
