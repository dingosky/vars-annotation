package org.mbari.vars.annotation.test.ui.mediaplayers.sharktopoda2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mbari.vars.annosaurus.sdk.r1.models.Annotation;
import org.mbari.vars.annosaurus.sdk.r1.models.Association;
import org.mbari.vars.annosaurus.sdk.r1.models.BoundingBox;
import org.mbari.vars.annotation.services.Services;
import org.mbari.vars.annotation.ui.Initializer;
import org.mbari.vars.annotation.ui.UIToolBox;
import org.mbari.vars.annotation.ui.mediaplayers.sharktopoda2.LocalizedAnnotation;
import org.mbari.vars.vampiresquid.sdk.r1.MediaService;
import org.mbari.vars.vampiresquid.sdk.r1.models.Media;
import org.mbari.vcr4j.VideoIndex;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalizedAnnotation.toLocalization must check whether an annotation from a different
 * video reference belongs to the current media. That check requires a media-service
 * lookup, which runs on latency-sensitive threads (event bus, often during a full
 * localization reload). The lookup — including a failing one — must be cached per
 * videoReferenceUuid, not repeated for every localization.
 */
public class LocalizedAnnotationMediaCacheTest {

    private UIToolBox toolBox;
    private Services originalServices;
    private Media currentMedia;
    private final AtomicInteger lookupCount = new AtomicInteger();

    @BeforeEach
    public void setup() {
        toolBox = Initializer.getToolBox();
        originalServices = toolBox.getServices();

        currentMedia = new Media();
        currentMedia.setVideoReferenceUuid(UUID.randomUUID());
        currentMedia.setStartTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        currentMedia.setWidth(1920);
        currentMedia.setHeight(1080);
        toolBox.getData().setMedia(currentMedia);

        lookupCount.set(0);
    }

    @AfterEach
    public void cleanup() {
        toolBox.setServices(originalServices);
        toolBox.getData().setMedia(null);
    }

    private void installMediaService(Supplier<CompletableFuture<Media>> findByUuidResult) {
        var mediaService = (MediaService) Proxy.newProxyInstance(
                MediaService.class.getClassLoader(),
                new Class<?>[]{MediaService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUuid" -> {
                        lookupCount.incrementAndGet();
                        yield findByUuidResult.get();
                    }
                    case "toString" -> "CountingMediaService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        toolBox.setServices(new Services(null, null, null, mediaService, null, null, null));
    }

    private LocalizedAnnotation localizedAnnotationFromOtherVideoReference() {
        var association = new Association(BoundingBox.LINK_NAME,
                Association.VALUE_SELF,
                """
                        {"x": 10, "y": 20, "width": 30, "height": 40}""",
                "application/json",
                UUID.randomUUID());
        var annotation = new Annotation("Nanomia bijuga", "brian",
                new VideoIndex(Duration.ofSeconds(1)),
                UUID.randomUUID()); // a different video reference than the current media
        annotation.setObservationUuid(UUID.randomUUID());
        annotation.setAssociations(List.of(association));
        return new LocalizedAnnotation(annotation, association);
    }

    @Test
    public void compatibleMediaIsLookedUpOnlyOnce() {
        var localizedAnnotation = localizedAnnotationFromOtherVideoReference();

        // Same recording (start timestamp) and dimensions as the current media
        var annotationMedia = new Media();
        annotationMedia.setVideoReferenceUuid(localizedAnnotation.annotation().getVideoReferenceUuid());
        annotationMedia.setStartTimestamp(currentMedia.getStartTimestamp());
        annotationMedia.setWidth(currentMedia.getWidth());
        annotationMedia.setHeight(currentMedia.getHeight());
        installMediaService(() -> CompletableFuture.completedFuture(annotationMedia));

        for (int i = 0; i < 5; i++) {
            assertTrue(localizedAnnotation.toLocalization(toolBox).isPresent(),
                    "The compatible annotation should convert to a localization");
        }
        assertEquals(1, lookupCount.get(),
                "The media lookup must be cached, not repeated for every localization");
    }

    @Test
    public void failedMediaLookupIsNotRetriedForEveryLocalization() {
        var localizedAnnotation = localizedAnnotationFromOtherVideoReference();
        installMediaService(() -> CompletableFuture.failedFuture(new RuntimeException("Vampire Squid is down")));

        for (int i = 0; i < 3; i++) {
            assertTrue(localizedAnnotation.toLocalization(toolBox).isEmpty(),
                    "An annotation whose media can't be resolved should not convert");
        }
        assertEquals(1, lookupCount.get(),
                "A failed media lookup must be cached too — retrying it for every localization "
                        + "stalls the caller once per localization when the service is down");
    }
}
