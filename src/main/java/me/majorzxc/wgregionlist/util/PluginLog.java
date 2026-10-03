package me.majorzxc.wgregionlist.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Логгер плагина с отладочным режимом и префиксом мира. */
public final class PluginLog {

    private final Logger logger;
    private volatile boolean debug;

    public PluginLog(Logger logger) {
        this.logger = logger;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public void info(String message) {
        logger.info(message);
    }

    public void warn(String message) {
        logger.warning(message);
    }

    public void error(String message) {
        logger.severe(message);
    }

    public void error(String message, Throwable error) {
        logger.log(Level.SEVERE, message, error);
    }

    public void debug(String message) {
        if (debug) {
            logger.info("[debug] " + message);
        }
    }

    public void info(String world, String message) {
        info(prefix(world) + message);
    }

    public void warn(String world, String message) {
        warn(prefix(world) + message);
    }

    public void error(String world, String message) {
        error(prefix(world) + message);
    }

    public void debug(String world, String message) {
        debug(prefix(world) + message);
    }

    private static String prefix(String world) {
        return "[" + world + "] ";
    }
}
