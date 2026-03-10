package main;

import javax.swing.ImageIcon;
import javax.swing.JFrame;

public class Main {

    public static JFrame window;
    public static void main(String[] args) {

        // Enable OpenGL Java2D pipeline for smoother rendering.
        // V-Sync on/off is controlled from in-game settings.
        System.setProperty("sun.java2d.opengl", "True");

        window = new JFrame();
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setResizable(true);
        window.setTitle("Type Shit !");
        new Main().setIcon();

        GamePanel gamePanel = new GamePanel();
        window.add(gamePanel);

        gamePanel.config.loadConfig();

        window.pack();

        window.setLocationRelativeTo(null);
        window.setVisible(true);

        gamePanel.setupGame();
        gamePanel.startGameThread();

    }
    public void setIcon() {
        java.net.URL iconUrl = Main.class.getResource("/res/icon.png");
        if (iconUrl != null) {
            ImageIcon icon = new ImageIcon(iconUrl);
            window.setIconImage(icon.getImage());
        } else {
            System.out.println("Warning: icon not found at /res/icon.png");
        }
    }
}
