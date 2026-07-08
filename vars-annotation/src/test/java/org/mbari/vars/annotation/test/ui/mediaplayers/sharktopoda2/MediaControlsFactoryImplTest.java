package org.mbari.vars.annotation.test.ui.mediaplayers.sharktopoda2;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mbari.vars.annotation.ui.mediaplayers.sharktopoda2.MediaControlsFactoryImpl;
import org.mbari.vars.vampiresquid.sdk.r1.models.Media;
import org.mbari.vcr4j.remote.control.commands.FrameAdvanceCmd;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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

    /**
     * RVideoIO's command subject is serialized: when another thread is mid-send, holding
     * the emitter loop for a full UDP round trip, send() just queues the command and
     * returns. If close() then tears down the RemoteControl right away, the queued
     * CloseCmd is dropped and the video stays open in Sharktopoda.
     *
     * The fake player below acks everything instantly EXCEPT the frame-advance command,
     * which this test sends manually — so exactly one slow command reliably occupies the
     * emitter loop when close() runs. (It must be a command the monitoring decorator does
     * not send on a timer: withholding acks for periodic commands overloads the emitter
     * loop — 1 s of blocking every 333 ms — and starves the open itself.)
     */
    @Test
    public void closeCmdIsDeliveredEvenWhenAnotherCommandIsInFlight() throws Exception {
        int remotePort;
        int localPort;
        try (var a = new DatagramSocket(0); var b = new DatagramSocket(0)) {
            remotePort = a.getLocalPort();
            localPort = b.getLocalPort();
        }

        var closeReceived = new CountDownLatch(1);
        var playerSocket = new DatagramSocket(remotePort);
        var player = new Thread(() -> {
            var buffer = new byte[4096];
            try {
                while (!playerSocket.isClosed()) {
                    var packet = new DatagramPacket(buffer, buffer.length);
                    playerSocket.receive(packet);
                    var msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.contains("\"close\"")) {
                        closeReceived.countDown();
                    }
                    if (!msg.contains("frame advance")) {
                        var ack = "{}".getBytes(StandardCharsets.UTF_8);
                        playerSocket.send(new DatagramPacket(ack, ack.length,
                                packet.getAddress(), packet.getPort()));
                    }
                }
            }
            catch (Exception e) {
                // Socket closed; test is over
            }
        }, "fake-sharktopoda");
        player.setDaemon(true);
        player.start();

        try {
            var media = new Media();
            media.setVideoReferenceUuid(UUID.randomUUID());
            media.setUri(URI.create("http://localhost/test.mp4"));

            var factory = new MediaControlsFactoryImpl();
            var mediaPlayer = factory.open(media, remotePort, localPort).get(30, TimeUnit.SECONDS);

            // Occupy the serialized command subject: this send blocks ~1s waiting for an
            // ack the fake player deliberately withholds
            var inFlight = new Thread(() ->
                    mediaPlayer.getVideoIO().send(new FrameAdvanceCmd(media.getVideoReferenceUuid())));
            inFlight.setDaemon(true);
            inFlight.start();
            Thread.sleep(150);

            mediaPlayer.close();

            assertTrue(closeReceived.await(10, TimeUnit.SECONDS),
                    "The CloseCmd never reached the video player");
        }
        finally {
            playerSocket.close();
        }
    }
}
