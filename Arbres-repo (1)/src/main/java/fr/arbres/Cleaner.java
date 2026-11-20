package fr.arbres;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Nettoie les restes d'anciens arbres autour d'un chunk : feuilles qui n'ont plus de tronc (et qui se casseraient
 * toutes seules), lianes / mousses pendantes dans le vide, neige en l'air, troncs flottants.
 */
final class Cleaner {
    private Cleaner() {}

    static volatile boolean enabled = true;
    static volatile boolean orphanLogs = true;

    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int SWEEP = 8;  // on nettoie jusqu'à 8 blocs autour du chunk
    private static final int MARGIN = 7; // les feuilles tiennent jusqu'à 6 blocs d'un tronc : on regarde 7 blocs plus loin

    /** Retourne le nombre de blocs retirés. yLo..yHi = tranche verticale où il y a des arbres. */
    static int sweep(Canvas cv, int x0, int z0, int x1, int z1, int yLo, int yHi) {
        if (!enabled) return 0;
        int sx0 = x0 - SWEEP, sx1 = x1 + SWEEP, sz0 = z0 - SWEEP, sz1 = z1 + SWEEP;
        int sy0 = Math.max(cv.minY(), yLo), sy1 = Math.min(cv.maxY() - 1, yHi);
        if (sy1 < sy0) return 0;
        int bx0 = sx0 - MARGIN, bx1 = sx1 + MARGIN, bz0 = sz0 - MARGIN, bz1 = sz1 + MARGIN;
        int by0 = Math.max(cv.minY(), sy0 - MARGIN), by1 = Math.min(cv.maxY() - 1, sy1 + MARGIN);
        int ym = (by0 + by1) / 2;
        // si un chunk voisin n'est pas chargé, on ne risque rien : on ne nettoie pas
        if (cv.type(bx0, ym, bz0) == null || cv.type(bx1, ym, bz0) == null
                || cv.type(bx0, ym, bz1) == null || cv.type(bx1, ym, bz1) == null) return 0;

        int removed = removeLooseLeaves(cv, sx0, sx1, sz0, sz1, sy0, sy1, bx0, bx1, bz0, bz1, by0, by1);
        removed += removeHanging(cv, sx0, sx1, sz0, sz1, sy0, sy1);
        if (orphanLogs) removed += removeOrphanLogs(cv, sx0, sx1, sz0, sz1, sy0, sy1);
        return removed;
    }

    // ------------------------------------------------------------ feuilles sans tronc

    private static int removeLooseLeaves(Canvas cv, int sx0, int sx1, int sz0, int sz1, int sy0, int sy1,
                                         int bx0, int bx1, int bz0, int bz1, int by0, int by1) {
        int nx = bx1 - bx0 + 1, nz = bz1 - bz0 + 1, ny = by1 - by0 + 1;
        int total = nx * nz * ny;
        byte[] kind = new byte[total]; // 1 = tronc, 2 = feuille
        int[] dist = new int[total];
        Arrays.fill(dist, -1);
        int[] queue = new int[total];
        int qh = 0, qt = 0;
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                for (int y = by0; y <= by1; y++) {
                    Material m = cv.type(x, y, z);
                    if (m == null) continue;
                    int idx = ((x - bx0) * nz + (z - bz0)) * ny + (y - by0);
                    if (Blocks.anyLog(m)) {
                        kind[idx] = 1;
                        dist[idx] = 0;
                        queue[qt++] = idx;
                    } else if (Blocks.leaf(m)) {
                        kind[idx] = 2;
                    }
                }
            }
        }
        // comme le jeu : une feuille tient si un tronc est à 6 feuilles ou moins
        while (qh < qt) {
            int c = queue[qh++];
            int d = dist[c];
            if (d >= 6) continue;
            int y = c % ny;
            int t = c / ny;
            int z = t % nz;
            int x = t / nz;
            for (int[] n : N6) {
                int xx = x + n[0], yy = y + n[1], zz = z + n[2];
                if (xx < 0 || xx >= nx || yy < 0 || yy >= ny || zz < 0 || zz >= nz) continue;
                int i2 = (xx * nz + zz) * ny + yy;
                if (kind[i2] == 2 && dist[i2] < 0) {
                    dist[i2] = d + 1;
                    queue[qt++] = i2;
                }
            }
        }
        BlockData air = Material.AIR.createBlockData();
        int removed = 0;
        for (int x = sx0; x <= sx1; x++) {
            for (int z = sz0; z <= sz1; z++) {
                for (int y = sy0; y <= sy1; y++) {
                    int idx = ((x - bx0) * nz + (z - bz0)) * ny + (y - by0);
                    if (kind[idx] != 2 || dist[idx] >= 0) continue;
                    BlockData bd = cv.data(x, y, z);
                    if (bd instanceof Leaves l && !l.isPersistent()) {
                        cv.set(x, y, z, air);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------ lianes, mousses, cacao, neige en l'air

    private static int removeHanging(Canvas cv, int sx0, int sx1, int sz0, int sz1, int sy0, int sy1) {
        BlockData air = Material.AIR.createBlockData();
        int removed = 0;
        // de haut en bas : quand le haut d'une chaîne part, le bas part au passage suivant
        for (int y = sy1; y >= sy0; y--) {
            for (int x = sx0; x <= sx1; x++) {
                for (int z = sz0; z <= sz1; z++) {
                    Material m = cv.type(x, y, z);
                    if (m == null || m.isAir()) continue;
                    String n = m.name();
                    if (n.equals("VINE")) {
                        removed += fixVine(cv, x, y, z, air);
                    } else if (n.equals("PALE_HANGING_MOSS")) {
                        Material a = cv.type(x, y + 1, z);
                        if (a != null && !(Blocks.support(a) || a.name().equals("PALE_HANGING_MOSS"))) {
                            cv.set(x, y, z, air);
                            removed++;
                        }
                    } else if (n.equals("COCOA")) {
                        boolean ok = false;
                        for (int[] d : N6) {
                            if (d[1] != 0) continue;
                            Material t = cv.type(x + d[0], y, z + d[2]);
                            if (t == null || (Blocks.anyLog(t) && t.name().contains("JUNGLE"))) ok = true;
                        }
                        if (!ok) {
                            cv.set(x, y, z, air);
                            removed++;
                        }
                    } else if (n.equals("CREAKING_HEART")) {
                        boolean ok = false;
                        for (int[] d : N6) {
                            Material t = cv.type(x + d[0], y + d[1], z + d[2]);
                            if (t == null || Blocks.anyLog(t)) ok = true;
                        }
                        if (!ok) {
                            cv.set(x, y, z, air);
                            removed++;
                        }
                    } else if (n.equals("SNOW")) {
                        Material b = cv.type(x, y - 1, z);
                        if (b != null && b.isAir()) {
                            cv.set(x, y, z, air);
                            removed++;
                        }
                    }
                }
            }
        }
        return removed;
    }

    /** Une liane tient si chaque face touche un bloc plein, ou si la liane du dessus tient cette face. */
    private static int fixVine(Canvas cv, int x, int y, int z, BlockData air) {
        BlockData bd = cv.data(x, y, z);
        if (!(bd instanceof MultipleFacing mf)) return 0;
        Material above = cv.type(x, y + 1, z);
        MultipleFacing av = null;
        if (above != null && above.name().equals("VINE") && cv.data(x, y + 1, z) instanceof MultipleFacing t) av = t;
        boolean changed = false;
        boolean any = false;
        for (BlockFace f : mf.getAllowedFaces()) {
            if (!mf.hasFace(f)) continue;
            boolean ok;
            if (f == BlockFace.UP) {
                ok = above == null || Blocks.support(above);
            } else {
                Material nb = cv.type(x + f.getModX(), y, z + f.getModZ());
                ok = nb == null || Blocks.support(nb) || (av != null && av.hasFace(f));
            }
            if (ok) {
                any = true;
            } else {
                mf.setFace(f, false);
                changed = true;
            }
        }
        if (!any) {
            cv.set(x, y, z, air);
            return 1;
        }
        if (changed) {
            cv.set(x, y, z, mf);
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------ troncs flottants

    /** Un morceau de tronc naturel qui ne touche que de l'air / des feuilles est un reste d'arbre : on l'enlève. */
    private static int removeOrphanLogs(Canvas cv, int sx0, int sx1, int sz0, int sz1, int sy0, int sy1) {
        BlockData air = Material.AIR.createBlockData();
        Set<TreeGen.P> visited = new HashSet<>();
        int removed = 0;
        for (int x = sx0; x <= sx1; x++) {
            for (int z = sz0; z <= sz1; z++) {
                for (int y = sy0; y <= sy1; y++) {
                    Material m = cv.type(x, y, z);
                    if (m == null || !Blocks.log(m)) continue;
                    TreeGen.P start = new TreeGen.P(x, y, z);
                    if (!visited.add(start)) continue;
                    List<TreeGen.P> comp = new ArrayList<>();
                    ArrayDeque<TreeGen.P> q = new ArrayDeque<>();
                    comp.add(start);
                    q.add(start);
                    boolean keep = false;
                    while (!q.isEmpty()) {
                        TreeGen.P c = q.poll();
                        for (int[] d : N6) {
                            Material t = cv.type(c.x() + d[0], c.y() + d[1], c.z() + d[2]);
                            if (t == null) {
                                keep = true;
                            } else if (!(t.isAir() || Blocks.replaceable(t) || Blocks.log(t))) {
                                keep = true; // touche un vrai bloc (sol, construction...)
                            }
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    if (dx == 0 && dy == 0 && dz == 0) continue;
                                    int nx = c.x() + dx, ny = c.y() + dy, nz = c.z() + dz;
                                    Material t = cv.type(nx, ny, nz);
                                    if (t == null || !Blocks.log(t)) continue;
                                    if (nx < sx0 || nx > sx1 || nz < sz0 || nz > sz1 || ny < sy0 || ny > sy1) {
                                        keep = true; // la suite est hors de la zone nettoyée : on ne tranche pas
                                        continue;
                                    }
                                    TreeGen.P nb = new TreeGen.P(nx, ny, nz);
                                    if (visited.add(nb)) {
                                        comp.add(nb);
                                        q.add(nb);
                                    }
                                }
                            }
                        }
                    }
                    if (!keep && comp.size() <= 120) {
                        for (TreeGen.P p : comp) cv.set(p.x(), p.y(), p.z(), air);
                        removed += comp.size();
                    }
                }
            }
        }
        return removed;
    }
}
