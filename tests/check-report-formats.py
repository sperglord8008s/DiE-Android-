# SPDX-License-Identifier: GPL-3.0-only
"""Independent standard-library reconstruction of the complete exported report."""
import csv
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
csv.field_size_limit(32 * 1024 * 1024)


def same(left, right):
    assert type(left) is type(right), (type(left), type(right))
    if isinstance(left, dict):
        assert left.keys() == right.keys()
        for key in left:
            same(left[key], right[key])
    elif isinstance(left, list):
        assert len(left) == len(right)
        for a, b in zip(left, right):
            same(a, b)
    else:
        assert left == right


def xml_value(element):
    if element.tag == "null":
        return None
    if element.tag in ("string", "number", "boolean"):
        return json.loads(element.text)
    if element.tag == "object":
        result = {}
        for member in element:
            key, value = list(member)
            assert key.tag == "key" and key.attrib["encoding"] == "json-string"
            name = json.loads(key.text)
            assert name not in result
            result[name] = xml_value(value)
        return result
    if element.tag == "array":
        result = []
        for index, item in enumerate(element):
            assert item.tag == "item" and int(item.attrib["index"]) == index
            result.append(xml_value(list(item)[0]))
        return result
    raise AssertionError(element.tag)


for kind in ("success", "failed", "empty", "null"):
    expected = json.loads((root / (kind + ".json")).read_text())
    document = ET.parse(root / (kind + ".xml")).getroot()
    assert document.tag == "die-scan-report"
    assert document.attrib == {"schema": "org.diemobile.scan-xml", "version": "1"}
    same(xml_value(list(document)[0]), expected)
    for extension, delimiter in (("csv", ","), ("tsv", "\t")):
        data = (root / (kind + "." + extension)).read_bytes()
        assert all(value < 128 for value in data), "Tabular JSON cells must retain ASCII escapes"
        with (root / (kind + "." + extension)).open(newline="") as stream:
            rows = list(csv.DictReader(stream, delimiter=delimiter, strict=True))
        assert rows[0]["record_type"] == "report-metadata"
        metadata = json.loads(rows[0]["value_json"])
        assert metadata["schema"] == "org.diemobile.scan-table" and metadata["schemaVersion"] == 1
        reconstructed = metadata["reportMetadata"]
        native = []
        for index, row in enumerate(rows[1:]):
            assert row["record_type"] == "native-row" and int(row["index"]) == index
            raw = json.loads(row["value_json"])
            for key in ("category", "name", "signature", "text"):
                assert json.loads(row[key + "_json"]) == raw.get(key)
            assert int(row["parent"]) == raw["parent"]
            native.append(raw)
        assert len(native) == metadata["nativeRowCount"]
        if metadata["nativeRowsSeparated"]:
            reconstructed["scan"]["rows"] = native
        else:
            assert not native
        same(reconstructed, expected)
        if kind == "success":
            assert len(native) == 125 and native[42]["name"].encode("utf-16-le", "surrogatepass") == expected["source"]["name"].encode("utf-16-le", "surrogatepass")

print("Independent Python XML/CSV/TSV parsers reconstructed every report field, 125 native rows, every UTF-16 unit, duplicate delimiters/quotes/line breaks, typed unknown values and failed/empty/missing/null scan scopes.")
