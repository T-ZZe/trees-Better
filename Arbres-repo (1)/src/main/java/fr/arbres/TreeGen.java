package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Génération des arbres stylés : saule à rideaux de feuilles, arbre rond touffu,
 * bonsaï à canopées plates, et grand conifère étagé.
 */
final class TreeGen {
    private TreeGen() {}

    enum Species {
        OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, DARK_OAK, CHERRY, PALE_OAK, POPLAR;

        final Material log;
        final Material leaves;

        Species() {
            log = Material.getMaterial(name() + "_LOG");
            Material lv = Material.getMaterial(name() + "_LEAVES");
            // le peuplier a 3 couleurs de feuilles (rouge, orange, jaune) : on en choisit une par arbre
            if (lv == null && name().equals("POPLAR")) lv = Material.getMaterial("ORANGE_POPLAR_LEAVES");
            leaves = lv;
        }

        boolean ok() {
            return log != null && leaves != null;
        }

        static Species ofLog(Material m) {
            for (Species s : values()) {
                if (s.ok() && s.log == m) return s;
            }
            return null;
        }
    }

    enum Style { ROND, SAULE, BONSAI, GRAND, PEUPLIER, CONE, CONE_FIN, CONE_LARGE }

    record P(int x, int y, int z) {}

    static final class Shape {
        final Map<P, Axis> logs = new LinkedHashMap<>();
        final Map<P, Axis> roots = new LinkedHashMap<>();
        final Set<P> leaves = new HashSet<>();
        /** Blocs de branches (hors tronc) : on les habille de feuilles pour qu'aucun bois ne reste à nu. */
        final Set<P> branch = new HashSet<>();
        /** Feuilles de cet arbre (peuplier : couleur tirée au hasard). null = celles de l'espèce. */
        Material leafMat;
        /** Position du coeur de creaking (arbres pâles), ou null. */
        P heart;
    }

    private static final int[][] CARD = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    // ------------------------------------------------------------------ API

    /** Mélange une graine (splitmix64) : évite que deux arbres voisins aient le même "hasard". */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static Shape build(Species sp, Random r, int bx, int by, int bz, int th, double scale, long seed) {
        Ctx c = new Ctx(sp, r, seed, bx, by, bz, th, Math.max(0.6, Math.min(2.0, scale)));
        if (sp == Species.POPLAR) {
            String[] cols = {"RED", "ORANGE", "YELLOW"};
            Material lm = Material.getMaterial(cols[r.nextInt(3)] + "_POPLAR_LEAVES");
            if (lm != null) c.s.leafMat = lm;
        }
        switch (pick(sp, r)) {
            case ROND -> rond(c);
            case SAULE -> saule(c);
            case BONSAI -> bonsai(c);
            case GRAND -> grand(c);
            case PEUPLIER -> peuplier(c);
            case CONE -> cone(c, 0);
            case CONE_FIN -> cone(c, 1);
            case CONE_LARGE -> cone(c, 2);
        }
        c.dress();
        c.s.leaves.removeAll(c.s.logs.keySet());
        c.s.leaves.removeAll(c.s.roots.keySet());
        return c.s;
    }

    private static Style pick(Species sp, Random r) {
        double x = r.nextDouble();
        return switch (sp) {
            case OAK -> x < 0.15 ? Style.SAULE : x < 0.40 ? Style.ROND : x < 0.55 ? Style.BONSAI : x < 0.85 ? Style.GRAND : Style.PEUPLIER;
            case BIRCH -> x < 0.40 ? Style.PEUPLIER : x < 0.70 ? Style.ROND : Style.SAULE;
            case SPRUCE -> x < 0.40 ? Style.CONE : x < 0.70 ? Style.CONE_FIN : Style.CONE_LARGE;
            case JUNGLE -> x < 0.35 ? Style.ROND : x < 0.60 ? Style.SAULE : Style.GRAND;
            case ACACIA -> x < 0.60 ? Style.BONSAI : x < 0.80 ? Style.SAULE : Style.GRAND;
            case DARK_OAK -> x < 0.45 ? Style.GRAND : x < 0.75 ? Style.BONSAI : Style.ROND;
            case CHERRY -> x < 0.35 ? Style.BONSAI : x < 0.70 ? Style.SAULE : Style.ROND;
            case PALE_OAK -> x < 0.40 ? Style.SAULE : x < 0.70 ? Style.ROND : Style.GRAND;
            case POPLAR -> x < 0.50 ? Style.PEUPLIER : x < 0.80 ? Style.ROND : Style.GRAND;
        };
    }

    /** Pose l'arbre sur le canvas : troncs, racines, puis feuilles (avec leur distance de décomposition). */
    static void apply(Shape s, Species sp, Canvas cv, boolean snowy) {
        BlockData[] logData = new BlockData[3];
        for (Axis a : Axis.values()) {
            BlockData d = sp.log.createBlockData();
            if (d instanceof Orientable o) o.setAxis(a);
            logData[a.ordinal()] = d;
        }
        for (Map.Entry<P, Axis> e : s.logs.entrySet()) {
            P p = e.getKey();
            Material t = cv.type(p.x(), p.y(), p.z());
            if (t != null && Blocks.replaceable(t)) cv.set(p.x(), p.y(), p.z(), logData[e.getValue().ordinal()]);
        }
        for (Map.Entry<P, Axis> e : s.roots.entrySet()) {
            P p = e.getKey();
            Material t = cv.type(p.x(), p.y(), p.z());
            if (t != null && (Blocks.replaceable(t) || Blocks.ground(t))) {
                cv.set(p.x(), p.y(), p.z(), logData[e.getValue().ordinal()]);
            }
        }
        if (s.heart != null) { // coeur de creaking naturel, pris entre deux troncs
            BlockData hd = heartData();
            if (hd != null) cv.set(s.heart.x(), s.heart.y(), s.heart.z(), hd);
        }
        // Marqueur : de la terre racinée sous le pied du tronc. Le plugin sait ainsi que cet arbre est le sien
        // et ne le remplacera jamais une seconde fois.
        int baseY = Integer.MAX_VALUE;
        for (P p : s.logs.keySet()) baseY = Math.min(baseY, p.y());
        BlockData marker = Material.ROOTED_DIRT.createBlockData();
        for (Map.Entry<P, Axis> e : s.logs.entrySet()) {
            P p = e.getKey();
            if (p.y() != baseY || e.getValue() != Axis.Y) continue;
            Material under = cv.type(p.x(), p.y() - 1, p.z());
            if (under != null && Blocks.ground(under)) cv.set(p.x(), p.y() - 1, p.z(), marker);
        }

        // On ne garde que les feuilles reliées à un tronc (par des feuilles voisines) : pas de feuilles flottantes.
        // Elles sont "persistantes" : elles ne se décomposent jamais toutes seules (pas de feuillage qui disparaît).
        Set<P> keep = new HashSet<>();
        ArrayDeque<P> q = new ArrayDeque<>();
        List<P> sources = new ArrayList<>(s.logs.keySet());
        sources.addAll(s.roots.keySet());
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (P lg : sources) {
            for (int[] d : n6) {
                P nb = new P(lg.x() + d[0], lg.y() + d[1], lg.z() + d[2]);
                if (s.leaves.contains(nb) && keep.add(nb)) q.add(nb);
            }
        }
        while (!q.isEmpty()) {
            P c = q.poll();
            for (int[] d : n6) {
                P nb = new P(c.x() + d[0], c.y() + d[1], c.z() + d[2]);
                if (s.leaves.contains(nb) && keep.add(nb)) q.add(nb);
            }
        }
        BlockData leaf = (s.leafMat != null ? s.leafMat : sp.leaves).createBlockData();
        if (leaf instanceof Leaves l) {
            l.setPersistent(true);
            l.setDistance(1);
        }
        List<P> placed = new ArrayList<>();
        for (P p : keep) {
            Material t = cv.type(p.x(), p.y(), p.z());
            if (t != null && Blocks.replaceable(t)) {
                cv.set(p.x(), p.y(), p.z(), leaf);
                placed.add(p);
            }
        }
        if (snowy) { // biome enneigé : une couche de neige sur le dessus des feuilles
            BlockData snow = Material.SNOW.createBlockData();
            for (P p : placed) {
                Material above = cv.type(p.x(), p.y() + 1, p.z());
                if (above != null && above.isAir()) cv.set(p.x(), p.y() + 1, p.z(), snow);
            }
        }
    }

    private static BlockData heartData() {
        try {
            return Bukkit.createBlockData("minecraft:creaking_heart[natural=true]");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Choisit un bloc de tronc bas, entouré de troncs au-dessus et en dessous, pour y mettre un coeur de creaking. */
    static P pickHeart(Shape s, Random r) {
        int minY = Integer.MAX_VALUE;
        for (P p : s.logs.keySet()) minY = Math.min(minY, p.y());
        List<P> cand = new ArrayList<>();
        for (Map.Entry<P, Axis> e : s.logs.entrySet()) {
            P p = e.getKey();
            if (e.getValue() != Axis.Y || s.branch.contains(p)) continue;
            int k = p.y() - minY;
            if (k < 2 || k > 7) continue;
            if (s.logs.get(new P(p.x(), p.y() + 1, p.z())) == Axis.Y && s.logs.get(new P(p.x(), p.y() - 1, p.z())) == Axis.Y) {
                cand.add(p);
            }
        }
        return cand.isEmpty() ? null : cand.get(r.nextInt(cand.size()));
    }

    // ------------------------------------------------------------------ outils

    private static final class Ctx {
        final Shape s = new Shape();
        final Species sp;
        final Random r;
        final long seed;
        final int bx, by, bz, th;
        final double sc;
        final double hm; // multiplicateur de hauteur selon l'espèce
        final double rm; // multiplicateur de canopée selon l'espèce

        Ctx(Species sp, Random r, long seed, int bx, int by, int bz, int th, double sc) {
            this.sp = sp;
            this.r = r;
            this.seed = seed;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.th = th;
            this.sc = sc;
            this.hm = sp == Species.JUNGLE ? 1.5 : (sp == Species.DARK_OAK || sp == Species.PALE_OAK) ? 1.1 : 1.0;
            this.rm = sp == Species.JUNGLE ? 1.25 : 1.0;
        }

        double hash(int x, int y, int z) {
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

        double cx() {
            return bx + (th - 1) / 2.0;
        }

        double cz() {
            return bz + (th - 1) / 2.0;
        }

        void log(int x, int y, int z, Axis a) {
            s.logs.putIfAbsent(new P(x, y, z), a);
        }

        void leaf(int x, int y, int z) {
            s.leaves.add(new P(x, y, z));
        }

        void trunkBlock(int x, int y, int z) {
            for (int i = 0; i < th; i++) {
                for (int j = 0; j < th; j++) log(x + i, y, z + j, Axis.Y);
            }
        }

        /** Racines : deux blocs qui sortent du pied du tronc, le second enfoncé dans la terre. */
        void roots(int count, int len) {
            if (th < 2) return; // arbres fins : aucune racine apparente
            count = Math.min(count, 3);
            len = 1;
            double ph = r.nextDouble() * Math.PI * 2;
            for (int k = 0; k < count; k++) {
                double a = ph + k * Math.PI * 2 / count + (r.nextDouble() - 0.5) * 0.5;
                double dx = Math.cos(a);
                double dz = Math.sin(a);
                Axis ax = Math.abs(dx) >= Math.abs(dz) ? Axis.X : Axis.Z;
                int l = Math.max(2, len - r.nextInt(2));
                for (int st = 1; st <= l; st++) {
                    int px = (int) Math.round(cx() + dx * (th / 2.0 + st - 0.5));
                    int pz = (int) Math.round(cz() + dz * (th / 2.0 + st - 0.5));
                    s.roots.putIfAbsent(new P(px, st == 1 ? by : by - 1, pz), ax);
                }
            }
        }

        /** Branche en escalier (blocs reliés par les faces), comme sur l'image de référence. */
        void stair(int x1, int y1, int z1, int x2, int y2, int z2) {
            int x = x1, y = y1, z = z1;
            log(x, y, z, Axis.Y);
            int guard = 0;
            int idx = 0;
            while ((x != x2 || y != y2 || z != z2) && guard++ < 200) {
                int rx = x2 - x, ry = y2 - y, rz = z2 - z;
                int ax = Math.abs(rx), ay = Math.abs(ry), az = Math.abs(rz);
                Axis a;
                if (ax >= ay && ax >= az && ax > 0) {
                    x += Integer.signum(rx);
                    a = Axis.X;
                } else if (az >= ay && az > 0) {
                    z += Integer.signum(rz);
                    a = Axis.Z;
                } else {
                    y += Integer.signum(ry);
                    a = Axis.Y;
                }
                log(x, y, z, a);
                if (++idx >= 2) s.branch.add(new P(x, y, z));
            }
        }

        /** Habille les branches : une gaine de feuilles autour de chaque bloc de branche (plus dense au-dessus). */
        void dress() {
            for (P p : new ArrayList<>(s.branch)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int man = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (man == 0) continue;
                            double ch = man == 1 ? 1.0 : (man == 2 ? 0.8 : 0.4);
                            int x = p.x() + dx, y = p.y() + dy, z = p.z() + dz;
                            if (hash(x, y, z) < ch) leaf(x, y, z);
                        }
                    }
                }
                if (hash(p.x(), p.y() + 2, p.z()) < 0.45) leaf(p.x(), p.y() + 2, p.z());
            }
        }

        /** Boule de feuilles irrégulière ; chunky = bords en gros blocs. */
        void blob(double cx, double cy, double cz, double rx, double ry, double rz, double rag, boolean chunky) {
            int ix = (int) Math.ceil(rx + 1), iy = (int) Math.ceil(ry + 1), iz = (int) Math.ceil(rz + 1);
            int x0 = (int) Math.round(cx), y0 = (int) Math.round(cy), z0 = (int) Math.round(cz);
            for (int dx = -ix; dx <= ix; dx++) {
                for (int dy = -iy; dy <= iy; dy++) {
                    for (int dz = -iz; dz <= iz; dz++) {
                        double ex = dx / rx;
                        double ey = dy < 0 ? dy / (ry * 0.8) : dy / ry;
                        double ez = dz / rz;
                        double d = ex * ex + ey * ey + ez * ez;
                        int x = x0 + dx, y = y0 + dy, z = z0 + dz;
                        double n = chunky ? 0.65 * hash(x >> 1, y >> 1, z >> 1) + 0.35 * hash(x, y, z) : hash(x, y, z);
                        if (d + (n - 0.5) * rag * 0.7 <= 1.0) leaf(x, y, z);
                    }
                }
            }
        }

        /** Disque plat de feuilles (canopée en nuage, étage de conifère...). */
        void disc(double cx, int y, double cz, double rad, double rag) {
            if (rad <= 0.3) {
                leaf((int) Math.round(cx), y, (int) Math.round(cz));
                return;
            }
            int x0 = (int) Math.round(cx), z0 = (int) Math.round(cz);
            int ir = (int) Math.ceil(rad + 1);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double d = (dx * dx + dz * dz) / (rad * rad);
                    double n = hash(x0 + dx, y, z0 + dz);
                    if (d + (n - 0.5) * rag <= 1.0) leaf(x0 + dx, y, z0 + dz);
                }
            }
        }

        /** (Plus de branches cachées : les feuilles sont persistantes, il n'y a donc plus besoin de bois dedans.) */
        void arms(int cx, int cy, int cz, int len) {
        }

        /** Canopée en nuage : 4 couches aux bords festonnés. */
        void cloud(double cx, int y, double cz, double rad) {
            disc(cx, y - 1, cz, rad - 1.8, 1.0);
            disc(cx, y, cz, rad, 1.4);
            disc(cx, y + 1, cz, rad - 0.6, 1.3);
            disc(cx, y + 2, cz, rad - 2.4, 1.0);
            arms((int) Math.round(cx), y, (int) Math.round(cz), Math.max(2, (int) Math.round(rad * 0.6)));
        }
    }

    // ------------------------------------------------------------------ styles

    /** Arbre rond et touffu : tronc droit, grosse canopée en gros blocs. */
    private static void rond(Ctx c) {
        Random r = c.r;
        int trunkH = (int) Math.round((6 + r.nextInt(5)) * c.sc * (c.th == 2 ? 1.25 : 1.0) * c.hm);
        double rad = (4.6 + r.nextDouble() * 2.0) * Math.sqrt(c.sc) * (c.th == 2 ? 1.2 : 1.0) * c.rm;
        double lean = r.nextDouble() * 1.6;
        double dir = r.nextDouble() * Math.PI * 2;
        int ox = 0, oz = 0;
        int[] offXs = new int[trunkH + 1];
        int[] offZs = new int[trunkH + 1];
        for (int y = 0; y <= trunkH; y++) {
            double t = (double) y / trunkH;
            int nx = (int) Math.round(Math.cos(dir) * lean * t * t);
            int nz = (int) Math.round(Math.sin(dir) * lean * t * t);
            if ((nx != ox || nz != oz) && y > 0) c.trunkBlock(c.bx + nx, c.by + y - 1, c.bz + nz);
            ox = nx;
            oz = nz;
            offXs[y] = ox;
            offZs[y] = oz;
            c.trunkBlock(c.bx + ox, c.by + y, c.bz + oz);
        }
        c.roots(4 + r.nextInt(2), c.th == 2 ? 3 : 2);
        double tx = c.cx() + ox, tz = c.cz() + oz;
        int topY = c.by + trunkH;
        double cy = topY + rad * 0.55;
        for (int y = topY; y <= (int) Math.round(cy); y++) c.log((int) Math.round(tx), y, (int) Math.round(tz), Axis.Y);
        c.blob(tx, cy, tz, rad, rad * 0.88, rad, 0.85, true);
        int lobes = 2 + r.nextInt(3);
        for (int i = 0; i < lobes; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double dist = rad * 0.55;
            double lr = rad * 0.62;
            c.blob(tx + Math.cos(a) * dist, cy + (r.nextDouble() - 0.4) * rad * 0.5, tz + Math.sin(a) * dist,
                    lr, lr * 0.85, lr, 0.9, true);
        }
        c.arms((int) Math.round(tx), (int) Math.round(cy), (int) Math.round(tz), Math.max(2, (int) (rad * 0.55)));
        // deux branches latérales avec leur petite touffe
        for (int i = 0; i < 2; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int yy = Math.min(trunkH, (int) Math.round(trunkH * (0.7 + 0.2 * i)));
            int y = c.by + yy;
            int sx0 = c.bx + offXs[yy], sz0 = c.bz + offZs[yy]; // sur le tronc, à cette hauteur
            int ex = (int) Math.round(sx0 + Math.cos(a) * 3);
            int ez = (int) Math.round(sz0 + Math.sin(a) * 3);
            c.stair(sx0, y, sz0, ex, y + 1, ez);
            c.blob(ex, y + 2, ez, 2.6, 2.2, 2.6, 0.9, true);
        }
    }

    /** Saule penché : tronc en escalier, grands rideaux de feuilles qui pendent. */
    private static void saule(Ctx c) {
        Random r = c.r;
        int h = (int) Math.round((16 + r.nextInt(7)) * c.sc * (c.th == 2 ? 1.2 : 1.0) * c.hm);
        double lean = 3.0 + r.nextDouble() * 2.5;
        int[] dirLean = CARD[r.nextInt(4)];
        int[] offX = new int[h + 1];
        int[] offZ = new int[h + 1];
        for (int y = 0; y <= h; y++) {
            int o = (int) Math.round(lean * y / h);
            offX[y] = dirLean[0] * o;
            offZ[y] = dirLean[1] * o;
            if (y > 0 && (offX[y] != offX[y - 1] || offZ[y] != offZ[y - 1])) {
                c.trunkBlock(c.bx + offX[y], c.by + y - 1, c.bz + offZ[y]);
            }
            c.trunkBlock(c.bx + offX[y], c.by + y, c.bz + offZ[y]);
        }
        c.roots(4, c.th == 2 ? 3 : 2);

        int tiers = h >= 19 ? 3 : 2;
        double[] fr = tiers == 3 ? new double[] {0.52, 0.74, 0.94} : new double[] {0.62, 0.90};
        for (int k = 0; k < tiers; k++) {
            int y = Math.min(h, (int) Math.round(h * fr[k]));
            double cxd = c.bx + offX[y] + (c.th - 1) / 2.0;
            double czd = c.bz + offZ[y] + (c.th - 1) / 2.0;
            // rideaux de part et d'autre du tronc, en tournant d'un étage à l'autre
            int d0 = (r.nextInt(4) + k) % 4;
            List<Integer> dirs = new ArrayList<>(List.of(d0, (d0 + 2) % 4));
            if (k == tiers - 1 || r.nextInt(3) == 0) dirs.add((d0 + 1 + 2 * r.nextInt(2)) % 4);
            for (int dd : dirs) {
                panel(c, cxd, czd, c.by + y, CARD[dd], 3 + r.nextInt(2) + (c.th == 2 ? 1 : 0));
            }
        }
        int tx = (int) Math.round(c.bx + offX[h] + (c.th - 1) / 2.0);
        int tz = (int) Math.round(c.bz + offZ[h] + (c.th - 1) / 2.0);
        int ty = c.by + h;
        c.disc(tx, ty + 1, tz, 3.3 * c.rm, 0.9);
        c.disc(tx, ty + 2, tz, 2.3 * c.rm, 0.8);
        c.leaf(tx, ty + 3, tz);
    }

    /** Un rideau de feuilles : une planche de branches avec des feuilles qui retombent. */
    private static void panel(Ctx c, double cxd, double czd, int ylog, int[] dir, int out) {
        Random r = c.r;
        for (int s = 1; s <= out; s++) {
            int x = (int) Math.round(cxd + dir[0] * (c.th / 2.0 + s - 0.5));
            int z = (int) Math.round(czd + dir[1] * (c.th / 2.0 + s - 0.5));
            c.log(x, ylog, z, dir[0] != 0 ? Axis.X : Axis.Z);
            if (s >= 2) c.s.branch.add(new P(x, ylog, z));
        }
        int pcx = (int) Math.round(cxd + dir[0] * (c.th / 2.0 + out - 0.5));
        int pcz = (int) Math.round(czd + dir[1] * (c.th / 2.0 + out - 0.5));
        int[] perp = {-dir[1], dir[0]};
        int half = 2 + (r.nextInt(3) == 0 ? 1 : 0);
        for (int u = -half; u <= half; u++) {
            c.log(pcx + perp[0] * u, ylog, pcz + perp[1] * u, perp[0] != 0 ? Axis.X : Axis.Z);
            c.s.branch.add(new P(pcx + perp[0] * u, ylog, pcz + perp[1] * u));
        }
        for (int u = -half; u <= half; u++) {
            boolean edge = Math.abs(u) == half;
            int base = 3 + (int) (c.hash(pcx + perp[0] * u, ylog, pcz + perp[1] * u) * 4);
            for (int v = -1; v <= 1; v++) {
                int len = base - (v != 0 ? 1 : 0) - (edge ? 1 : 0);
                int x = pcx + perp[0] * u + dir[0] * v;
                int z = pcz + perp[1] * u + dir[1] * v;
                for (int k = 0; k <= len; k++) c.leaf(x, ylog - k, z);
                if (!edge) {
                    c.leaf(x, ylog + 1, z);
                    if (v == 0) c.leaf(x, ylog + 2, z);
                } else if (v == 0) {
                    c.leaf(x, ylog + 1, z);
                }
            }
        }
    }

    /** Bonsaï : tronc qui zigzague, branches tordues terminées par des nuages plats. */
    private static void bonsai(Ctx c) {
        Random r = c.r;
        int h = (int) Math.round((12 + r.nextInt(7)) * c.sc * (c.th == 2 ? 1.15 : 1.0) * c.hm);
        int x = c.bx, z = c.bz;
        int[] tx = new int[h + 1];
        int[] tz = new int[h + 1];
        int segLeft = 2 + r.nextInt(2);
        boolean alongX = r.nextBoolean();
        int sx = r.nextBoolean() ? 1 : -1;
        int sz = r.nextBoolean() ? 1 : -1;
        for (int y = 0; y <= h; y++) {
            c.trunkBlock(x, c.by + y, z);
            segLeft--;
            if (segLeft <= 0 && y >= 2 && y < h - 1) {
                if (alongX) x += sx; else z += sz;
                c.trunkBlock(x, c.by + y, z); // liaison horizontale
                segLeft = 2 + r.nextInt(2);
                if (r.nextInt(3) > 0) alongX = !alongX;
                if (r.nextInt(3) == 0) {
                    sx = -sx;
                    sz = -sz;
                }
            }
            tx[y] = x;
            tz[y] = z;
        }
        c.roots(4, c.th == 2 ? 3 : 2);

        int nb = 3 + (h >= 16 ? 1 : 0);
        double base = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < nb; i++) {
            double frac = 0.35 + 0.40 * i / Math.max(1, nb - 1);
            int y = (int) Math.round(h * frac);
            double ang = base + i * (Math.PI * 2 / nb) + r.nextDouble() * 0.7;
            int len = 5 + r.nextInt(4);
            int rise = 2 + r.nextInt(3);
            int sxp = tx[y], szp = tz[y];
            int ex = sxp + (int) Math.round(Math.cos(ang) * len);
            int ez = szp + (int) Math.round(Math.sin(ang) * len);
            int ey = c.by + y + rise;
            c.stair(sxp, c.by + y, szp, ex, ey, ez);
            c.cloud(ex, ey + 1, ez, (3.0 + r.nextDouble() * 1.8) * Math.sqrt(c.sc) * c.rm);
        }
        for (int y = (int) (h * 0.55); y <= h; y++) { // le haut du tronc n'est plus nu
            for (int[] d : CARD) {
                int lx = tx[y] + (d[0] > 0 ? c.th : d[0]);
                int lz = tz[y] + (d[1] > 0 ? c.th : d[1]);
                if (c.hash(lx, c.by + y, lz) < 0.6) c.leaf(lx, c.by + y, lz);
            }
        }
        c.cloud(tx[h] + (c.th - 1) / 2.0, c.by + h + 1, tz[h] + (c.th - 1) / 2.0,
                (4.0 + r.nextDouble() * 1.6) * Math.sqrt(c.sc) * c.rm);
    }

    /** Grand chêne étalé : tronc épais, basses branches qui s'écartent, large couronne en plusieurs lobes. */
    private static void grand(Ctx c) {
        Random r = c.r;
        int h = (int) Math.round((12 + r.nextInt(6)) * c.sc * (c.th == 2 ? 1.15 : 1.0) * c.hm);
        int trunkH = (int) Math.round(h * 0.6);
        double lean = r.nextDouble() * 1.2;
        double dir = r.nextDouble() * Math.PI * 2;
        int[] offXs = new int[trunkH + 1];
        int[] offZs = new int[trunkH + 1];
        int ox = 0, oz = 0;
        for (int y = 0; y <= trunkH; y++) {
            double t = (double) y / trunkH;
            int nx = (int) Math.round(Math.cos(dir) * lean * t * t);
            int nz = (int) Math.round(Math.sin(dir) * lean * t * t);
            if ((nx != ox || nz != oz) && y > 0) c.trunkBlock(c.bx + nx, c.by + y - 1, c.bz + nz);
            ox = nx;
            oz = nz;
            offXs[y] = ox;
            offZs[y] = oz;
            c.trunkBlock(c.bx + ox, c.by + y, c.bz + oz);
        }
        c.roots(4, c.th == 2 ? 3 : 2);
        double tx = c.cx() + ox, tz = c.cz() + oz;
        int topY = c.by + trunkH;
        double rm = Math.sqrt(c.sc) * c.rm;

        // basses branches qui s'écartent, chacune finissant par un gros lobe aplati
        int nb = 4 + r.nextInt(2);
        double base = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < nb; i++) {
            int yy = Math.min(trunkH, (int) Math.round(trunkH * (0.55 + 0.35 * i / Math.max(1, nb - 1))));
            double ang = base + i * (Math.PI * 2 / nb) + r.nextDouble() * 0.6;
            int len = 4 + r.nextInt(3);
            int sx0 = c.bx + offXs[yy], sz0 = c.bz + offZs[yy];
            int ex = sx0 + (int) Math.round(Math.cos(ang) * len);
            int ez = sz0 + (int) Math.round(Math.sin(ang) * len);
            int ey = c.by + yy + 1 + r.nextInt(3);
            c.stair(sx0, c.by + yy, sz0, ex, ey, ez);
            double lr = (3.4 + r.nextDouble() * 1.0) * rm;
            c.blob(ex, ey + 1, ez, lr, lr * 0.62, lr, 0.9, true);
        }
        // couronne centrale large et basse
        double cr = (5.0 + r.nextDouble() * 1.4) * rm;
        int cy = topY + 2;
        for (int y = topY; y <= cy; y++) c.log((int) Math.round(tx), y, (int) Math.round(tz), Axis.Y);
        c.blob(tx, cy + 1, tz, cr, cr * 0.62, cr, 0.9, true);
    }

    /** Arbre colonne (type peuplier) : un seul tronc, couronne étroite et très haute. */
    private static void peuplier(Ctx c) {
        Random r = c.r;
        int h = (int) Math.round((17 + r.nextInt(8)) * c.sc * c.hm);
        double rad = (2.6 + r.nextDouble() * 1.0) * c.rm;
        int trunkH = Math.max(5, (int) Math.round(h * 0.3));
        int top = h - 2;
        for (int y = 0; y <= top; y++) c.trunkBlock(c.bx, c.by + y, c.bz);
        c.roots(3, 2);
        double cx = c.cx(), cz = c.cz();
        double mid = c.by + trunkH + (h - trunkH) / 2.0;
        double ry = (h - trunkH) / 2.0;
        c.blob(cx, mid, cz, rad, ry, rad, 0.8, false);
        // quelques bosses sur les côtés pour casser la silhouette
        int bumps = 3 + r.nextInt(3);
        for (int i = 0; i < bumps; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double yy = c.by + trunkH + 1 + r.nextDouble() * (h - trunkH - 3);
            double br = rad * (0.55 + r.nextDouble() * 0.2);
            c.blob(cx + Math.cos(a) * rad * 0.7, yy, cz + Math.sin(a) * rad * 0.7, br, br * 1.2, br, 0.9, false);
        }
    }

    /** Conifère étagé : 0 = classique, 1 = fin et très haut, 2 = large et touffu. */
    private static void cone(Ctx c, int variant) {
        Random r = c.r;
        int h;
        double maxR;
        int period;
        int first;
        if (variant == 1) {
            h = (int) Math.round((22 + r.nextInt(11)) * c.sc);
            maxR = Math.min(4.2, 2.8 + 1.4 * Math.max(0, Math.min(1, (h - 22) / 10.0))) * Math.sqrt(c.sc);
            period = 3;
            first = Math.max(5, (int) (h * 0.18));
        } else if (variant == 2) {
            h = (int) Math.round((15 + r.nextInt(8)) * c.sc);
            maxR = Math.min(8.4, 5.4 + 3.0 * Math.max(0, Math.min(1, (h - 15) / 7.0))) * Math.sqrt(c.sc);
            period = 4;
            first = Math.max(3, (int) (h * 0.15));
        } else {
            h = (int) Math.round((18 + r.nextInt(11)) * c.sc);
            maxR = Math.min(7.2, 4.2 + 3.0 * Math.max(0, Math.min(1, (h - 18) / 10.0))) * Math.sqrt(c.sc);
            period = 4;
            first = Math.max(4, (int) (h * 0.2));
        }
        // le tronc s'arrête sous la pointe : les feuilles la recouvrent, aucun bout de bois ne dépasse
        for (int y = 0; y <= h - 3; y++) c.trunkBlock(c.bx, c.by + y, c.bz);
        c.roots(4, c.th == 2 ? 3 : 2);
        int topY = c.by + h;
        double cx = c.cx(), cz = c.cz();
        double rag = variant == 2 ? 0.7 : 0.8;
        for (int y = topY + 2; y >= c.by + first; y--) {
            int dep = topY - y;
            double rr;
            int phase = 0;
            if (dep < 0) {
                rr = dep == -1 ? 1.4 : 0.6;
            } else {
                double t = Math.max(0, Math.min(1, dep / (double) (h - first)));
                phase = Math.floorMod(dep, period);
                double f = 0.45 + 0.55 * (phase / (period - 1.0));
                rr = (0.8 + maxR * Math.pow(t, 0.9)) * f;
            }
            c.disc(cx, y, cz, rr, rag);
            if (dep > 3 && phase == period - 1) c.disc(cx, y - 1, cz, rr * (variant == 2 ? 0.85 : 0.7), rag);
        }
    }
}
