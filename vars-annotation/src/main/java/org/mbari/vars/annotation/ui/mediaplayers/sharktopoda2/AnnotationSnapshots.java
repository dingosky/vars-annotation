package org.mbari.vars.annotation.ui.mediaplayers.sharktopoda2;

import javafx.application.Platform;
import org.mbari.vars.annosaurus.sdk.r1.models.Annotation;
import org.mbari.vars.annotation.etc.jdk.Loggers;
import org.mbari.vars.annotation.ui.UIToolBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Data.annotations is a JavaFX ObservableList owned by the FX application thread. The
 * sharktopoda2 controllers run on other threads (PlayerIO's UDP receive thread, the
 * toolbox executor) and must not iterate it there: a concurrent mutation throws
 * ConcurrentModificationException inside the Rx subscriber, which cancels the
 * subscription and silently kills localization handling until the media is reopened.
 * This helper copies the list on the FX thread.
 */
class AnnotationSnapshots {

    private static final Loggers log = new Loggers(AnnotationSnapshots.class);

    private AnnotationSnapshots() {
        // static helper
    }

    static List<Annotation> snapshot(UIToolBox toolBox) {
        var data = toolBox.getData();
        if (Platform.isFxApplicationThread()) {
            return List.copyOf(data.getAnnotations());
        }
        var future = new CompletableFuture<List<Annotation>>();
        Platform.runLater(() -> {
            try {
                future.complete(List.copyOf(data.getAnnotations()));
            }
            catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            log.atWarn().withCause(e).log("Failed to snapshot the annotations on the FX thread");
            return List.of();
        }
    }
}
