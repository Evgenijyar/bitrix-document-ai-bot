package ru.abs.bitrixdocbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class ApplicationProperties {

    private String dataDir = "./data";
    private final Admin admin = new Admin();
    private final Bitrix bitrix = new Bitrix();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Bitrix getBitrix() {
        return bitrix;
    }

    public static class Admin {
        private String username = "admin";
        private String password = "change-this-password";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Bitrix {
        private long pollDelayMs = 5000;

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }
    }
}
