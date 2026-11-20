package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Champignons géants stylés : île champignon (dôme rouge, chapeau plat brun, version à deux étages)
 * et fungus du Nether (ombrelles de verrues avec shroomlight et lianes pleureuses).
 */
final class MushroomGen {
    private MushroomGen() {}

    private static final int OUT = Integer.MIN_VALUE;

    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31) * 0xBF58476D1CE4E5B9L;
        h ^= y * 0x94D049BB133111EBL;
        h = Long.rotateLeft(h, 27) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static boolean freeCell(Material m) {
        return m != null && (m.isAir() || Blocks.replaceable(m));
    }

    // ================================================================== ÎLE CHAMPIGNON

    private static boolean mushroomBlock(Material m) {
        return m == Material.MUSHROOM_STEM || m == Material.RED_MUSHROOM_BLOCK || m == Material.BROWN_MUSHROOM_BLOCK;
    }

    /** Remplace les champignons géants vanilla d'une zone. Le marqueur : de l'podzol sous le pied (le mycélium d'origine). */
    static int island(Canvas cv, int x0, int z0, int x1, int z1, long seed, double scale) {
        Set<TreeGen.P> done = new HashSet<>();
        BlockData air = Material.AIR.createBlockData();
        int count = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int y = cv.topY(x, z);
                Material m = null;
                while (y > cv.minY()) {
                    m = cv.type(x, y, z);
                    if (m == null) break;
                    if (m.isAir() || mushroomBlock(m) || Blocks.replaceable(m) || Blocks.leaf(m)) {
                        y--;
                        continue;
                    }
                    break;
                }
                if (m != Material.MYCELIUM) continue;
                if (cv.type(x, y + 1, z) != Material.MUSHROOM_STEM) continue;
                TreeGen.P base = new TreeGen.P(x, y + 1, z);
                if (done.contains(base)) continue;
                Set<TreeGen.P> comp = collect(cv, base);
                if (comp == null) {
                    done.add(base);
                    continue;
                }
                done.addAll(comp);
                List<TreeGen.P> bases = new ArrayList<>();
                int caps = 0;
                for (TreeGen.P p : comp) {
                    Material t = cv.type(p.x(), p.y(), p.z());
                    if (t == Material.MUSHROOM_STEM) {
                        if (cv.type(p.x(), p.y() - 1, p.z()) == Material.MYCELIUM) bases.add(p);
                    } else {
                        caps++;
                    }
                }
                if (caps < 5 || bases.isEmpty()) continue;
                for (TreeGen.P p : comp) cv.set(p.x(), p.y(), p.z(), air);
                for (TreeGen.P b : bases) {
                    Random rnd = new Random(TreeGen.mix(seed ^ (b.x() * 341873128712L) ^ (b.z() * 132897987541L) ^ b.y()));
                    grow(cv, rnd, b.x(), b.y(), b.z(), scale);
                    done.add(b);
                    count++;
                }
            }
        }
        return count;
    }

    private static Set<TreeGen.P> collect(Canvas cv, TreeGen.P base) {
        Set<TreeGen.P> out = new java.util.LinkedHashSet<>();
        ArrayDeque<TreeGen.P> q = new ArrayDeque<>();
        out.add(base);
        q.add(base);
        while (!q.isEmpty()) {
            TreeGen.P c = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        TreeGen.P nb = new TreeGen.P(c.x() + dx, c.y() + dy, c.z() + dz);
                        if (Math.abs(nb.x() - base.x()) > 16 || Math.abs(nb.z() - base.z()) > 16
                                || nb.y() < base.y() - 1 || nb.y() > base.y() + 50) continue;
                        Material t = cv.type(nb.x(), nb.y(), nb.z());
                        if (t != null && mushroomBlock(t) && out.add(nb)) {
                            if (out.size() > 900) return null;
                            q.add(nb);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Construit un champignon géant dont le premier bloc de pied est (bx, by, bz) ; le sol est en dessous. */
    static void grow(Canvas cv, Random r, int bx, int by, int bz, double scale) {
        double sc = Math.max(0.6, Math.min(2.0, scale));
        double roll = r.nextDouble();
        int kind = roll < 0.45 ? 0 : roll < 0.75 ? 1 : 2; // 0 dôme rouge, 1 plat brun, 2 deux étages
        int H = (int) Math.round((9 + r.nextInt(9)) * sc);
        int free = 0;
        while (free < 45 && freeCell(cv.type(bx, by + free, bz))) free++;
        H = Math.max(3, Math.min(H, free - 8));

        double dir = r.nextDouble() * Math.PI * 2;
        double bend = r.nextDouble() * 3.0 * sc;
        double rs = H >= 14 ? 1.5 : 1.0;
        Map<TreeGen.P, Material> blocks = new LinkedHashMap<>();
        int[] sxs = new int[H + 2];
        int[] szs = new int[H + 2];
        for (int y = 0; y <= H + 1; y++) {
            double t = Math.min(1.0, y / (double) H);
            double cxd = bx + Math.cos(dir) * bend * t * t;
            double czd = bz + Math.sin(dir) * bend * t * t;
            sxs[y] = (int) Math.round(cxd);
            szs[y] = (int) Math.round(czd);
            double rad = rs * (1.0 - 0.25 * t) + (y < 2 ? 0.7 : 0.0);
            int ir = (int) Math.ceil(rad) + 1;
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int px = sxs[y] + dx, pz = szs[y] + dz;
                    double ddx = px - cxd, ddz = pz - czd;
                    if (ddx * ddx + ddz * ddz <= rad * rad + 0.25) {
                        blocks.put(new TreeGen.P(px, by + y, pz), Material.MUSHROOM_STEM);
                    }
                }
            }
        }
        long hs = TreeGen.mix(r.nextLong());
        if (kind == 0) {
            double R = (4.5 + r.nextDouble() * 2.5) * Math.sqrt(sc);
            cap(blocks, Material.RED_MUSHROOM_BLOCK, sxs[H], by + H, szs[H], R, R * 0.8, 0.0, r, hs);
        } else if (kind == 1) {
            double R = (6.0 + r.nextDouble() * 3.0) * Math.sqrt(sc);
            cap(blocks, Material.BROWN_MUSHROOM_BLOCK, sxs[H], by + H, szs[H], R, 2.6, 2.0, r, hs);
        } else {
            int y1 = Math.max(2, (int) Math.round(H * 0.55));
            double R1 = (5.5 + r.nextDouble() * 2.0) * Math.sqrt(sc);
            cap(blocks, Material.BROWN_MUSHROOM_BLOCK, sxs[y1], by + y1, szs[y1], R1, 2.4, 2.0, r, hs);
            double R2 = (3.4 + r.nextDouble() * 1.6) * Math.sqrt(sc);
            cap(blocks, Material.RED_MUSHROOM_BLOCK, sxs[H], by + H, szs[H], R2, R2 * 0.85, 0.0, r, hs + 1);
        }

        // pose : cuticule sur l'extérieur, pores (lamelles) sous le chapeau et à l'intérieur
        for (Map.Entry<TreeGen.P, Material> e : blocks.entrySet()) {
            TreeGen.P p = e.getKey();
            Material t = cv.type(p.x(), p.y(), p.z());
            if (!freeCell(t)) continue;
            BlockData bd = e.getValue().createBlockData();
            if (bd instanceof MultipleFacing mf) {
                boolean stem = e.getValue() == Material.MUSHROOM_STEM;
                for (BlockFace f : mf.getAllowedFaces()) {
                    boolean nb = blocks.containsKey(new TreeGen.P(p.x() + f.getModX(), p.y() + f.getModY(), p.z() + f.getModZ()));
                    boolean show;
                    if (stem) show = f != BlockFace.UP && f != BlockFace.DOWN && !nb;
                    else show = !nb && f != BlockFace.DOWN;
                    mf.setFace(f, show);
                }
                cv.set(p.x(), p.y(), p.z(), mf);
            } else {
                cv.set(p.x(), p.y(), p.z(), bd);
            }
        }
        // marqueur : le sol sous le pied devient du podzol
        BlockData podzol = Material.PODZOL.createBlockData();
        for (TreeGen.P p : blocks.keySet()) {
            if (p.y() != by) continue;
            Material under = cv.type(p.x(), by - 1, p.z());
            if (under == Material.MYCELIUM || under == Material.PODZOL || Blocks.ground(under == null ? Material.AIR : under)) {
                cv.set(p.x(), by - 1, p.z(), podzol);
            }
        }
        // petits champignons au pied
        int n = 4 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = 2.5 + r.nextDouble() * 4;
            int px = bx + (int) Math.round(Math.cos(a) * d), pz = bz + (int) Math.round(Math.sin(a) * d);
            Material g = cv.type(px, by - 1, pz);
            Material up = cv.type(px, by, pz);
            if ((g == Material.MYCELIUM || g == Material.PODZOL) && up != null && up.isAir()) {
                cv.set(px, by, pz, (r.nextBoolean() ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM).createBlockData());
            }
        }
    }

    /**
     * Chapeau en coque (2 blocs d'épaisseur) : profil en dôme, bord qui retombe (droop), le dessous reste creux.
     * Quelques shroomlight accrochés sous le chapeau.
     */
    private static void cap(Map<TreeGen.P, Material> out, Material mat, int ccx, int ccy, int ccz,
                            double R, double Hc, double droop, Random r, long hs) {
        int ir = (int) Math.ceil(R) + 1;
        int w = 2 * ir + 1;
        int[][] tops = new int[w][w];
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double dd = Math.sqrt(dx * dx + dz * dz);
                if (dd >= R) {
                    tops[dx + ir][dz + ir] = OUT;
                } else {
                    double f = Math.sqrt(1.0 - (dd / R) * (dd / R));
                    tops[dx + ir][dz + ir] = (int) Math.round(Hc * f - droop * Math.pow(dd / R, 3));
                }
            }
        }
        int[][] bots = new int[w][w];
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                int top = tops[dx + ir][dz + ir];
                if (top == OUT) continue;
                int bot = top - 1;
                for (int[] d : dirs) {
                    int ax = dx + d[0] + ir, az = dz + d[1] + ir;
                    if (ax < 0 || az < 0 || ax >= w || az >= w) continue;
                    int nt = tops[ax][az];
                    if (nt != OUT && nt < bot) bot = nt;
                }
                bots[dx + ir][dz + ir] = bot;
                for (int y = bot; y <= top; y++) out.put(new TreeGen.P(ccx + dx, ccy + y, ccz + dz), mat);
            }
        }
        // la tige monte jusqu'au creux du centre
        int cb = bots[ir][ir];
        for (int y = 0; y <= cb; y++) out.putIfAbsent(new TreeGen.P(ccx, ccy + y, ccz), Material.MUSHROOM_STEM);
        // lumières sous le chapeau
        for (int g = 0; g < 2; g++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = R * (0.3 + r.nextDouble() * 0.35);
            int dx = (int) Math.round(Math.cos(a) * d), dz = (int) Math.round(Math.sin(a) * d);
            if (Math.abs(dx) > ir || Math.abs(dz) > ir) continue;
            if (tops[dx + ir][dz + ir] == OUT || r.nextDouble() > 0.6) continue;
            TreeGen.P p = new TreeGen.P(ccx + dx, ccy + bots[dx + ir][dz + ir] - 1, ccz + dz);
            out.putIfAbsent(p, Material.SHROOMLIGHT);
        }
    }

    // ================================================================== NETHER

    private static boolean fungusStem(Material m) {
        return m == Material.CRIMSON_STEM || m == Material.WARPED_STEM;
    }

    private static boolean fungusPart(Material m) {
        return fungusStem(m) || m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK
                || m == Material.SHROOMLIGHT;
    }

    /**
     * Embellit les fungus du Nether SANS rien supprimer : lianes pleureuses en plus sous les verrues rouges,
     * quelques shroomlight accrochés, gouttes de verrues bleues. Seuls des blocs d'air sont remplacés.
     */
    static int netherDecor(Canvas cv, int x0, int z0, int x1, int z1, long seed) {
        int top = Math.min(cv.maxY() - 1, 125);
        int made = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = top; y > 32; y--) {
                    Material m = cv.type(x, y, z);
                    if (m == null) break;
                    boolean crimson = m == Material.NETHER_WART_BLOCK;
                    if (!crimson && m != Material.WARPED_WART_BLOCK) continue;
                    Material below = cv.type(x, y - 1, z);
                    if (below == null || !below.isAir()) continue;
                    double n = hash(seed + 31, x, y, z);
                    if (crimson) {
                        if (n < 0.05) {
                            int len = 2 + (int) (hash(seed + 32, x, y, z) * 6);
                            int k = 0;
                            while (k < len) {
                                Material c = cv.type(x, y - 1 - k, z);
                                if (c == null || !c.isAir()) break;
                                k++;
                            }
                            for (int i = 0; i < k; i++) {
                                Material vm = i == k - 1 ? Material.WEEPING_VINES : Material.WEEPING_VINES_PLANT;
                                cv.set(x, y - 1 - i, z, vm.createBlockData());
                            }
                            if (k > 0) made++;
                        } else if (n < 0.065) {
                            cv.set(x, y - 1, z, Material.SHROOMLIGHT.createBlockData());
                            made++;
                        }
                    } else if (n < 0.04) {
                        int len = 1 + (int) (hash(seed + 33, x, y, z) * 3);
                        for (int i = 0; i < len; i++) {
                            Material c = cv.type(x, y - 1 - i, z);
                            if (c == null || !c.isAir()) break;
                            boolean last = i == len - 1 && hash(seed + 34, x, y, z) < 0.3;
                            cv.set(x, y - 1 - i, z, (last ? Material.SHROOMLIGHT : Material.WARPED_WART_BLOCK).createBlockData());
                        }
                        made++;
                    }
                }
            }
        }
        return made;
    }

    static int nether(Canvas cv, int x0, int z0, int x1, int z1, long seed, double scale) {
        Set<TreeGen.P> done = new HashSet<>();
        BlockData air = Material.AIR.createBlockData();
        int count = 0;
        int top = Math.min(cv.maxY() - 1, 125);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = top; y > 32; y--) {
                    Material m = cv.type(x, y, z);
                    if (m == null || !fungusStem(m)) continue;
                    Material below = cv.type(x, y - 1, z);
                    if (below != Material.CRIMSON_NYLIUM && below != Material.WARPED_NYLIUM) continue;
                    if (!(cv.data(x, y, z) instanceof Orientable o) || o.getAxis() != Axis.Y) continue;
                    TreeGen.P base = new TreeGen.P(x, y, z);
                    if (done.contains(base)) continue;
                    Set<TreeGen.P> comp = collectFungus(cv, base);
                    if (comp == null) {
                        done.add(base);
                        continue;
                    }
                    done.addAll(comp);
                    // chaque pied garde SA couleur (une forêt crimson peut toucher une forêt warped)
                    List<TreeGen.P> bases = new ArrayList<>();
                    List<Boolean> crimsonOf = new ArrayList<>();
                    int warts = 0;
                    for (TreeGen.P p : comp) {
                        Material t = cv.type(p.x(), p.y(), p.z());
                        if (t == null) continue;
                        if (fungusStem(t)) {
                            Material u = cv.type(p.x(), p.y() - 1, p.z());
                            if (u == Material.CRIMSON_NYLIUM || u == Material.WARPED_NYLIUM) {
                                bases.add(p);
                                crimsonOf.add(t == Material.CRIMSON_STEM);
                            }
                        } else if (t != Material.SHROOMLIGHT) {
                            warts++;
                        }
                    }
                    if (warts < 8 || bases.isEmpty()) continue;
                    for (TreeGen.P p : comp) {
                        cv.set(p.x(), p.y(), p.z(), air);
                    }
                    // lianes pleureuses accrochées à l'ancien arbre
                    for (TreeGen.P p : comp) {
                        int yy = p.y() - 1;
                        while (true) {
                            Material v = cv.type(p.x(), yy, p.z());
                            if (v != Material.WEEPING_VINES && v != Material.WEEPING_VINES_PLANT) break;
                            cv.set(p.x(), yy, p.z(), air);
                            yy--;
                        }
                    }
                    for (int i = 0; i < bases.size(); i++) {
                        TreeGen.P b = bases.get(i);
                        Random rnd = new Random(TreeGen.mix(seed ^ (b.x() * 341873128712L) ^ (b.z() * 132897987541L) ^ b.y()));
                        fungus(cv, rnd, seed, b.x(), b.y(), b.z(), crimsonOf.get(i), scale);
                        done.add(b);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Ensemble de blocs du fungus (tronc, verrues, shroomlight). Retourne null si l'ensemble touche le bord de la
     * zone que le plugin peut modifier ou s'il est trop grand : on laisse alors cet arbre tel quel, au lieu de le couper.
     */
    private static Set<TreeGen.P> collectFungus(Canvas cv, TreeGen.P base) {
        Set<TreeGen.P> out = new java.util.LinkedHashSet<>();
        ArrayDeque<TreeGen.P> q = new ArrayDeque<>();
        out.add(base);
        q.add(base);
        while (!q.isEmpty()) {
            TreeGen.P c = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        TreeGen.P nb = new TreeGen.P(c.x() + dx, c.y() + dy, c.z() + dz);
                        if (Math.abs(nb.x() - base.x()) > 20 || Math.abs(nb.z() - base.z()) > 20) return null;
                        if (nb.y() < base.y() - 1 || nb.y() > base.y() + 60) continue;
                        Material t = cv.type(nb.x(), nb.y(), nb.z());
                        if (t == null) return null; // bord de la zone : arbre peut-être coupé, on n'y touche pas
                        if (fungusPart(t) && out.add(nb)) {
                            if (out.size() > 1500) return null;
                            q.add(nb);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Fungus géant : tronc tordu (1x1 ou 2x2), grande ombrelle de verrues, deuxième étage, shroomlight, lianes. */
    static void fungus(Canvas cv, Random r, long seed, int bx, int by, int bz, boolean crimson, double scale) {
        double sc = Math.max(0.6, Math.min(2.0, scale));
        Material stem = crimson ? Material.CRIMSON_STEM : Material.WARPED_STEM;
        Material wart = crimson ? Material.NETHER_WART_BLOCK : Material.WARPED_WART_BLOCK;
        int H = (int) Math.round((10 + r.nextInt(11)) * sc);
        int free = 0;
        while (free < 50 && freeCell(cv.type(bx, by + free, bz))) free++;
        H = Math.max(4, Math.min(H, free - 5));
        int th = H >= 15 ? 2 : 1;
        if (th == 2) { // un tronc 2x2 a besoin de sol plein sous ses 4 blocs, sinon il flotterait
            int[][] ex = {{1, 0}, {0, 1}, {1, 1}};
            for (int[] e : ex) {
                Material g = cv.type(bx + e[0], by - 1, bz + e[1]);
                if (g == null || !Blocks.support(g) || !freeCell(cv.type(bx + e[0], by, bz + e[1]))) th = 1;
            }
        }

        Map<TreeGen.P, Material> blocks = new LinkedHashMap<>();
        double dir = r.nextDouble() * Math.PI * 2;
        double lean = r.nextDouble() * 2.5;
        int[] ox = new int[H + 1];
        int[] oz = new int[H + 1];
        int px = 0, pz = 0;
        for (int y = 0; y <= H; y++) {
            double t = y / (double) H;
            int nx = (int) Math.round(Math.cos(dir) * lean * t * t);
            int nz = (int) Math.round(Math.sin(dir) * lean * t * t);
            if (y > 0 && (nx != px || nz != pz)) putTrunk(blocks, bx + nx, by + y - 1, bz + nz, th, stem);
            px = nx;
            pz = nz;
            ox[y] = nx;
            oz[y] = nz;
            putTrunk(blocks, bx + nx, by + y, bz + nz, th, stem);
        }
        double R = (4.0 + r.nextDouble() * 2.0) * Math.sqrt(sc);
        int roof = 0; // place libre au-dessus du sommet : sous un plafond, l'ombrelle est plus petite
        while (roof < 6 && freeCell(cv.type(bx + ox[H], by + H + 1 + roof, bz + oz[H]))) roof++;
        if (roof < 4) R = Math.min(R, 3.5 + roof * 0.5);
        double off = (th - 1) / 2.0;
        umbrella(blocks, wart, bx + ox[H] + off, by + H, bz + oz[H] + off, R, crimson, seed, r);
        for (Map.Entry<TreeGen.P, Material> e : blocks.entrySet()) {
            TreeGen.P p = e.getKey();
            Material cur = cv.type(p.x(), p.y(), p.z());
            boolean ok = freeCell(cur);
            // le tronc passe toujours : il remplace les verrues / shroomlight / lianes d'un arbre voisin
            if (!ok && e.getValue() == stem && cur != null
                    && (cur == Material.NETHER_WART_BLOCK || cur == Material.WARPED_WART_BLOCK || cur == Material.SHROOMLIGHT
                    || cur == Material.WEEPING_VINES || cur == Material.WEEPING_VINES_PLANT)) ok = true;
            if (!ok) continue;
            cv.set(p.x(), p.y(), p.z(), e.getValue().createBlockData());
        }
        // marqueur : du netherrack à la place de la nylium sous le pied
        BlockData rack = Material.NETHERRACK.createBlockData();
        for (int i = 0; i < th; i++) {
            for (int j = 0; j < th; j++) {
                Material u = cv.type(bx + i, by - 1, bz + j);
                if (u == Material.CRIMSON_NYLIUM || u == Material.WARPED_NYLIUM) cv.set(bx + i, by - 1, bz + j, rack);
            }
        }
    }

    private static void putTrunk(Map<TreeGen.P, Material> blocks, int x, int y, int z, int th, Material stem) {
        for (int i = 0; i < th; i++) {
            for (int j = 0; j < th; j++) blocks.put(new TreeGen.P(x + i, y, z + j), stem);
        }
    }

    private static void umbrella(Map<TreeGen.P, Material> blocks, Material wart, double cx, int cy, double cz,
                                 double R, boolean crimson, long seed, Random r) {
        int ir = (int) Math.ceil(R) + 2;
        int x0 = (int) Math.round(cx), z0 = (int) Math.round(cz);
        List<TreeGen.P> hang = new ArrayList<>();
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double dd = Math.sqrt(dx * dx + dz * dz);
                int x = x0 + dx, z = z0 + dz;
                double n = hash(seed, x, cy, z) - 0.5;
                double q = dd + n * 1.6; // bord irrégulier
                if (q <= R * 0.40) blocks.putIfAbsent(new TreeGen.P(x, cy + 2, z), wart);
                if (q <= R * 0.72) blocks.putIfAbsent(new TreeGen.P(x, cy + 1, z), wart);
                if (q <= R) {
                    boolean glow = dd > R * 0.3 && hash(seed + 1, x, cy, z) < 0.05;
                    blocks.putIfAbsent(new TreeGen.P(x, cy, z), glow ? Material.SHROOMLIGHT : wart);
                }
                if (q <= R && q >= R * 0.62) {
                    boolean glow = hash(seed + 2, x, cy, z) < 0.12;
                    blocks.putIfAbsent(new TreeGen.P(x, cy - 1, z), glow ? Material.SHROOMLIGHT : wart);
                }
                if (q <= R && q >= R * 0.82 && hash(seed + 3, x, cy, z) < 0.55) {
                    blocks.putIfAbsent(new TreeGen.P(x, cy - 2, z), wart);
                    if (crimson && hash(seed + 4, x, cy, z) < 0.30) hang.add(new TreeGen.P(x, cy - 3, z));
                }
            }
        }
        for (TreeGen.P p : hang) {
            int len = 2 + r.nextInt(5);
            for (int k = 0; k < len; k++) {
                blocks.putIfAbsent(new TreeGen.P(p.x(), p.y() - k, p.z()),
                        k == len - 1 ? Material.WEEPING_VINES : Material.WEEPING_VINES_PLANT);
            }
        }
    }
}
