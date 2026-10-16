import base64
import copy
import hashlib
import io
import math
import json
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageDraw

from studio.assets import build_asset, decode_source, default_definition


def fixture():
    image = Image.new("RGBA", (64, 80))
    draw = ImageDraw.Draw(image)
    draw.rectangle((9, 5, 54, 75), fill=(140, 110, 210, 255))
    draw.rectangle((18, 25, 25, 29), fill=(10, 15, 20, 255))
    draw.rectangle((38, 25, 45, 29), fill=(10, 15, 20, 255))
    draw.rectangle((27, 43, 37, 46), fill=(180, 30, 40, 255))
    image.putpixel((1, 1), (240, 10, 60, 1))
    return image


def encoded(image, format="PNG"):
    data = io.BytesIO()
    image.save(data, format=format)
    return data.getvalue()


def definition(image):
    value = default_definition(image)
    value["anchors"] = {"head": [32, 24], "neck": [32, 55],
                        "eyeLeft": [18, 25, 26, 30], "eyeRight": [38, 25, 46, 30],
                        "mouth": [27, 43, 38, 47]}
    return value


def recomposed(result):
    asset = result["asset"]
    canvas = Image.new("RGBA", (asset["width"], asset["height"]))
    for layer in sorted(asset["layers"], key=lambda item: (item["z"], item["id"])):
        texture = Image.open(io.BytesIO(result["files"][layer["texture"]])).convert("RGBA")
        canvas.alpha_composite(texture, (layer["bounds"][0], layer["bounds"][1]))
    return canvas


def wide_sparse_attachments(image):
    value = default_definition(image)
    parts = []
    for index in range(60):
        mask = Image.new("L", image.size)
        mask.putpixel((index + 1, 1), 255)
        mask.putpixel((image.width - 2 - index, image.height - 2), 255)
        parts.append({"id": f"sparse_{index}", "name": f"Sparse {index}", "role": "accessory",
                      "z": index, "mask": base64.b64encode(encoded(mask)).decode(),
                      "pivot": [image.width / 2, image.height / 2], "strength": .2})
    value["parts"] = parts
    return value


class AssetTests(unittest.TestCase):
    def test_decode_normalizes_rgb_and_keeps_partial_alpha(self):
        image = decode_source(encoded(fixture()))
        self.assertEqual(image.mode, "RGBA")
        self.assertEqual(image.getpixel((1, 1)), (240, 10, 60, 1))

    def test_exif_orientation_is_applied_before_anchors(self):
        image = Image.new("RGB", (10, 30), "red")
        exif = Image.Exif()
        exif[274] = 6
        data = io.BytesIO()
        image.save(data, format="JPEG", exif=exif)
        self.assertEqual(decode_source(data.getvalue()).size, (30, 10))

    def test_large_image_is_bounded_without_cropping(self):
        image = decode_source(encoded(Image.new("RGB", (2000, 1000), "blue")))
        self.assertEqual(image.size, (1536, 768))

    def test_bad_image_and_input_budget_are_explicit_errors(self):
        for raw in (b"not image", b"x" * (20 * 1024 * 1024 + 1), None):
            with self.subTest(raw_type=type(raw)), self.assertRaises(ValueError):
                decode_source(raw)

    def test_default_anchors_are_valid_even_for_small_source(self):
        image = Image.new("RGBA", (8, 9), "red")
        self.assertEqual(build_asset(image, default_definition(image), "Small")["asset"]["width"], 8)

    def test_neutral_render_reproduces_source_including_alpha_one(self):
        image = fixture()
        result = build_asset(image, definition(image), "OC")
        self.assertTrue(np.array_equal(np.asarray(recomposed(result)), np.asarray(image)))

    def test_features_have_real_vertices_and_nonzero_blink_mouth_deltas(self):
        result = build_asset(fixture(), definition(fixture()), "OC")
        layers = {item["role"]: item for item in result["asset"]["layers"]}
        for role, parameter in (("eye_left", "blinkLeft"), ("eye_right", "blinkRight"), ("mouth", "mouth")):
            self.assertTrue(any(layers[role]["deltas"][parameter]))
            self.assertGreater(len(layers[role]["indices"]), 3)

    def test_twelve_wings_and_tiny_island_remain_separate_parts(self):
        image = fixture()
        value = definition(image)
        value["parts"] = [{"id": f"wing_{i}", "name": f"Wing {i}", "role": "accessory", "z": i + 1,
                           "polygon": [[10 + i * 3, 65], [12 + i * 3, 65], [12 + i * 3, 69], [10 + i * 3, 69]],
                           "pivot": [32, 55], "strength": .5} for i in range(12)]
        result = build_asset(image, value, "Wings")
        self.assertEqual(sum(item["id"].startswith("wing_") for item in result["asset"]["layers"]), 12)
        self.assertEqual(recomposed(result).getpixel((1, 1)), image.getpixel((1, 1)))

    def test_definition_and_source_are_not_modified(self):
        image = fixture()
        value = definition(image)
        before = copy.deepcopy(value)
        pixels = image.tobytes()
        build_asset(image, value, "OC")
        self.assertEqual(value, before)
        self.assertEqual(image.tobytes(), pixels)

    def test_invalid_definition_geometry_roles_and_ids_are_rejected(self):
        good = definition(fixture())
        variants = []
        for key, content in (("head", [math.nan, 2]), ("eyeLeft", [20, 5, 10, 7])):
            bad = copy.deepcopy(good)
            bad["anchors"][key] = content
            variants.append(bad)
        for ident, role in (("../bad", "hair"), ("base", "hair"), ("safe", "unknown")):
            bad = copy.deepcopy(good)
            bad["parts"] = [{"id": ident, "name": "Bad", "role": role, "z": 1,
                             "polygon": [[1, 1], [4, 1], [4, 4]], "pivot": [3, 3], "strength": .5}]
            variants.append(bad)
        for bad in variants:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                build_asset(fixture(), bad, "OC")

    def test_mask_dimension_and_binary_subject_are_validated(self):
        good = definition(fixture())
        bad = copy.deepcopy(good)
        bad["subjectMask"] = base64.b64encode(bytes([2]) * (64 * 80)).decode()
        with self.assertRaises(ValueError):
            build_asset(fixture(), bad, "OC")
        good["parts"] = [{"id": "hair_1", "name": "Hair", "role": "hair", "z": 1,
                           "mask": base64.b64encode(encoded(Image.new("L", (3, 3), 255))).decode(),
                           "pivot": [32, 20], "strength": .5}]
        with self.assertRaises(ValueError):
            build_asset(fixture(), good, "OC")

    def test_hashes_bind_texture_and_canonical_source_files(self):
        result = build_asset(fixture(), definition(fixture()), "OC")
        asset = result["asset"]
        self.assertEqual(asset["sourceSha"], hashlib.sha256(result["files"]["source.png"]).hexdigest())
        self.assertEqual(asset["format"], "rig2d-planar-1")
        self.assertFalse(asset["capabilities"]["trueSideViews"])
        self.assertTrue(asset["capabilities"]["reviewRequired"])
        for layer in asset["layers"]:
            self.assertEqual(layer["textureSha"], hashlib.sha256(result["files"][layer["texture"]]).hexdigest())

    def test_layer_vertex_and_triangle_budgets_are_bounded(self):
        result = build_asset(fixture(), definition(fixture()), "OC")
        for layer in result["asset"]["layers"]:
            self.assertLessEqual(len(layer["vertices"]) // 2, 1089)
            self.assertLessEqual(len(layer["indices"]) // 3, 2048)
            self.assertEqual(len(layer["uv"]), len(layer["vertices"]))

    def test_all_parameter_extremes_keep_nearby_head_neck_eye_mesh_unflipped(self):
        import itertools
        value = definition(fixture())
        value["anchors"]["head"] = [0, 26]
        value["anchors"]["neck"] = [63, 27]
        asset = build_asset(fixture(), value, "Close anchors")["asset"]
        for layer in asset["layers"]:
            vertices = np.asarray(layer["vertices"]).reshape(-1, 2)
            triangles = np.asarray(layer["indices"]).reshape(-1, 3)
            for weights in itertools.product(*[limits[:2] for limits in asset["parameters"].values()]):
                transformed = vertices.copy()
                for parameter, weight in zip(asset["parameters"], weights):
                    transformed += np.asarray(layer["deltas"][parameter]).reshape(-1, 2) * weight
                edge_a = transformed[triangles[:, 1]] - transformed[triangles[:, 0]]
                edge_b = transformed[triangles[:, 2]] - transformed[triangles[:, 0]]
                cross = edge_a[:, 0] * edge_b[:, 1] - edge_a[:, 1] * edge_b[:, 0]
                self.assertTrue((cross > 0).all(), layer["id"])

    def test_huge_geometry_number_is_a_typed_validation_error(self):
        value = definition(fixture())
        value["anchors"]["head"][0] = 10 ** 10000
        with self.assertRaises(ValueError):
            build_asset(fixture(), value, "Huge")

    def test_partial_alpha_in_eye_region_and_subject_mask_preserve_selected_pixels(self):
        image = fixture()
        image.putpixel((20, 27), (50, 60, 70, 100))
        value = definition(image)
        selected = np.ones((80, 64), dtype=np.uint8)
        selected[1, 1] = 0
        value["subjectMask"] = base64.b64encode(selected.tobytes()).decode()
        result = build_asset(image, value, "Partial eye")
        self.assertEqual(recomposed(result).getpixel((20, 27)), (50, 60, 70, 100))
        self.assertEqual(recomposed(result).getpixel((1, 1)), (0, 0, 0, 0))

    def test_extreme_wide_source_tilt_does_not_flip_body_triangles(self):
        image = Image.new("RGBA", (1536, 8), "red")
        value = default_definition(image)
        value["anchors"]["head"] = [1535, 2]
        value["anchors"]["neck"] = [1535, 4]
        asset = build_asset(image, value, "Wide")["asset"]
        base = next(layer for layer in asset["layers"] if layer["role"] == "base")
        vertices = np.asarray(base["vertices"]).reshape(-1, 2)
        triangles = np.asarray(base["indices"]).reshape(-1, 3)
        for weight in (-1, 1):
            transformed = vertices + weight * np.asarray(base["deltas"]["tilt"]).reshape(-1, 2)
            a = transformed[triangles[:, 1]] - transformed[triangles[:, 0]]
            b = transformed[triangles[:, 2]] - transformed[triangles[:, 0]]
            self.assertTrue((a[:, 0] * b[:, 1] - a[:, 1] * b[:, 0] > 0).all())

    def test_geometry_is_float32_canonical_once_and_survives_json_roundtrip(self):
        image = Image.new("RGBA", (113, 89), (140, 120, 100, 255))
        asset = build_asset(image, default_definition(image), "Native geometry")["asset"]
        restored = json.loads(json.dumps(asset))
        for layer in restored["layers"]:
            for key, values in [("vertices", layer["vertices"]), ("uv", layer["uv"]),
                                *layer["deltas"].items()]:
                with self.subTest(layer=layer["id"], parameter=key):
                    self.assertEqual(values, np.asarray(values, dtype=np.float32).tolist())
                    self.assertTrue(np.isfinite(values).all())
            self.assertTrue(all(type(index) is int for index in layer["indices"]))

    def test_opaque_linework_keeps_sword_and_disconnected_ribbon_pixels_without_thresholding(self):
        image = Image.new("RGBA", (128, 192), "white")
        draw = ImageDraw.Draw(image)
        draw.ellipse((54, 8, 79, 35), outline=(0, 0, 0, 255), width=1)
        draw.line([(64, 34), (64, 175)], fill=(20, 20, 20, 255), width=1)
        draw.line([(69, 40), (123, 23)], fill=(5, 5, 5, 255), width=1)
        draw.line([(4, 7), (8, 65), (2, 121), (28, 153)], fill=(90, 90, 90, 255), width=1)
        image.putpixel((1, 188), (70, 70, 70, 255))
        result = build_asset(image, default_definition(image), "Linework boundary")
        self.assertTrue(np.array_equal(np.asarray(recomposed(result)), np.asarray(image)))
        self.assertTrue(result["asset"]["capabilities"]["reviewRequired"])
        self.assertFalse(result["asset"]["capabilities"]["identityVerified"])

    def test_very_light_portrait_hair_at_frame_boundary_keeps_original_color(self):
        image = Image.new("RGBA", (128, 192), "white")
        draw = ImageDraw.Draw(image)
        draw.line([(0, 2), (18, 8), (7, 51), (0, 87)], fill=(252, 251, 250, 255), width=1)
        draw.ellipse((20, 40, 104, 126), outline=(249, 247, 246, 255), width=1)
        draw.line([(95, 125), (127, 136), (127, 191)], fill=(254, 253, 252, 255), width=1)
        result = build_asset(image, default_definition(image), "Light boundary")
        restored = np.asarray(recomposed(result))
        self.assertTrue(np.array_equal(restored, np.asarray(image)))
        self.assertEqual(tuple(restored[191, 127]), (254, 253, 252, 255))

    def test_detected_face_numpy_rectangle_generates_valid_builtin_anchor_numbers(self):
        image = fixture()
        with patch("studio.definition.cv2.CascadeClassifier") as constructor:
            constructor.return_value.empty.return_value = False
            constructor.return_value.detectMultiScale.return_value = np.asarray([[12, 8, 32, 36]], dtype=np.int32)
            value = default_definition(image)
        result = build_asset(image, value, "Detected suggestion")
        self.assertTrue(all(type(item) in (float, int) for key in ("head", "neck") for item in value["anchors"][key]))
        self.assertTrue(np.array_equal(np.asarray(recomposed(result)), np.asarray(image)))

    def test_eye_mouth_local_deformation_pins_outer_edges_and_moves_inner_vertices(self):
        asset = build_asset(fixture(), definition(fixture()), "Local features")["asset"]
        for layer in asset["layers"]:
            parameter = {"eye_left": "blinkLeft", "eye_right": "blinkRight", "mouth": "mouth"}.get(layer["role"])
            if not parameter:
                continue
            with self.subTest(role=layer["role"]):
                uv = np.asarray(layer["uv"]).reshape(-1, 2)
                boundary = ((uv == 0) | (uv == 1)).any(axis=1)
                delta = np.asarray(layer["deltas"][parameter]).reshape(-1, 2)
                self.assertTrue((delta[boundary] == 0).all())
                self.assertGreaterEqual(len(np.unique(uv[:, 0])), 7)
                self.assertGreaterEqual(len(np.unique(uv[:, 1])), 7)
                self.assertTrue((delta[~boundary] != 0).any())

    def test_full_blink_after_native_float32_rounding_keeps_positive_crop_triangles(self):
        asset = build_asset(fixture(), definition(fixture()), "Closed eyes")["asset"]
        for layer in asset["layers"]:
            parameter = {"eye_left": "blinkLeft", "eye_right": "blinkRight"}.get(layer["role"])
            if not parameter:
                continue
            vertices = np.asarray(layer["vertices"]).reshape(-1, 2)
            delta = np.asarray(layer["deltas"][parameter]).reshape(-1, 2)
            closed = np.asarray(vertices + delta, dtype=np.float32).astype(np.float64)
            x, y, width, height = layer["bounds"]
            self.assertTrue(((closed[:, 0] >= x) & (closed[:, 0] <= x + width)).all())
            self.assertTrue(((closed[:, 1] >= y) & (closed[:, 1] <= y + height)).all())
            indices = np.asarray(layer["indices"]).reshape(-1, 3)
            a = closed[indices[:, 1]] - closed[indices[:, 0]]
            b = closed[indices[:, 2]] - closed[indices[:, 0]]
            self.assertTrue((a[:, 0] * b[:, 1] - a[:, 1] * b[:, 0] > 0).all())

    def test_large_sparse_texture_spans_are_rejected_before_any_png_is_encoded(self):
        from studio.image_io import png_bytes
        image = Image.new("RGBA", (768, 768), (120, 80, 60, 255))
        value = wide_sparse_attachments(image)
        with patch("studio.assets.png_bytes", wraps=png_bytes) as encoder:
            with self.assertRaisesRegex(ValueError, "TEXTURE.*BUDGET"):
                build_asset(image, value, "Sparse texture budget")
            encoder.assert_not_called()

    def test_full_blink_compresses_inner_iris_band_to_tenth_distance(self):
        from studio.mesh import local_feature_delta
        bounds = [20, 30, 120, 60]
        center_x, center_y = 80, 60
        for role in ("eye_left", "eye_right"):
            for x_offset in (-36, 0, 36):
                for y_offset in (-12, 12):
                    with self.subTest(role=role, x_offset=x_offset, y_offset=y_offset):
                        delta = local_feature_delta(role, center_x + x_offset, center_y + y_offset, bounds)
                        mapped = float(np.float32(center_y + y_offset + delta[1])) - center_y
                        self.assertLessEqual(abs(mapped), abs(y_offset) * .100001)


if __name__ == "__main__":
    unittest.main()
