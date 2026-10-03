package me.majorzxc.wgregionlist.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Геометрия региона в «чистом» виде, без зависимостей от WorldEdit.
 * Записи (record) сравниваются по значению — это используется для поиска изменений.
 */
public sealed interface Geometry permits Geometry.Cuboid, Geometry.Polygon, Geometry.Global {

    /** Все ключи файла, которые описывают геометрию. */
    List<String> KEYS = List.of("type", "min", "max", "min-y", "max-y", "points");

    String type();

    /** Значения ключей геометрии для записи в файл (формат WorldGuard). */
    Map<String, Object> toYaml();

    record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements Geometry {

        /** Принимает две любые противоположные точки и упорядочивает их. */
        public static Cuboid of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Cuboid(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        @Override
        public String type() {
            return "cuboid";
        }

        @Override
        public Map<String, Object> toYaml() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type());
            map.put("min", vector(minX, minY, minZ));
            map.put("max", vector(maxX, maxY, maxZ));
            return map;
        }

        private static Map<String, Object> vector(int x, int y, int z) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", x);
            map.put("y", y);
            map.put("z", z);
            return map;
        }
    }

    record Point(int x, int z) {
    }

    record Polygon(List<Point> points, int minY, int maxY) implements Geometry {

        public Polygon {
            points = List.copyOf(points);
            if (minY > maxY) {
                int swap = minY;
                minY = maxY;
                maxY = swap;
            }
        }

        @Override
        public String type() {
            return "poly2d";
        }

        @Override
        public Map<String, Object> toYaml() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type());
            map.put("min-y", minY);
            map.put("max-y", maxY);
            List<Map<String, Object>> list = new ArrayList<>(points.size());
            for (Point point : points) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("x", point.x());
                entry.put("z", point.z());
                list.add(entry);
            }
            map.put("points", list);
            return map;
        }
    }

    record Global() implements Geometry {

        @Override
        public String type() {
            return "global";
        }

        @Override
        public Map<String, Object> toYaml() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type());
            return map;
        }
    }
}
