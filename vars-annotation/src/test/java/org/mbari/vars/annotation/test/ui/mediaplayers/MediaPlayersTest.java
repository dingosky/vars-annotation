package org.mbari.vars.annotation.test.ui.mediaplayers;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mbari.vars.annotation.ui.Initializer;
import org.mbari.vars.annotation.ui.events.MediaChangedEvent;
import org.mbari.vars.annotation.ui.events.MediaControlsChangedEvent;
import org.mbari.vars.annotation.ui.mediaplayers.MediaControls;
import org.mbari.vars.annotation.ui.mediaplayers.MediaControlsFactory;
import org.mbari.vars.annotation.ui.mediaplayers.MediaPlayer;
import org.mbari.vars.annotation.ui.mediaplayers.MediaPlayers;
import org.mbari.vars.annotation.ui.mediaplayers.NoopImageCaptureService;
import org.mbari.vars.annotation.ui.mediaplayers.SettingsPane;
import org.mbari.vars.annotation.ui.mediaplayers.sharktopoda2.NoopVideoIO;
import org.mbari.vars.vampiresquid.sdk.r1.models.Media;
import org.mbari.vcr4j.VideoError;
import org.mbari.vcr4j.VideoState;
import org.mbari.vcr4j.remote.control.RError;
import org.mbari.vcr4j.remote.control.RState;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MediaPlayersTest {

    private static final long OPEN_DELAY_MS = 1500;

    @BeforeAll
    public static void initJavaFx() {
        try {
            Platform.startup(() -> {});
        }
        catch (IllegalStateException e) {
            // Toolkit already running
        }
    }

    /**
     * MediaChangedEvent is often sent from the JavaFX application thread (media dialogs,
     * settings pane). Opening a media involves blocking work (UDP round trips, waiting on
     * the player), so MediaPlayers must not run the open on the sender's thread — the UI
     * would freeze for the duration.
     */
    @Test
    public void openingMediaDoesNotBlockTheFxThread() throws Exception {
        var toolBox = Initializer.getToolBox();

        MediaControlsFactory factory = new MediaControlsFactory() {
            @Override
            public SettingsPane getSettingsPane() {
                return null;
            }

            @Override
            public boolean canOpen(Media media) {
                return true;
            }

            @Override
            public CompletableFuture<MediaControls<? extends VideoState, ? extends VideoError>> open(Media media) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(OPEN_DELAY_MS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    var mediaPlayer = new MediaPlayer<RState, RError>(media,
                            new NoopImageCaptureService(), new NoopVideoIO("test"), () -> {});
                    return new MediaControls<>(mediaPlayer, null);
                });
            }
        };
        var mediaPlayers = new MediaPlayers(toolBox, List.of(factory));

        var opened = new CountDownLatch(1);
        var disposable = toolBox.getEventBus()
                .toObserverable()
                .ofType(MediaControlsChangedEvent.class)
                .subscribe(evt -> opened.countDown());

        var media = new Media();
        media.setVideoReferenceUuid(UUID.randomUUID());
        media.setUri(URI.create("http://localhost/test.mp4"));

        try {
            // Send the event from the FX thread, with a probe runnable queued right
            // before it. The probe's latency is how long the FX thread was tied up.
            var probeLatencyMs = new CompletableFuture<Long>();
            Platform.runLater(() -> {
                long t0 = System.nanoTime();
                Platform.runLater(() -> probeLatencyMs.complete((System.nanoTime() - t0) / 1_000_000));
                toolBox.getEventBus().send(new MediaChangedEvent(this, media));
            });

            var latencyMs = probeLatencyMs.get(10, TimeUnit.SECONDS);
            assertTrue(opened.await(10, TimeUnit.SECONDS), "The media was never opened");
            assertTrue(latencyMs < 500,
                    "The FX thread was blocked for " + latencyMs + " ms while the media opened");
        }
        finally {
            disposable.dispose();
        }
    }
}
