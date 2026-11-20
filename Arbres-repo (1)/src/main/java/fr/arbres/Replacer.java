package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Remplace les arbres vanilla d'une zone par les arbres stylés (sans jamais toucher à ses propres arbres). */
final class Replacer {
    private Replacer() {}

    /** Probabilité qu'un arbre pâle reconverti reçoive un coeur de creaking (s'il en avait un, il en garde toujours un). */
    static volatile double heartChance = 0.45;

    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    static int replaceTrees(Canvas cv, int x0, int z0, int x1, int z1, long seed, double scale) {
        Set<TreeGen.P> done = new HashSet<>();
        BlockData air = Material.AIR.createBlockData();
        int count = 0;
        int yTop = cv.minY(), yBot = Integer.MAX_VALUE;
        boolean sawTree = false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int y = cv.topY(x, z);
                Material m = null;
                while (y > cv.minY()) {
                    m = cv.type(x, y, z);
                    if (m == null) break;
                    if (Blocks.leaf(m) || Blocks.log(m)) {
                        sawTree = true;
                        if (y > yTop) yTop = y;
                    }
                    // neige, herbe, fleurs... : on descend à travers (la neige sur les feuilles bloquait avant)
                    if (m.isAir() || Blocks.leaf(m) || Blocks.log(m) || Blocks.replaceable(m)) {
                        y--;
                        continue;
                    }
                    break;
                }
                if (m != null && y < yBot) yBot = y;
                // terre racinée = arbre déjà converti par ce plugin (ou azalée) : on n'y touche pas
                if (m == null || !Blocks.ground(m) || m == Material.ROOTED_DIRT) continue;
                Material above = cv.type(x, y + 1, z);
                if (above == null || !Blocks.log(above)) continue;
                // le pied d'un vrai arbre est un tronc debout (les troncs couchés / racines sont ignorés)
                BlockData bd = cv.data(x, y + 1, z);
                if (!(bd instanceof Orientable o) || o.getAxis() != Axis.Y) continue;

                TreeGen.P base = new TreeGen.P(x, y + 1, z);
                if (done.contains(base)) continue;
                done.add(base);

                Set<TreeGen.P> logs = collectLogs(cv, base, done);
                if (logs == null) continue;
                done.addAll(logs);

                // espèce = bois majoritaire
                Map<Material, Integer> tally = new EnumMap<>(Material.class);
                for (TreeGen.P p : logs) {
                    Material t = cv.type(p.x(), p.y(), p.z());
                    if (t != null && Blocks.log(t)) tally.merge(t, 1, Integer::sum);
                }
                Material best = null;
                int bestN = 0;
                for (Map.Entry<Material, Integer> e : tally.entrySet()) {
                    if (e.getValue() > bestN) {
                        best = e.getKey();
                        bestN = e.getValue();
                    }
                }
                TreeGen.Species sp = best == null ? null : TreeGen.Species.ofLog(best);
                if (sp == null) continue;
                boolean hadHeart = false;
                for (TreeGen.P p : logs) {
                    Material ht = cv.type(p.x(), p.y(), p.z());
                    if (ht != null && ht.name().equals("CREAKING_HEART")) {
                        hadHeart = true;
                        break;
                    }
                }

                // feuilles qui appartiennent à CET arbre : on suit la "distance" des feuilles depuis ses troncs
                Set<TreeGen.P> leaves = ownLeaves(cv, logs);
                if (leaves.size() < 6) { // repli : zone autour des troncs
                    leaves = new HashSet<>();
                    for (TreeGen.P p : logs) {
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dy = -2; dy <= 3; dy++) {
                                for (int dz = -3; dz <= 3; dz++) {
                                    TreeGen.P q = new TreeGen.P(p.x() + dx, p.y() + dy, p.z() + dz);
                                    Material t = cv.type(q.x(), q.y(), q.z());
                                    if (t != null && Blocks.leaf(t)) leaves.add(q);
                                }
                            }
                        }
                    }
                }
                if (leaves.size() < 10) continue; // pas de feuilles : ce n'est pas un arbre

                // épaisseur du tronc : nombre de blocs au pied
                int minY = Integer.MAX_VALUE;
                for (TreeGen.P p : logs) minY = Math.min(minY, p.y());
                int ox = Integer.MAX_VALUE, oz = Integer.MAX_VALUE, baseCount = 0;
                for (TreeGen.P p : logs) {
                    if (p.y() == minY) {
                        baseCount++;
                        ox = Math.min(ox, p.x());
                        oz = Math.min(oz, p.z());
                    }
                }
                int th = baseCount >= 3 ? 2 : 1;

                // on efface l'ancien arbre (troncs + ses feuilles + lianes accrochées)
                List<TreeGen.P> removed = new ArrayList<>(logs);
                removed.addAll(leaves);
                // restes de feuilles vanilla collées au tronc (les nôtres sont persistantes, on ne les touche pas)
                Set<TreeGen.P> seen = new HashSet<>(leaves);
                for (TreeGen.P p : logs) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                TreeGen.P q = new TreeGen.P(p.x() + dx, p.y() + dy, p.z() + dz);
                                if (seen.contains(q)) continue;
                                BlockData bd2 = cv.data(q.x(), q.y(), q.z());
                                if (bd2 instanceof Leaves l2 && !l2.isPersistent()) {
                                    seen.add(q);
                                    removed.add(q);
                                }
                            }
                        }
                    }
                }
                // la neige posée sur l'ancien arbre doit partir aussi, sinon elle reste en l'air
                for (TreeGen.P p : new ArrayList<>(removed)) {
                    if (cv.type(p.x(), p.y() + 1, p.z()) == Material.SNOW) removed.add(new TreeGen.P(p.x(), p.y() + 1, p.z()));
                }
                for (TreeGen.P p : removed) cv.set(p.x(), p.y(), p.z(), air);
                // lianes et cacao accrochés à l'ancien arbre : on enlève aussi toute la chaîne de lianes qui pend dessous,
                // sinon elle reste suspendue dans le vide
                for (TreeGen.P p : removed) {
                    for (int[] d : N6) {
                        int nx = p.x() + d[0], ny = p.y() + d[1], nz = p.z() + d[2];
                        Material t = cv.type(nx, ny, nz);
                        if (t == null) continue;
                        String n = t.name();
                        if (n.equals("COCOA") || n.equals("CREAKING_HEART")) {
                            cv.set(nx, ny, nz, air);
                        } else if (n.equals("VINE") || n.equals("PALE_HANGING_MOSS")) {
                            int yy = ny;
                            while (true) {
                                Material v = cv.type(nx, yy, nz);
                                if (v == null || !v.name().equals(n)) break;
                                cv.set(nx, yy, nz, air);
                                yy--;
                            }
                        }
                    }
                }

                Random rnd = new Random(TreeGen.mix(seed ^ (ox * 341873128712L) ^ (oz * 132897987541L) ^ minY));
                TreeGen.Shape shape = TreeGen.build(sp, rnd, ox, minY, oz, th, scale, rnd.nextLong());
                if (sp == TreeGen.Species.PALE_OAK && (hadHeart || rnd.nextDouble() < heartChance)) {
                    shape.heart = TreeGen.pickHeart(shape, rnd);
                }
                TreeGen.apply(shape, sp, cv, snowy(cv.biome(ox, minY, oz)));
                done.addAll(shape.logs.keySet());
                done.addAll(shape.roots.keySet());
                count++;
            }
        }
        // nettoyage : feuilles orphelines, lianes en l'air, troncs flottants...
        if (sawTree && yBot != Integer.MAX_VALUE) Cleaner.sweep(cv, x0, z0, x1, z1, yBot - 2, yTop + 2);
        return count;
    }

    static boolean snowy(String biome) {
        return biome.contains("snowy") || biome.contains("frozen") || biome.contains("grove")
                || biome.contains("ice_spikes") || biome.contains("jagged");
    }

    /** Feuilles dont la "distance" (comme calculée par le jeu) mène aux troncs de cet arbre. */
    private static Set<TreeGen.P> ownLeaves(Canvas cv, Set<TreeGen.P> logs) {
        Set<TreeGen.P> out = new HashSet<>();
        List<TreeGen.P> frontier = new ArrayList<>(logs);
        for (int depth = 0; depth < 7; depth++) {
            List<TreeGen.P> next = new ArrayList<>();
            for (TreeGen.P p : frontier) {
                for (int[] d : N6) {
                    TreeGen.P nb = new TreeGen.P(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                    if (out.contains(nb)) continue;
                    BlockData bd = cv.data(nb.x(), nb.y(), nb.z());
                    if (bd instanceof Leaves l && l.getDistance() == depth + 1) {
                        out.add(nb);
                        next.add(nb);
                    }
                }
            }
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return out;
    }

    /** Troncs connectés au pied de l'arbre. Retourne null si ça ressemble à une construction ou à un arbre déjà converti. */
    private static Set<TreeGen.P> collectLogs(Canvas cv, TreeGen.P base, Set<TreeGen.P> done) {
        Set<TreeGen.P> logs = new LinkedHashSet<>();
        ArrayDeque<TreeGen.P> q = new ArrayDeque<>();
        logs.add(base);
        q.add(base);
        while (!q.isEmpty()) {
            TreeGen.P c = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        TreeGen.P nb = new TreeGen.P(c.x() + dx, c.y() + dy, c.z() + dz);
                        if (Math.abs(nb.x() - base.x()) > 26 || Math.abs(nb.z() - base.z()) > 26
                                || nb.y() < base.y() - 1 || nb.y() > base.y() + 120) continue;
                        Material t = cv.type(nb.x(), nb.y(), nb.z());
                        if (t != null && (Blocks.log(t) || t.name().equals("CREAKING_HEART")) && logs.add(nb)) {
                            // touche un arbre déjà converti (terre racinée dessous, ou posé pendant cette passe) : on laisse tout tel quel
                            if (cv.type(nb.x(), nb.y() - 1, nb.z()) == Material.ROOTED_DIRT || done.contains(nb)) return null;
                            if (logs.size() > 800) return null; // trop gros : probablement une construction
                            q.add(nb);
                        }
                    }
                }
            }
        }
        return logs;
    }
}
