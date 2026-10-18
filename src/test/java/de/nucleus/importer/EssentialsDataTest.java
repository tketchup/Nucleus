package de.nucleus.importer;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialsDataTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID FRIEND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    /** Beispiel im Format einer EssentialsX-Datei userdata/<uuid>.yml (selbst geschrieben). */
    private static final String USER = """
            money: '1234.56'
            homes:
              home:
                world: 6f2c3a1e-0000-0000-0000-000000000001
                world-name: world
                x: 10.5
                y: 64.0
                z: -3.5
                yaw: 90.0
                pitch: 0.0
              meine basis!:
                world: 6f2c3a1e-0000-0000-0000-000000000001
                world-name: world
                x: 100
                y: 70
                z: 100
            nickname: '&cRoter&lName'
            lastlocation:
              world: 6f2c3a1e-0000-0000-0000-000000000002
              world-name: world_nether
              x: 1.0
              y: 2.0
              z: 3.0
              yaw: 0.0
              pitch: 0.0
            ignore:
            - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
            - kein-uuid
            muted: true
            mute-reason: Spam
            socialspy: true
            teleportenabled: false
            last-account-name: Tester
            timestamps:
              mute: 1900000000000
              logout: 1700000000000
            """;

    private static Map<?, ?> yaml(String text) {
        return new Yaml().load(text);
    }

    @Test
    void readsAllRelevantUserFields() {
        EssentialsData.User u = EssentialsData.parseUser(ID, yaml(USER));
        assertEquals("Tester", u.name());
        assertEquals(new BigDecimal("1234.56"), u.money());
        assertEquals(2, u.homes().size());
        EssentialsData.Loc home = u.homes().get("home");
        assertEquals("world", home.worldName());
        assertEquals(10.5, home.x());
        assertEquals(90f, home.yaw());
        assertEquals("&cRoter&lName", u.nickname());
        assertEquals("world_nether", u.lastLocation().worldName());
        assertEquals(1, u.ignored().size(), "ungültige UUIDs werden übersprungen");
        assertEquals(FRIEND, u.ignored().getFirst());
        assertTrue(u.muted());
        assertEquals(1_900_000_000_000L, u.muteUntil());
        assertEquals("Spam", u.muteReason());
        assertTrue(u.socialSpy());
        assertFalse(u.teleportEnabled());
        assertEquals(1_700_000_000_000L, u.lastLogout());
    }

    @Test
    void emptyUserFileGivesDefaults() {
        EssentialsData.User u = EssentialsData.parseUser(ID, Map.of());
        assertNull(u.money());
        assertTrue(u.homes().isEmpty());
        assertFalse(u.muted());
        assertTrue(u.teleportEnabled(), "Standard bei EssentialsX: Teleport-Anfragen an");
    }

    @Test
    void moneyAsNumberOrTextWithoutDoubleErrors() {
        assertEquals(new BigDecimal("0.1"), EssentialsData.parseUser(ID, yaml("money: '0.1'")).money());
        assertEquals(new BigDecimal("50"), EssentialsData.parseUser(ID, yaml("money: 50")).money());
        assertEquals(BigDecimal.ZERO, EssentialsData.parseUser(ID, yaml("money: '-5'")).money(), "nie negativ");
        assertNull(EssentialsData.parseUser(ID, yaml("money: 'viel'")).money());
    }

    @Test
    void readsWarpsJailsAndSpawns() {
        var warp = EssentialsData.parseWarp("shop", yaml("""
                world: abc
                world-name: world
                x: 1
                y: 2
                z: 3
                name: Shop
                lastowner: 11111111-2222-3333-4444-555555555555
                """));
        assertEquals("Shop", warp.getKey());
        assertEquals(1.0, warp.getValue().x());
        assertNull(EssentialsData.parseWarp("kaputt", yaml("name: ohneort")));

        var jails = EssentialsData.parseLocationSection(yaml("""
                jails:
                  knast: {world: w, world-name: world, x: 5, y: 6, z: 7}
                  leer: {}
                """).get("jails"));
        assertEquals(1, jails.size());
        assertEquals(7.0, jails.get("knast").z());
    }

    @Test
    void cleansNamesForNucleus() {
        assertEquals("meine_basis_", EssentialsData.cleanName("meine basis!"));
        assertEquals("home", EssentialsData.cleanName("home"));
        assertNull(EssentialsData.cleanName("!!!"));
        assertEquals(32, EssentialsData.cleanName("a".repeat(50)).length());
    }
}
