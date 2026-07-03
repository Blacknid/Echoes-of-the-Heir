package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;

import platform.GameStorage;

/**
 * Manages a persistent list of saved multiplayer servers.
 * Stored in servers.txt as: name|ip|port per line.
 */
public class ServerListManager {

    private static final String FILE_PATH = "servers.txt";
    private final ArrayList<String[]> servers = new ArrayList<>();

    public ServerListManager() {
        load();
    }

    public ArrayList<String[]> getServers() {
        return servers;
    }

    public void addServer(String name, String ip, String port) {
        servers.add(new String[]{name, ip, port});
        save();
    }

    public void removeServer(int index) {
        if (index >= 0 && index < servers.size()) {
            servers.remove(index);
            save();
        }
    }

    private void load() {
        servers.clear();
        if (!GameStorage.exists(FILE_PATH)) return;
        try (BufferedReader br = GameStorage.bufferedReader(FILE_PATH)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    servers.add(new String[]{parts[0], parts[1], parts[2]});
                }
            }
        } catch (IOException e) {
            System.out.println("[ServerList] Could not load servers.txt: " + e.getMessage());
        }
    }

    private void save() {
        try (BufferedWriter bw = GameStorage.bufferedWriter(FILE_PATH)) {
            for (String[] s : servers) {
                bw.write(s[0] + "|" + s[1] + "|" + s[2]);
                bw.newLine();
            }
        } catch (IOException e) {
            System.out.println("[ServerList] Could not save servers.txt: " + e.getMessage());
        }
    }
}
