"""Fail CI on new FindSecBugs findings or incomplete analysis; retain the known backlog.

Run after: ./mvnw -Psecurity compile spotbugs:spotbugs
Then: python scripts/check_security_report.py
Use spotbugs:check instead to enforce zero findings, including the existing backlog.
"""

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def fingerprint(finding):
    keys = ("type", "class", "method", "signature", "instance_hash")
    values = tuple(finding.get(key, "") for key in keys)
    if not all(values):
        raise ValueError("A finding is missing its complete fingerprint")
    return values


def check(report_path, baseline_path):
    baseline = json.loads(Path(baseline_path).read_text(encoding="utf-8"))
    if baseline.get("version") != 1 or not isinstance(baseline.get("findings"), list):
        raise ValueError("Unsupported security baseline")
    allowed = Counter()
    for finding in baseline["findings"]:
        if not finding.get("reason", "").strip():
            raise ValueError("Every baseline finding needs a reason")
        allowed[fingerprint(finding)] += 1
    if any(count != 1 for count in allowed.values()):
        raise ValueError("Duplicate baseline fingerprints are not allowed")

    root = ET.parse(report_path).getroot()
    if root.tag != "BugCollection":
        raise ValueError("Not a SpotBugs report")
    errors = root.find("Errors")
    summary = root.find("FindBugsSummary")
    if errors is None or any(int(errors.get(key, "-1")) != 0 for key in ("errors", "missingClasses")):
        raise ValueError("Security analysis failed or has missing classes")
    if summary is None or int(summary.get("total_classes", "0")) <= 0:
        raise ValueError("Security analysis did not inspect any classes")

    known = 0
    unexpected = []
    for bug in root.findall("BugInstance"):
        if bug.get("category") != "SECURITY":
            continue
        method = next((m for m in bug.findall("Method") if m.get("primary") == "true"), None)
        owner = bug.find("Class")
        finding = {
            "type": bug.get("type", ""),
            "class": method.get("classname", "") if method is not None else owner.get("classname", "") if owner is not None else "",
            "method": method.get("name", "") if method is not None else "<class>",
            "signature": method.get("signature", "") if method is not None else "<class>",
            "instance_hash": bug.get("instanceHash", ""),
        }
        key = fingerprint(finding)
        if allowed[key]:
            allowed[key] -= 1
            known += 1
        else:
            unexpected.append(finding)

    print(f"Security findings: {known} recorded backlog, {len(unexpected)} new")
    for finding in unexpected:
        # Only code identifiers, never report messages that could contain sensitive values.
        print(f"NEW: {finding['type']} in {finding['class']}#{finding['method']}")
    remaining = sum(allowed.values())
    if remaining:
        print(f"{remaining} baseline entries no longer match; review and remove resolved entries.")
    return 1 if unexpected else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="target/spotbugsXml.xml")
    parser.add_argument("--baseline", default="config/security/spotbugs-baseline.json")
    args = parser.parse_args()
    try:
        return check(args.report, args.baseline)
    except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as error:
        print(f"Security gate could not validate the analysis: {type(error).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
