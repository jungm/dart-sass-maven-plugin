package com.github.cleydyr.dart.embedded;

import com.sass_lang.embedded_protocol.OutboundMessage;
import de.larsgrefer.sass.embedded.logging.LoggingHandler;
import org.apache.maven.plugin.logging.Log;

public class MavenLoggingHandler implements LoggingHandler {
    private final Log mvnLog;

    private final boolean quiet;

    public MavenLoggingHandler(Log mvnLog, boolean quiet) {
        this.mvnLog = mvnLog;
        this.quiet = quiet;
    }

    @Override
    public void handle(OutboundMessage.LogEventOrBuilder logEvent) {
        if (quiet) {
            return;
        }

        switch (logEvent.getType()) {
            case WARNING:
            case DEPRECATION_WARNING:
                mvnLog.warn(logEvent.getFormatted());
                break;

            case DEBUG:
                mvnLog.debug(logEvent.getFormatted());
                break;

            default:
                mvnLog.info(logEvent.getFormatted());
        }
    }
}
