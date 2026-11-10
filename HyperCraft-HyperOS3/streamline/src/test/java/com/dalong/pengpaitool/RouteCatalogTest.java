package com.dalong.pengpaitool;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class RouteCatalogTest {
    @Test public void everyFeatureHasAnUnambiguousParentAndHostDestination() {
        Set<String> destinations = new HashSet<>(), parents = new HashSet<>();
        for (RouteCatalog.Group group : RouteCatalog.groups()) {
            assertTrue(parents.add(group.getGroupId()));
            assertEquals(group.getGroupId(), group.getRoute());
            assertEquals(group.getRoute(), RouteCatalog.validRoute(group.getRoute()));
            assertFalse(RouteCatalog.entriesFor(group.getGroupId()).isEmpty());
            assertFalse(RouteCatalog.requiresRoot(group.getRoute()));
        }
        for (RouteCatalog.Entry entry : RouteCatalog.entries()) {
            assertTrue(destinations.add(entry.getRoute()));
            assertTrue(parents.contains(entry.getGroupId()));
            assertEquals(entry.getTitle(), RouteCatalog.title(entry.getRoute()));
            assertEquals(entry.getRoute(), RouteCatalog.validRoute(entry.getRoute()));
            assertSame(entry, RouteCatalog.entry(entry.getRoute()));
            assertEquals(entry.isRequiresRoot(), RouteCatalog.requiresRoot(entry.getRoute()));
        }
    }
    @Test public void externalRoutesCannotSupplyPrivateChargeSessionPaths() {
        assertEquals("home", RouteCatalog.validRoute(null));
        assertEquals("home", RouteCatalog.validRoute("../../charge"));
        assertEquals("home", RouteCatalog.validRoute("chargelog:/data/private"));
        assertTrue(RouteCatalog.requiresRoot("chargelog:internal-session"));
    }
    @Test public void onlyNonPrivilegedPagesCanMountBeforeAuthorization() {
        Set<String> publicPages = Set.of("mydevice", "adjump", "shortcuts", "about");
        for (RouteCatalog.Entry entry : RouteCatalog.entries())
            assertEquals(!publicPages.contains(entry.getRoute()), entry.isRequiresRoot());
        assertTrue(RouteCatalog.requiresRoot("ota"));
        assertTrue(RouteCatalog.requiresRoot("backup"));
        assertTrue(RouteCatalog.requiresRoot("wifi"));
    }
}
