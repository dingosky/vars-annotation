package org.mbari.vars.annotation.test.ui.mediaplayers.sharktopoda2;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mbari.vars.annotation.ui.mediaplayers.sharktopoda2.MediaControlsFactoryImpl;
import org.mbari.vars.vampiresquid.sdk.r1.models.Media;

import java.net.DatagramSocket;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MediaControlsFactoryImplTest {

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
     * If open() fails after the RemoteControl has been built (e.g. the media URI can't be
     * converted to a URL), the RemoteControl must be closed so its PlayerIO releases the
     * local UDP port. If the port stays bound, every subsequent open gets a PlayerIO whose
     * socket silently failed to bind and the incoming channel from Sharktopoda is dead
     * until the app is restarted.
     */
    @Test
    public void openReleasesLocalPortWhenOpenFails() throws Exception {
        int remotePort;
        int localPort;
        try (var a = new DatagramSocket(0); var b = new DatagramSocket(0)) {
            remotePort = a.getLocalPort();
            localPort = b.getLocalPort();
        }

        var media = new Media();
        media.setVideoReferenceUuid(UUID.randomUUID());
        // A valid URI that is not a URL. OpenCmd creation calls media.getUri().toURL(),
        // which throws after the RemoteControl (and its UDP socket) has been created.
        media.setUri(URI.create("urn:not:a:url"));

        var factory = new MediaControlsFactoryImpl();
        var mediaPlayer = factory.open(media, remotePort, localPort).get(30, TimeUnit.SECONDS);
        assertNotNull(mediaPlayer, "Expected the fallback (noop) media player");

        assertDoesNotThrow(() -> {
                    try (var socket = new DatagramSocket(localPort)) {
                        // Binding succeeds only if the failed open released its socket
                    }
                },
                "The local UDP port was still bound after a failed open");
    }
}
