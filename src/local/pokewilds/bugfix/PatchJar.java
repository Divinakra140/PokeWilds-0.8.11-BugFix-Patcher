// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Divinakra
package local.pokewilds.bugfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import local.pokewilds.bugfix.asm.ClassReader;
import local.pokewilds.bugfix.asm.ClassVisitor;
import local.pokewilds.bugfix.asm.MethodVisitor;
import local.pokewilds.bugfix.asm.Opcodes;

/**
 * Offline patcher: writes a copy of the official PokeWilds 0.8.11 jar with all BugFix patches applied, so the game
 * needs no -javaagent. It applies exactly the same patches as the agent (BugFixAgent.transformClass), adds the
 * runtime support classes (Hooks), and refuses to write anything unless every patch applied as expected.
 *
 * Usage: java -jar bugfix.jar [--no-sprites] [--no-hooh] [--no-floors] [--no-eggs] input-pokewilds.jar output-pokewilds.jar
 */
public final class PatchJar {
    private PatchJar() {}

    public static void main(String[] args) throws Exception {
        boolean sprites = true, hooh = true, floors = true, eggs = true;
        List<String> files = new ArrayList<String>();
        for (String a : args) {
            if (a.equals("--no-sprites")) sprites = false;
            else if (a.equals("--no-hooh")) hooh = false;
            else if (a.equals("--no-floors")) floors = false;
            else if (a.equals("--no-eggs")) eggs = false;
            else files.add(a);
        }
        if (files.size() != 2) {
            System.err.println("Usage: java -jar bugfix.jar [--no-sprites] [--no-hooh] [--no-floors] [--no-eggs] <official pokewilds.jar> <output jar>");
            System.exit(1);
        }
        File in = new File(files.get(0)), out = new File(files.get(1));
        if (in.getCanonicalFile().equals(out.getCanonicalFile())) fail("input and output must be different files");
        try {
            patch(in, out, sprites, hooh, floors, eggs);
        } catch (PatchException e) {
            if (out.exists()) out.delete();
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    static final class PatchException extends Exception { PatchException(String m) { super(m); } }
    private static void fail(String m) { System.err.println("ERROR: " + m); System.exit(1); }

    static void patch(File inFile, File outFile, boolean sprites, boolean hooh, boolean floors, boolean eggs) throws Exception {
        List<String> applied = new ArrayList<String>();
        Map<String, Integer> hookCalls = new TreeMap<String, Integer>();
        int classes = 0, patchedClasses = 0;
        try (ZipFile zin = new ZipFile(inFile)) {
            // 1. only the exact, unpatched PokeWilds 0.8.11 class files are accepted
            if (zin.getEntry("META-INF/BUGFIX.txt") != null || zin.getEntry("local/pokewilds/bugfix/Hooks.class") != null)
                throw new PatchException("this jar is already patched by BugFix; run the patcher on the official, unmodified pokewilds.jar");
            for (String[] c : new String[][] {{BugFixAgent.PKMN_MAP, BugFixAgent.PKMN_MAP_SHA256}, {BugFixAgent.POKEMON, BugFixAgent.POKEMON_SHA256}}) {
                ZipEntry e = zin.getEntry(c[0] + ".class");
                if (e == null) throw new PatchException("this is not a PokeWilds jar (" + c[0] + ".class is missing)");
                if (!c[1].equals(sha256(read(zin, e)))) throw new PatchException(c[0] + ".class does not match PokeWilds 0.8.11. This patcher only supports the official 0.8.11 release.");
            }
            for (Enumeration<? extends ZipEntry> en = zin.entries(); en.hasMoreElements();) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")))
                    throw new PatchException("the jar is signed (" + n + "); patching would invalidate the signature");
            }
            // 2. copy every entry, patching the game classes
            System.out.println("The jar is valid PokeWilds 0.8.11. Patching now: the whole jar is rewritten, which can take a minute or two...");
            final int total = zin.size();
            int done = 0;
            File tmp = new File(outFile.getPath() + ".tmp");
            try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp))) {
                for (Enumeration<? extends ZipEntry> en = zin.entries(); en.hasMoreElements();) {
                    ZipEntry e = en.nextElement();
                    if (++done % 10000 == 0) System.out.println("  " + done + " of " + total + " files copied...");
                    ZipEntry ne = new ZipEntry(e.getName());
                    ne.setTime(e.getTime());
                    if (e.isDirectory()) { zout.putNextEntry(ne); zout.closeEntry(); continue; }
                    byte[] data = read(zin, e);
                    String n = e.getName();
                    if (n.startsWith("com/pkmngen/game/") && n.endsWith(".class")) {
                        classes++;
                        String cn = n.substring(0, n.length() - 6);
                        byte[] r = BugFixAgent.transformClass(cn, data, sprites, hooh, floors, eggs, applied);
                        if (r != null) {
                            data = r;
                            patchedClasses++;
                            for (Map.Entry<String, Integer> h : countHooks(r).entrySet()) {
                                Integer old = hookCalls.get(h.getKey());
                                hookCalls.put(h.getKey(), (old == null ? 0 : old) + h.getValue());
                            }
                        }
                    }
                    zout.putNextEntry(ne);
                    zout.write(data);
                    zout.closeEntry();
                }
                // 3. runtime support classes
                if (floors || eggs) {
                    int hooks = 0;
                    File self = new File(PatchJar.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    try (ZipFile zself = new ZipFile(self)) {
                        for (Enumeration<? extends ZipEntry> en = zself.entries(); en.hasMoreElements();) {
                            ZipEntry e = en.nextElement();
                            if (!e.getName().startsWith("local/pokewilds/bugfix/Hooks")) continue;
                            ZipEntry ne = new ZipEntry(e.getName());
                            ne.setTime(inFile.lastModified());
                            zout.putNextEntry(ne);
                            zout.write(read(zself, e));
                            zout.closeEntry();
                            hooks++;
                        }
                    }
                    if (hooks == 0) throw new PatchException("internal error: the runtime support classes were not found next to the patcher");
                }
                ZipEntry note = new ZipEntry("META-INF/BUGFIX.txt");
                note.setTime(inFile.lastModified());
                zout.putNextEntry(note);
                zout.write(describe(applied).getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
            // 4. every patch must have applied exactly as expected, or nothing is written
            check(applied, hookCalls, sprites, hooh, floors, eggs);
            if (outFile.exists() && !outFile.delete()) throw new PatchException("cannot replace " + outFile);
            if (!tmp.renameTo(outFile)) throw new PatchException("cannot write " + outFile);
        }
        System.out.println("Patched " + patchedClasses + " of " + classes + " game classes.");
        System.out.println("Wrote " + outFile + "  (sha256 " + sha256(java.nio.file.Files.readAllBytes(outFile.toPath())) + ")");
    }

    /** What a complete patch of PokeWilds 0.8.11 looks like. */
    static void check(List<String> applied, Map<String, Integer> hooks, boolean sprites, boolean hooh, boolean floors, boolean eggs) throws PatchException {
        List<String> problems = new ArrayList<String>();
        if (sprites) {
            need(applied, "sprites:" + BugFixAgent.UPPER, problems);
            need(applied, "sprites:" + BugFixAgent.LOWER, problems);
        }
        if (hooh) need(applied, "hooh:" + BugFixAgent.POKEMON, problems);
        if (eggs) { need(applied, "eggs:" + BugFixAgent.POKEMON_DATA_V07, problems); expect(hooks, "floorIndex", 1, problems); }
        else expect(hooks, "floorIndex", 0, problems);
        if (floors) {
            for (String c : new String[] {"PkmnMap", "DrawMiniMap", "EnterBuilding", "EscapeRope", "Game"}) need(applied, "floors-wiring:com/pkmngen/game/" + c, problems);
            expect(hooks, "newPokemonMap", 1, problems);
            expect(hooks, "tilesChanged", 11, problems);
            expect(hooks, "viewOwner", 40, problems);
            expect(hooks, "viewOverworld", 7, problems);
            expect(hooks, "viewAllFloors", 3, problems);
            expect(hooks, "viewSave", 4, problems);
        } else {
            for (String k : new String[] {"newPokemonMap", "tilesChanged", "viewOwner", "viewOverworld", "viewAllFloors", "viewSave"}) expect(hooks, k, 0, problems);
        }
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("the patches did not apply as expected, nothing was written:");
            for (String p : problems) sb.append("\n  - ").append(p);
            throw new PatchException(sb.toString());
        }
    }

    private static void need(List<String> applied, String label, List<String> problems) { if (!applied.contains(label)) problems.add("missing patch " + label); }
    private static void expect(Map<String, Integer> hooks, String name, int n, List<String> problems) {
        int got = hooks.containsKey(name) ? hooks.get(name) : 0;
        if (got != n) problems.add("Hooks." + name + ": " + got + " call(s) inserted, expected " + n);
    }

    private static String describe(List<String> applied) {
        StringBuilder sb = new StringBuilder("This jar was patched by BugFix for PokeWilds 0.8.11.\n"
                + "Patches: sprites (Cut/Ride/Build facing), hooh (Ho-Oh NullPointerException), floors (one Pokemon map per floor), eggs (egg floor when saving).\n\nApplied:\n");
        List<String> sorted = new ArrayList<String>(applied);
        java.util.Collections.sort(sorted);
        for (String s : sorted) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }

    private static Map<String, Integer> countHooks(byte[] b) {
        final Map<String, Integer> n = new TreeMap<String, Integer>();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String o, String f, String fd, boolean i) {
                        if (o.equals(BugFixAgent.HOOKS)) { Integer c = n.get(f); n.put(f, c == null ? 1 : c + 1); }
                    }
                };
            }
        }, 0);
        return n;
    }

    private static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
