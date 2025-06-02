package org.slackerdb.dbservice.logger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.AbstractDiscriminator;
import org.slf4j.Marker;

import java.util.List;

public class MarkerNameBasedDiscriminator extends AbstractDiscriminator<ILoggingEvent> {
    private static final String KEY = "markerName";

    public String getKey() {
        return KEY;
    }

    public String getDiscriminatingValue(ILoggingEvent e) {
        List<Marker> eventMarkers = e.getMarkerList();
        if (eventMarkers == null) {
            return "log-" + e.getLevel().levelStr.toLowerCase();
        }
        return eventMarkers.get(0).getName() + "-" + e.getLevel().levelStr.toLowerCase();
    }
}
