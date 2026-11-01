"""Prints the outcome of the last unit test run, one line per failure."""
import glob
import xml.etree.ElementTree as ET

total = failed = 0
for path in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    root = ET.parse(path).getroot()
    total += int(root.get("tests"))
    failed += int(root.get("failures")) + int(root.get("errors"))
    for case in root.findall("testcase"):
        for problem in case.findall("failure") + case.findall("error"):
            print(case.get("classname").split(".")[-1], case.get("name"), "-", (problem.get("message") or "")[:300])
print(f"tests {total} failed {failed}")
