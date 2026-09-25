// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Divinakra
package local.pokewilds.bugfix;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime support for the "floors" fix. Public because patched game classes call it.
 *
 * PokeWilds keeps every monster (overworld and every floor of every interior) in ONE map, PkmnMap.pokemon,
 * keyed only by position, although each floor has its own tile map. Monsters on different floors that share
 * coordinates therefore block, scan, overwrite and hide each other.
 *
 * This replaces that map with one real map per floor (a {@link FloorMap} for each tile map, all owned by one
 * {@link Registry}). The game's own field PkmnMap.pokemon is always switched to the map of the floor the player
 * is on, so all player, drawing and UI code runs unchanged on a normal single-floor map. Code that belongs to a
 * monster is routed to that monster's own floor ({@link #viewOwner}). A monster is registered in exactly one
 * place; putting it somewhere new removes its previous registration.
 *
 * Everything fails safe: if the game does not look as expected, the original map is used.
 */
public final class Hooks {
    private Hooks() {}

    // ------------------------------------------------------------------ reflection helpers

    static Field open(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static final boolean DEBUG = "true".equalsIgnoreCase(System.getProperty("bugfix.debug"));
    private static volatile boolean disabled;

    private static Class<?> pokemonClass;
    private static Field mapTilesField, interiorIndexField, nicknameField, positionField;

    private static synchronized boolean bindPokemon(Class<?> c) {
        if (pokemonClass == c) return mapTilesField != null;
        try {
            mapTilesField = open(c, "mapTiles");
            interiorIndexField = open(c, "interiorIndex");
        } catch (Throwable t) {
            mapTilesField = null;
            interiorIndexField = null;
            System.err.println("[bugfix] floors: cannot read Pokemon.mapTiles (" + t + "), using original behavior");
            disabled = true;
        }
        pokemonClass = c;
        return mapTilesField != null;
    }

    /** The floor (tile map) a monster lives on, or null if unknown. */
    static Object floorOf(Object pokemon) {
        if (pokemon == null) return null;
        if (pokemonClass != pokemon.getClass() && !bindPokemon(pokemon.getClass())) return null;
        try { return mapTilesField.get(pokemon); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ the per-floor maps

    /** All per-floor maps of one PkmnMap. */
    static final class Registry {
        final Object pkmnMap;
        final IdentityHashMap<Object, FloorMap> byFloor = new IdentityHashMap<Object, FloorMap>();
        /** monsters -> {FloorMap, key}: where each monster is registered (at most one place). */
        final IdentityHashMap<Object, Object[]> where = new IdentityHashMap<Object, Object[]>();
        final Field fPokemon, fTiles, fOverworld, fRefreshDrawn, fRefreshTiles;
        int nextSeq;
        long mod;                       // bumped on every change, so cached save views can be reused
        SaveView cachedSave;
        long cachedSaveMod = -1;

        Registry(Object pkmnMap) throws NoSuchFieldException {
            this.pkmnMap = pkmnMap;
            Class<?> c = pkmnMap.getClass();
            fPokemon = open(c, "pokemon");
            fTiles = open(c, "tiles");
            fOverworld = open(c, "overworldTiles");
            fRefreshDrawn = open(c, "refreshOnscreenPokemon");
            fRefreshTiles = open(c, "refreshCache");
        }

        Object overworld() { try { return fOverworld.get(pkmnMap); } catch (Throwable t) { return null; } }
        Object currentTiles() { try { return fTiles.get(pkmnMap); } catch (Throwable t) { return null; } }

        FloorMap floorMap(Object floor) {
            if (floor == null) floor = currentTiles();
            FloorMap m = byFloor.get(floor);
            if (m == null) { m = new FloorMap(this, floor); byFloor.put(floor, m); }
            return m;
        }

        /** Points the game's PkmnMap.pokemon at the map of the floor the player is on. True if it changed. */
        boolean syncField() {
            try {
                FloorMap cur = floorMap(currentTiles());
                if (fPokemon.get(pkmnMap) == cur) return false;
                fPokemon.set(pkmnMap, cur);
                return true;
            } catch (Throwable t) { return false; }
        }

        void requestRedraw() {
            try { fRefreshDrawn.setBoolean(pkmnMap, true); fRefreshTiles.setBoolean(pkmnMap, true); } catch (Throwable t) { /* ignore */ }
        }

        void clearAll() {
            for (FloorMap m : byFloor.values()) m.rawClear();
            where.clear();
            mod++;
        }

        List<FloorMap> inOrder() {
            ArrayList<FloorMap> l = new ArrayList<FloorMap>(byFloor.values());
            Collections.sort(l, new Comparator<FloorMap>() { public int compare(FloorMap a, FloorMap b) { return a.seq - b.seq; } });
            return l;
        }
    }

    /** The registrations of one floor. Behaves like the game's original HashMap, plus the rules described above. */
    static final class FloorMap extends HashMap<Object, Object> {
        private static final long serialVersionUID = 1L;
        final transient Registry reg;
        final transient Object floor;
        final int seq;

        FloorMap(Registry reg, Object floor) { this.reg = reg; this.floor = floor; this.seq = reg.nextSeq++; }

        void rawClear() { super.clear(); }
        Object rawRemove(Object k) { return super.remove(k); }

        @Override public Object put(Object k, Object v) {
            if (v == null) return super.put(k, v);
            Object vf = floorOf(v);
            if (vf != null && vf != floor) return reg.floorMap(vf).put(k, v);        // a monster belongs on its own floor
            Object[] w = reg.where.get(v);
            if (w != null && (w[0] != this || !w[1].equals(k))) {                    // it was registered somewhere else: drop that
                FloorMap wm = (FloorMap) w[0];
                if (wm.get(w[1]) == v) wm.rawRemove(w[1]);
                if (DEBUG) log("TRAIL removed: " + describe(v) + " was also registered at " + w[1] + " (now " + k + ")");
            }
            Object prev = super.put(k, v);
            if (prev != null && prev != v) reg.where.remove(prev);                   // replaced a same-floor entry, like the original
            reg.where.put(v, new Object[] {this, k});
            reg.mod++;
            return prev;
        }

        @Override public void putAll(Map<?, ?> m) { for (Map.Entry<?, ?> e : m.entrySet()) put(e.getKey(), e.getValue()); }

        @Override public Object remove(Object k) {
            Object r = super.remove(k);
            if (r != null) {
                Object[] w = reg.where.get(r);
                if (w != null && w[0] == this) reg.where.remove(r);
                reg.mod++;
            }
            return r;
        }

        /** The game only clears the map when it (re)loads the whole world, so this clears every floor. */
        @Override public void clear() { reg.clearAll(); }
    }

    // ------------------------------------------------------------------ entry points called by patched game code

    /** Replaces the initialisation of PkmnMap.pokemon in PkmnMap's constructor. */
    public static Map newPokemonMap(Map original, Object pkmnMap) {
        if (disabled) return original;
        try {
            Registry r = new Registry(pkmnMap);
            FloorMap m = new FloorMap(r, r.overworld());
            r.byFloor.put(r.overworld(), m);
            return m;
        } catch (Throwable t) {
            System.err.println("[bugfix] floors: could not set up per-floor maps (" + t + "), using original behavior");
            disabled = true;
            return original;
        }
    }

    private static Registry registryOf(Object pkmnMap) {
        try {
            Object m = open(pkmnMap.getClass(), "pokemon").get(pkmnMap);
            return m instanceof FloorMap ? ((FloorMap) m).reg : null;
        } catch (Throwable t) { return null; }
    }

    /** Called right after PkmnMap.tiles is assigned (the player changed floor, or a map was loaded). */
    public static void tilesChanged(Object pkmnMap) {
        if (disabled || pkmnMap == null) return;
        Registry r = registryOf(pkmnMap);
        if (r == null) return;
        if (r.syncField()) {
            r.requestRedraw();
            if (DEBUG) { log("player is now on " + label(r, r.currentTiles()) + " (" + ((FloorMap) r.floorMap(r.currentTiles())).size() + " registered Pokemon here)"); audit(r); }
        }
    }

    /** For code that belongs to a monster: the map of that monster's own floor. {@code m} is what the code read from the field. */
    public static Map viewOwner(Map m, Object owner) {
        if (!(m instanceof FloorMap) || owner == null) return m;
        Object f = floorOf(owner);
        if (f == null) return m;
        FloorMap fm = (FloorMap) m;
        return fm.floor == f ? fm : fm.reg.floorMap(f);
    }

    /** For world-level code (day/night spawning, world generation): always the overworld. */
    public static Map viewOverworld(Map m) {
        if (!(m instanceof FloorMap)) return m;
        FloorMap fm = (FloorMap) m;
        Object ow = fm.reg.overworld();
        return ow == null ? m : fm.reg.floorMap(ow);
    }

    /** For world regeneration: clear() and values() cover every floor; everything else is the overworld. */
    public static Map viewAllFloors(Map m) {
        if (!(m instanceof FloorMap)) return m;
        return new AllFloors(((FloorMap) m).reg);
    }

    static final class AllFloors extends AbstractMap<Object, Object> {
        final Registry reg;
        AllFloors(Registry reg) { this.reg = reg; }
        private Map<Object, Object> ow() { return reg.floorMap(reg.overworld()); }
        @Override public Object get(Object k) { return ow().get(k); }
        @Override public boolean containsKey(Object k) { return ow().containsKey(k); }
        @Override public Object put(Object k, Object v) { return ow().put(k, v); }
        @Override public Object remove(Object k) { return ow().remove(k); }
        @Override public void clear() { reg.clearAll(); }
        @Override public Set<Map.Entry<Object, Object>> entrySet() { return ow().entrySet(); }
        @Override public Collection<Object> values() {
            ArrayList<Object> all = new ArrayList<Object>();
            for (FloorMap m : reg.inOrder()) all.addAll(m.values());
            return all;
        }
    }

    /**
     * Replaces the read of Pokemon.interiorIndex when saving a Pokemon. The field is only kept up to date for
     * monsters the player dropped, so e.g. an egg laid on floor 5 was saved as floor 100 (the first floor).
     * The floor is derived from the tile map the monster is really on.
     */
    public static int floorIndex(Object pokemon) {
        int stored = 0;
        try {
            if (pokemon == null) return 0;
            if (pokemonClass != pokemon.getClass() && !bindPokemon(pokemon.getClass())) return 0;
            stored = interiorIndexField.getInt(pokemon);
            Object tiles = mapTilesField.get(pokemon);
            List<?> layers = env.interiorLayers();
            if (tiles == null || layers == null) return stored;
            for (int i = 0; i < layers.size(); i++) if (layers.get(i) == tiles) return i;
        } catch (Throwable t) { /* fall through */ }
        return stored;
    }

    // ------------------------------------------------------------------ saving

    /**
     * For the save code: every monster exactly once, under a unique position. The save file is keyed by position
     * only, but monsters on different floors share coordinates, so a monster whose position is already taken is
     * written to the nearest free tile of its own floor instead (walkable if possible). Never drops a Pokemon.
     */
    public static Map viewSave(Map m) {
        if (!(m instanceof FloorMap)) return m;
        try {
            Registry r = ((FloorMap) m).reg;
            if (r.cachedSave == null || r.cachedSaveMod != r.mod) { r.cachedSave = new SaveView(r); r.cachedSaveMod = r.mod; }
            return r.cachedSave;
        } catch (Throwable t) {
            System.err.println("[bugfix] floors: save view failed (" + t + "), saving the current floor only");
            return m;
        }
    }

    static final class SaveView extends AbstractMap<Object, Object> {
        private final LinkedHashMap<Object, Object> out = new LinkedHashMap<Object, Object>();

        SaveView(Registry reg) throws Exception {
            List<FloorMap> maps = reg.inOrder();
            for (FloorMap fm : maps) {
                for (Map.Entry<Object, Object> e : fm.entrySet()) {
                    Object key = e.getKey(), p = e.getValue();
                    if (!out.containsKey(key)) { out.put(key, p); continue; }
                    Object moved = nearestFree(key, fm.floor);
                    if (moved != null) out.put(moved, p);
                    else System.err.println("[bugfix] could not find a free tile to save " + describe(p) + " - it will not be saved");
                }
            }
        }

        private Object nearestFree(Object key, Object floor) throws Exception {
            Class<?> vc = key.getClass();
            Field fx = vc.getField("x"), fy = vc.getField("y");
            float x = fx.getFloat(key), y = fy.getFloat(key);
            java.lang.reflect.Constructor<?> ctor = vc.getConstructor(float.class, float.class);
            Field solid = null;
            boolean haveTiles = floor instanceof Map && !((Map) floor).isEmpty();
            Object firstSolidTile = null, firstVoid = null;
            for (int r = 1; r <= 16; r++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                        Object cand = ctor.newInstance(x + dx * 16, y + dy * 16);
                        if (out.containsKey(cand)) continue;
                        if (!haveTiles) return cand;
                        Object tile = ((Map) floor).get(cand);
                        if (tile == null) { if (firstVoid == null) firstVoid = cand; continue; }
                        if (solid == null) solid = open(tile.getClass(), "isSolid");
                        if (!solid.getBoolean(tile)) return cand;
                        if (firstSolidTile == null) firstSolidTile = cand;
                    }
                }
                if (r >= 8 && firstSolidTile != null) break;
            }
            return firstSolidTile != null ? firstSolidTile : firstVoid;
        }

        @Override public Set<Map.Entry<Object, Object>> entrySet() { return out.entrySet(); }
        @Override public Object get(Object key) { return out.get(key); }
        @Override public boolean containsKey(Object key) { return out.containsKey(key); }
        @Override public int size() { return out.size(); }
        @Override public Set<Object> keySet() { return out.keySet(); }
        @Override public Collection<Object> values() { return out.values(); }
    }

    // ------------------------------------------------------------------ Game state access (floor numbers, diagnostics)

    /** Access to game state used for floor numbers; replaced in tests. */
    public interface Env {
        /** game.map.interiorTiles, or null. */
        List<?> interiorLayers();
    }

    static final class GameEnv implements Env {
        private Field staticGame, map, interiorTiles;
        private boolean resolved, broken;

        Object mapObject() throws Exception {
            if (!resolved) {
                resolved = true;
                ClassLoader cl = Hooks.class.getClassLoader();
                Class<?> game = Class.forName("com.pkmngen.game.Game", false, cl);
                Class<?> pkmnMap = Class.forName("com.pkmngen.game.PkmnMap", false, cl);
                staticGame = open(game, "staticGame");
                map = open(game, "map");
                interiorTiles = open(pkmnMap, "interiorTiles");
            }
            Object g = staticGame.get(null);
            return g == null ? null : map.get(g);
        }

        @Override public List<?> interiorLayers() {
            if (broken) return null;
            try {
                Object m = mapObject();
                Object l = m == null ? null : interiorTiles.get(m);
                return l instanceof List ? (List<?>) l : null;
            } catch (Throwable t) { broken = true; return null; }
        }
    }

    static Env env = new GameEnv();

    // ------------------------------------------------------------------ diagnostics (-Dbugfix.debug=true)

    private static final java.util.HashSet<String> LOGGED = new java.util.HashSet<String>();
    private static long lastClear;

    static void log(String s) {
        long now = System.currentTimeMillis();
        if (now - lastClear > 20000) { LOGGED.clear(); lastClear = now; }
        if (LOGGED.size() < 5000 && LOGGED.add(s)) System.err.println("[bugfix:debug] " + s);
    }

    static String label(Registry r, Object floor) {
        if (floor == null) return "unknown";
        if (floor == r.overworld()) return "overworld";
        List<?> layers = env.interiorLayers();
        if (layers != null) for (int i = 0; i < layers.size(); i++) if (layers.get(i) == floor) return "interior#" + i;
        return "other";
    }

    static String describe(Object p) {
        if (p == null) return "null";
        try {
            if (nicknameField == null) { nicknameField = open(p.getClass(), "nickname"); positionField = open(p.getClass(), "position"); }
            return nicknameField.get(p) + "@" + positionField.get(p);
        } catch (Throwable t) { return p.getClass().getSimpleName(); }
    }

    /** Lists registrations of the current floor whose monster is not near its registered tile (possible phantoms). */
    static void audit(Registry r) {
        try {
            FloorMap cur = r.floorMap(r.currentTiles());
            if (positionField == null) return;
            for (Map.Entry<Object, Object> e : cur.entrySet()) {
                Object k = e.getKey(), p = e.getValue();
                Object pos = positionField.get(p);
                double d = Math.max(Math.abs(((Number) pos.getClass().getField("x").get(pos)).doubleValue() - ((Number) k.getClass().getField("x").get(k)).doubleValue()),
                        Math.abs(((Number) pos.getClass().getField("y").get(pos)).doubleValue() - ((Number) k.getClass().getField("y").get(k)).doubleValue()));
                if (d > 32) log("AUDIT: " + describe(p) + " is registered at " + k + " which is " + (int) d + "px from where it stands");
            }
        } catch (Throwable t) { /* diagnostics only */ }
    }
}
