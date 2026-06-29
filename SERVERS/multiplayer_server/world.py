#!/usr/bin/env python3
"""
World streaming for the Michi MP server.

Loads TMX maps, chops each tile layer into gzipped chunks for on-demand delivery,
builds a server-side collision oracle, and indexes trigger rectangles so the server
can fire map-transition / spawn events authoritatively.

Pure Python + stdlib (xml.etree, gzip, base64, struct). No extra deps.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import io
import logging
import re
import struct
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

log = logging.getLogger("michi-mp.world")

# Top 3 bits of a 32-bit GID are Tiled flip flags — mask them off for tile lookups.
GID_MASK = 0x1FFFFFFF
FLIP_BITS = 0xE0000000


@dataclass
class TmxObject:
    """One <object> from a Tiled objectgroup, with properties already coerced."""
    obj_id: int
    name: str
    obj_type: str
    x: float
    y: float
    w: float
    h: float
    properties: dict = field(default_factory=dict)

    def rect_pixels(self) -> tuple[float, float, float, float]:
        return self.x, self.y, max(1.0, self.w), max(1.0, self.h)


@dataclass
class TmxLayer:
    """A tile layer with its raw 32-bit GID grid (flip bits intact)."""
    name: str
    width: int
    height: int
    raw: list[list[int]]    # [row][col]
    properties: dict = field(default_factory=dict)


class TmxMap:
    """A TMX map split into fixed-size chunks for streaming."""

    def __init__(self, map_id: str, path: Path, chunk_size: int = 32):
        self.map_id = map_id
        self.path = path
        self.chunk_size = max(4, int(chunk_size))

        self.raw_bytes: bytes = path.read_bytes()
        self.sha256: str = hashlib.sha256(self.raw_bytes).hexdigest()

        self.tree = ET.ElementTree(ET.fromstring(self.raw_bytes))
        root = self.tree.getroot()

        if root.tag != "map":
            raise ValueError(f"{path}: root is <{root.tag}>, expected <map>")

        self.width = int(root.get("width", "0"))
        self.height = int(root.get("height", "0"))
        self.tilewidth = int(root.get("tilewidth", "32"))
        self.tileheight = int(root.get("tileheight", "32"))
        if self.width <= 0 or self.height <= 0:
            raise ValueError(f"{path}: invalid dimensions {self.width}x{self.height}")

        self.properties: dict = self._parse_properties(root)
        self.layers: list[TmxLayer] = []
        self.object_groups: dict[str, list[TmxObject]] = {}

        for child in list(root):
            if child.tag == "layer":
                self.layers.append(self._parse_layer(child))
            elif child.tag == "objectgroup":
                gname = child.get("name", "")
                self.object_groups[gname] = self._parse_object_group(child)

        self._chunk_cache: dict[tuple[int, int, int], dict] = {}
        self.num_chunks_x = (self.width + self.chunk_size - 1) // self.chunk_size
        self.num_chunks_y = (self.height + self.chunk_size - 1) // self.chunk_size

        self.skeleton_xml: str = self._build_skeleton_xml()
        self.skeleton_bytes: bytes = self.skeleton_xml.encode("utf-8")
        self.skeleton_sha256: str = hashlib.sha256(self.skeleton_bytes).hexdigest()

        self.collision_rects: list[tuple[float, float, float, float]] = []
        self.collision_tile_layer_indices: list[int] = []
        self._build_collision()

        self.spawns: dict[str, tuple[int, int]] = {}
        self.triggers: list[TmxObject] = []
        self._build_events()

        self.default_spawn: tuple[int, int] = self._resolve_default_spawn()

        log.info(
            "Map '%s' loaded: %dx%d tiles (%dx%d chunks, %d layers, "
            "%d trigger objs, %d spawns, %d collision rects)",
            self.map_id, self.width, self.height,
            self.num_chunks_x, self.num_chunks_y,
            len(self.layers), len(self.triggers),
            len(self.spawns), len(self.collision_rects),
        )

    @staticmethod
    def _parse_properties(node: ET.Element) -> dict:
        props: dict = {}
        for child in node:
            if child.tag != "properties":
                continue
            for p in child.findall("property"):
                name = p.get("name", "")
                if not name:
                    continue
                ptype = p.get("type", "string")
                value = p.get("value", "") or (p.text or "")
                if ptype == "int":
                    try: value = int(value)
                    except (TypeError, ValueError): value = 0
                elif ptype == "float":
                    try: value = float(value)
                    except (TypeError, ValueError): value = 0.0
                elif ptype == "bool":
                    value = str(value).strip().lower() in ("1", "true", "yes")
                props[name] = value
        return props

    def _parse_layer(self, layer_node: ET.Element) -> TmxLayer:
        name = layer_node.get("name", "")
        lw = int(layer_node.get("width", str(self.width)))
        lh = int(layer_node.get("height", str(self.height)))
        properties = self._parse_properties(layer_node)

        data = layer_node.find("data")
        if data is None:
            return TmxLayer(name, lw, lh, [[0] * lw for _ in range(lh)], properties)

        encoding = (data.get("encoding") or "").lower()
        compression = (data.get("compression") or "").lower()

        raw: list[list[int]] = [[0] * lw for _ in range(lh)]

        if encoding == "csv":
            text = (data.text or "").strip()
            cells = [c for c in re.split(r"[\s,]+", text) if c]
            for i, cell in enumerate(cells):
                if i >= lw * lh:
                    break
                try:
                    v = int(cell) & 0xFFFFFFFF
                except ValueError:
                    v = 0
                raw[i // lw][i % lw] = v
        elif encoding == "base64":
            blob = base64.b64decode((data.text or "").strip())
            if compression == "gzip":
                blob = gzip.decompress(blob)
            elif compression == "zlib":
                import zlib
                blob = zlib.decompress(blob)
            elif compression == "zstd":
                import zstandard
                blob = zstandard.ZstdDecompressor().decompress(blob)
            count = lw * lh
            unpacked = struct.unpack("<" + "I" * count, blob[: 4 * count])
            for i, v in enumerate(unpacked):
                raw[i // lw][i % lw] = v
        elif encoding in ("", "xml"):
            tiles = data.findall("tile")
            for i, tile in enumerate(tiles):
                if i >= lw * lh:
                    break
                gid = tile.get("gid", "0")
                try: v = int(gid) & 0xFFFFFFFF
                except ValueError: v = 0
                raw[i // lw][i % lw] = v
        else:
            raise ValueError(f"Unsupported layer encoding '{encoding}' in {self.path}")

        return TmxLayer(name, lw, lh, raw, properties)

    def _parse_object_group(self, og_node: ET.Element) -> list[TmxObject]:
        out: list[TmxObject] = []
        for o in og_node.findall("object"):
            try:
                obj = TmxObject(
                    obj_id=int(o.get("id", "0")),
                    name=o.get("name", ""),
                    obj_type=o.get("type", "") or o.get("class", ""),
                    x=float(o.get("x", "0")),
                    y=float(o.get("y", "0")),
                    w=float(o.get("width", "0")),
                    h=float(o.get("height", "0")),
                    properties=self._parse_properties(o),
                )
                out.append(obj)
            except Exception as exc:
                log.warning("Skipping malformed object in %s: %s", self.path, exc)
        return out

    def _build_skeleton_xml(self) -> str:
        # Clone the tree and zero out all <data> blocks so the client gets
        # map structure without tile data (chunks are sent separately on demand).
        clone_root = ET.fromstring(self.raw_bytes)
        for layer_node in clone_root.findall("layer"):
            data = layer_node.find("data")
            if data is None:
                continue
            lw = int(layer_node.get("width", str(self.width)))
            lh = int(layer_node.get("height", str(self.height)))
            data.set("encoding", "csv")
            if "compression" in data.attrib:
                del data.attrib["compression"]
            for child in list(data):
                data.remove(child)
            row = ",".join(["0"] * lw)
            data.text = "\n" + ("\n".join([row] * lh)) + "\n"
        xml_bytes = ET.tostring(clone_root, encoding="utf-8", xml_declaration=True)
        return xml_bytes.decode("utf-8")

    def _build_collision(self) -> None:
        # Rect-based: objects in the "Collision" objectgroup.
        group = self.object_groups.get("Collision", [])
        for o in group:
            if o.w > 0 and o.h > 0:
                self.collision_rects.append((o.x, o.y, o.w, o.h))

        # Tile-based: any layer with property collision=true or named "Collision".
        for idx, layer in enumerate(self.layers):
            if layer.properties.get("collision") is True or layer.name == "Collision":
                self.collision_tile_layer_indices.append(idx)

    def _build_events(self) -> None:
        events_group = self.object_groups.get("Events", [])
        for o in events_group:
            otype = (o.obj_type or "").lower()
            if otype in ("spawnpoint", "spawn", "spawnzone"):
                col = int(o.x // self.tilewidth)
                row = int(o.y // self.tileheight)
                key = o.name or f"_obj{o.obj_id}"
                self.spawns[key] = (col, row)
                continue
            # Everything else in Events is a trigger the client acts on.
            # Server only enforces map transitions.
            if o.w > 0 and o.h > 0:
                self.triggers.append(o)

    def _resolve_default_spawn(self) -> tuple[int, int]:
        if "defaultSpawnCol" in self.properties and "defaultSpawnRow" in self.properties:
            try:
                return int(self.properties["defaultSpawnCol"]), int(self.properties["defaultSpawnRow"])
            except (TypeError, ValueError):
                pass
        if "defaultSpawn" in self.properties:
            raw = str(self.properties["defaultSpawn"]).strip()
            m = re.match(r"\s*(\d+)\s*[,xX]\s*(\d+)\s*$", raw)
            if m:
                return int(m.group(1)), int(m.group(2))
            if raw in self.spawns:
                return self.spawns[raw]
        for name in ("default", "spawn", "start"):
            if name in self.spawns:
                return self.spawns[name]
        if self.spawns:
            return next(iter(self.spawns.values()))
        return self.width // 2, self.height // 2

    def get_chunk(self, layer_idx: int, cx: int, cy: int) -> Optional[dict]:
        """Return a serializable chunk dict, or None if out of range."""
        if layer_idx < 0 or layer_idx >= len(self.layers):
            return None
        if cx < 0 or cx >= self.num_chunks_x:
            return None
        if cy < 0 or cy >= self.num_chunks_y:
            return None
        cached = self._chunk_cache.get((layer_idx, cx, cy))
        if cached is not None:
            return cached

        layer = self.layers[layer_idx]
        cs = self.chunk_size
        x0, y0 = cx * cs, cy * cs
        x1 = min(layer.width, x0 + cs)
        y1 = min(layer.height, y0 + cs)
        w, h = x1 - x0, y1 - y0
        flat = bytearray()
        for r in range(y0, y1):
            row = layer.raw[r]
            for c in range(x0, x1):
                flat += struct.pack("<I", row[c])
        compressed = gzip.compress(bytes(flat), compresslevel=6)
        payload = {
            "layer": layer.name,
            "layer_idx": layer_idx,
            "cx": cx, "cy": cy,
            "w": w, "h": h,
            "data": base64.b64encode(compressed).decode("ascii"),
        }
        self._chunk_cache[(layer_idx, cx, cy)] = payload
        return payload

    def info_message(self) -> dict:
        """Top-level map info sent immediately on player join."""
        return {
            "type": "world_info",
            "map_id": self.map_id,
            "sha256": self.sha256,
            "skeleton_sha256": self.skeleton_sha256,
            "skeleton_xml_b64": base64.b64encode(self.skeleton_bytes).decode("ascii"),
            "width": self.width,
            "height": self.height,
            "tilewidth": self.tilewidth,
            "tileheight": self.tileheight,
            "chunk_size": self.chunk_size,
            "chunks_x": self.num_chunks_x,
            "chunks_y": self.num_chunks_y,
            "layers": [
                {"idx": i, "name": L.name, "width": L.width, "height": L.height}
                for i, L in enumerate(self.layers)
            ],
            "default_spawn": list(self.default_spawn),
            "spawns": {k: list(v) for k, v in self.spawns.items()},
        }

    def is_walkable(self, px: float, py: float) -> bool:
        """Return True iff the pixel point is inside map bounds and not blocked."""
        if px < 0 or py < 0:
            return False
        if px >= self.width * self.tilewidth or py >= self.height * self.tileheight:
            return False
        for rx, ry, rw, rh in self.collision_rects:
            if rx <= px < rx + rw and ry <= py < ry + rh:
                return False
        if self.collision_tile_layer_indices:
            col = int(px // self.tilewidth)
            row = int(py // self.tileheight)
            for li in self.collision_tile_layer_indices:
                layer = self.layers[li]
                if 0 <= row < layer.height and 0 <= col < layer.width:
                    if (layer.raw[row][col] & GID_MASK) != 0:
                        return False
        return True

    def is_box_walkable(self, px: float, py: float, w: float, h: float) -> bool:
        """Sample four corners + centre — cheap and good enough."""
        samples = (
            (px,           py),
            (px + w - 1.0, py),
            (px,           py + h - 1.0),
            (px + w - 1.0, py + h - 1.0),
            (px + w / 2,   py + h / 2),
        )
        return all(self.is_walkable(sx, sy) for sx, sy in samples)

    def safe_spawn(self, col: int, row: int,
                   hb_w: int = 24, hb_h: int = 24) -> tuple[int, int]:
        """Return a collision-free tile near (col, row), searching in expanding rings."""
        hb_off_x = (self.tilewidth - hb_w) // 2
        hb_off_y = self.tileheight - hb_h

        def _clear(c: int, r: int) -> bool:
            if c < 0 or r < 0 or c >= self.width or r >= self.height:
                return False
            bx = c * self.tilewidth + hb_off_x
            by = r * self.tileheight + hb_off_y
            return self.is_box_walkable(bx, by, hb_w, hb_h)

        if _clear(col, row):
            return col, row

        for radius in range(1, 21):
            for dc in range(-radius, radius + 1):
                for dr in range(-radius, radius + 1):
                    if abs(dc) != radius and abs(dr) != radius:
                        continue
                    if _clear(col + dc, row + dr):
                        log.warning(
                            "Spawn (%d, %d) is inside collision; relocated to (%d, %d)",
                            col, row, col + dc, row + dr,
                        )
                        return col + dc, row + dr

        log.error(
            "No collision-free spawn within 20 tiles of (%d, %d); using original",
            col, row,
        )
        return col, row

    def find_triggers(self, prev_x: float, prev_y: float,
                      new_x: float, new_y: float,
                      hitbox_w: float, hitbox_h: float) -> list[TmxObject]:
        """Return triggers entered on this step (newly intersecting, not previously)."""
        out: list[TmxObject] = []
        for trig in self.triggers:
            if _rects_intersect(new_x, new_y, hitbox_w, hitbox_h,
                                trig.x, trig.y, trig.w, trig.h):
                if not _rects_intersect(prev_x, prev_y, hitbox_w, hitbox_h,
                                        trig.x, trig.y, trig.w, trig.h):
                    out.append(trig)
        return out


def _rects_intersect(ax: float, ay: float, aw: float, ah: float,
                     bx: float, by: float, bw: float, bh: float) -> bool:
    return ax < bx + bw and ax + aw > bx and ay < by + bh and ay + ah > by


class MapCollection:
    """All TMX maps in a directory, indexed by lowercased filename stem."""

    def __init__(self, maps_dir: Path, chunk_size: int,
                 declared: Optional[dict] = None):
        self.maps_dir = maps_dir
        self.chunk_size = chunk_size
        self.declared: dict = declared or {}
        self.maps: dict[str, TmxMap] = {}

        if not maps_dir.is_dir():
            raise FileNotFoundError(f"maps_dir does not exist: {maps_dir}")

        for tmx_file in sorted(maps_dir.glob("*.tmx")):
            map_id = self._derive_id(tmx_file)
            try:
                mp = TmxMap(map_id, tmx_file, chunk_size=chunk_size)
            except Exception:
                log.exception("Failed to load map %s — skipping", tmx_file)
                continue
            decl = self.declared.get(map_id)
            if decl:
                exp_w = int(decl.get("width", mp.width))
                exp_h = int(decl.get("height", mp.height))
                if exp_w != mp.width or exp_h != mp.height:
                    log.warning(
                        "Map '%s': declared size %dx%d does not match TMX %dx%d",
                        map_id, exp_w, exp_h, mp.width, mp.height,
                    )
            self.maps[map_id] = mp

        if not self.maps:
            raise RuntimeError(f"No usable .tmx files found in {maps_dir}")
        log.info("MapCollection loaded %d maps from %s", len(self.maps), maps_dir)

    @staticmethod
    def _derive_id(path: Path) -> str:
        return path.stem.lower()

    def get(self, map_id: str) -> Optional[TmxMap]:
        return self.maps.get(map_id.lower())

    def list_ids(self) -> list[str]:
        return sorted(self.maps.keys())
