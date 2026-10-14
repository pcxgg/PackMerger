package sh.pcx.packmerger.loader;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.PackOptions;
import sh.pcx.packmerger.api.RegisteredPack;
import sh.pcx.packmerger.api.events.PackBedrockConvertedEvent;
import sh.pcx.packmerger.api.events.PackMergeStartedEvent;
import sh.pcx.packmerger.api.events.PackMergedEvent;
import sh.pcx.packmerger.api.events.PackMergerCollectPacksEvent;
import sh.pcx.packmerger.api.events.PackSentToPlayerEvent;
import sh.pcx.packmerger.api.events.PackUploadFailedEvent;
import sh.pcx.packmerger.api.events.PackUploadedEvent;
import sh.pcx.packmerger.api.events.PackValidationFailedEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public API must be loaded by the server's plugin classloader, not the isolated
 * one, or other plugins would see different {@code PackMergerApi} and event classes
 * than the ones PackMerger implements and fires.
 */
class PackMergerLoaderRoutingTest {

    private static final List<Class<?>> API_TYPES = List.of(
            PackMergerApi.class, PackOptions.class, RegisteredPack.class,
            PackMergerCollectPacksEvent.class, PackMergeStartedEvent.class, PackMergedEvent.class,
            PackUploadedEvent.class, PackUploadFailedEvent.class, PackValidationFailedEvent.class,
            PackSentToPlayerEvent.class, PackBedrockConvertedEvent.class);

    @Test
    void apiAndItsSignatureTypesAreShared() {
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.api.PackMergerApi"));
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.api.events.PackUploadedEvent"));
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.merge.MergeProvenance"));
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.merge.MergeProvenance$FileRecord"));
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.merge.PackValidator$ValidationResult"));
    }

    @Test
    void internalsAndDependenciesStayIsolated() {
        assertTrue(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.PackMergerBootstrap"));
        assertTrue(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.merge.PackValidator"));
        assertTrue(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.discovery.ApiPackRegistry"));
        assertTrue(PackMergerLoader.loadsFromIsolated("io.minio.MinioClient"));
        assertFalse(PackMergerLoader.loadsFromIsolated("sh.pcx.packmerger.loader.PackMergerLoader"));
        assertFalse(PackMergerLoader.loadsFromIsolated("org.bukkit.Bukkit"));
        assertFalse(PackMergerLoader.loadsFromIsolated("com.google.gson.Gson"));
    }

    /** Every PackMerger type reachable from a public API signature must be shared too. */
    @Test
    void everyPackMergerTypeInApiSignaturesIsShared() {
        List<String> isolated = new ArrayList<>();
        for (Class<?> api : API_TYPES) {
            List<Class<?>> used = new ArrayList<>();
            for (Method m : api.getMethods()) collect(m, used);
            for (Constructor<?> c : api.getConstructors()) collect(c, used);
            if (api.isRecord()) for (RecordComponent rc : api.getRecordComponents()) used.add(rc.getType());
            for (Class<?> t : used) {
                while (t.isArray()) t = t.getComponentType();
                if (t.getName().startsWith("sh.pcx.packmerger.") && PackMergerLoader.loadsFromIsolated(t.getName())) {
                    isolated.add(api.getSimpleName() + " uses " + t.getName());
                }
            }
        }
        assertEquals(List.of(), isolated);
    }

    private static void collect(Executable e, List<Class<?>> out) {
        out.addAll(List.of(e.getParameterTypes()));
        if (e instanceof Method m) out.add(m.getReturnType());
    }
}
