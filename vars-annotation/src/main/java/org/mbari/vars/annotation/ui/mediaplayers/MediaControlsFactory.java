package org.mbari.vars.annotation.ui.mediaplayers;

import javafx.application.Platform;
import org.mbari.vars.vampiresquid.sdk.r1.models.Media;
import org.mbari.vcr4j.VideoError;
import org.mbari.vcr4j.VideoState;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Brian Schlining
 * @since 2017-12-28T12:24:00
 */
public interface MediaControlsFactory {

    /**
     * Should return the settings paen that can be displayed in a dialog
     * @return
     */
    SettingsPane getSettingsPane();

    /**
     *
     * @param media The media we want to open
     * @return true if this media can be opened by this factory. false if it can not
     */
    boolean canOpen(Media media);

    /**V
     * Open a media. Should not be called directly. Use safeOpen instead.
     * @param media
     * @return
     */
    CompletableFuture<MediaControls<? extends VideoState, ? extends VideoError>> open(Media media);

    default MediaControls<? extends VideoState, ? extends VideoError> safeOpen(Media media) {
        return safeOpen(media, Duration.ofSeconds(10));
    }

    default MediaControls<? extends VideoState, ? extends VideoError> safeOpen(Media media, Duration timeout) {
        if (!canOpen(media)) {
            throw new RuntimeException(getClass().getName() + " is unable to open media");
        }

        CompletableFuture<MediaControls<? extends VideoState, ? extends VideoError>> future;
        if (Platform.isFxApplicationThread()) {
            future = open(media);
        }
        else {
            // Call open() on the FX thread (implementations may build JavaFX controls)
            // but never block the FX thread waiting for the open to complete
            var onFxThread =
                    new CompletableFuture<CompletableFuture<MediaControls<? extends VideoState, ? extends VideoError>>>();
            Platform.runLater(() -> {
                try {
                    onFxThread.complete(open(media));
                } catch (Throwable t) {
                    onFxThread.completeExceptionally(t);
                }
            });
            future = onFxThread.thenCompose(f -> f);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // If the open completes after we've given up, nobody will ever close the
            // MediaPlayer. An orphaned player keeps resources (e.g. sharktopoda2's
            // local UDP port) that break every subsequent open, so close it on arrival.
            future.thenAccept(mediaControls -> {
                if (mediaControls != null && mediaControls.getMediaPlayer() != null) {
                    mediaControls.getMediaPlayer().close();
                }
            });
            throw new RuntimeException("Failed to open " + media + " using " + this, e);
        }
    }

}
