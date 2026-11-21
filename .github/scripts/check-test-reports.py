import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


PHASES = ("surefire", "failsafe")
RESULT_COUNTS = ("skipped", "failures", "errors")
CASE_PROBLEMS = {"skipped", "failure", "error"}
JAVA_NON_CODE = re.compile(
    r'//[^\r\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""'
    r'|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
)
JAVA_PACKAGE = re.compile(r"\bpackage\s+([\w$.\s]+)\s*;")


def expected_classes(module):
    expected = {phase: set() for phase in PHASES}
    for path in (module / "src/test/java").rglob("*.java"):
        name = path.stem
        if name == "OutboxConcurrencyManualRunner" or name.endswith("Fixture"):
            continue
        if name.endswith(("IT", "IntegrationTest")):
            phase = "failsafe"
        elif name.startswith("Test") or name.endswith(("Test", "Tests", "TestCase")):
            phase = "surefire"
        else:
            continue

        source = JAVA_NON_CODE.sub(" ", path.read_text(encoding="utf-8"))
        if re.search(
            rf"\babstract\s+(?:(?:public|protected|private|static|strictfp|sealed|non-sealed)\s+)*"
            rf"class\s+{re.escape(name)}\b",
            source,
        ):
            continue
        package = JAVA_PACKAGE.search(source)
        qualified_name = f"{''.join(package[1].split())}.{name}" if package else name
        if qualified_name in expected[phase]:
            raise ValueError(f"{path}: duplicate test class {qualified_name}")
        expected[phase].add(qualified_name)
    return expected


def read_xml(path, problems):
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        problems.append(f"{path}: {error}")
        return None


def check_reports(directory, expected, problems):
    found = set()
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = read_xml(path, problems)
        if suite is None:
            continue
        name = suite.get("name")
        if suite.tag != "testsuite" or not name:
            problems.append(f"{path}: expected a named <testsuite>")
            continue
        if name in found:
            problems.append(f"{path}: duplicate report for {name}")
        found.add(name)
        if path.name != f"TEST-{name}.xml":
            problems.append(f"{path}: filename does not match suite {name}")
        for count in RESULT_COUNTS:
            if suite.get(count) != "0":
                problems.append(f"{path}: expected {count}=0, found {suite.get(count)!r}")
        # Surefire can write tests="0" for a suite with executed @Nested cases.
        cases = list(suite.iter("testcase"))
        if not cases:
            problems.append(f"{path}: no test cases")
        for case in cases:
            failed_tags = {node.tag for node in case.iter()} & CASE_PROBLEMS
            if failed_tags:
                problems.append(f"{path}: {case.get('name')!r} has {', '.join(sorted(failed_tags))}")

    for name in sorted(expected - found):
        problems.append(f"{directory}: missing report for {name}")
    for name in sorted(found - expected):
        problems.append(f"{directory}: unexpected report for {name}")
    return found


def check_failsafe_summary(path, problems):
    summary = read_xml(path, problems)
    if summary is None:
        return
    # Failsafe 3.5.4 formats its successful Java null result as the string "null".
    if summary.tag != "failsafe-summary" or summary.get("result") != "null" or summary.get("timeout") != "false":
        problems.append(f"{path}: expected a successful Failsafe summary without timeout")
    for count in RESULT_COUNTS:
        if summary.findtext(count, "").strip() != "0":
            problems.append(f"{path}: expected <{count}>0</{count}>")
    completed = summary.findtext("completed", "").strip()
    if not completed.isdecimal() or int(completed) < 1:
        problems.append(f"{path}: expected at least one completed test")
    if summary.findtext("failureMessage", "").strip():
        problems.append(f"{path}: unexpected Failsafe failure message")


def main():
    problems = []
    totals = dict.fromkeys(PHASES, 0)
    for module in sorted(Path("apps").iterdir()):
        if not module.is_dir():
            continue
        expected = expected_classes(module)
        target = module / "target"
        found = {}
        for phase in PHASES:
            found[phase] = check_reports(target / f"{phase}-reports", expected[phase], problems)
            totals[phase] += len(expected[phase])
        for name in sorted(found["surefire"] & found["failsafe"]):
            problems.append(f"{module}: {name} appeared in both phases")
        if expected["failsafe"]:
            check_failsafe_summary(target / "failsafe-reports/failsafe-summary.xml", problems)

    for phase, total in totals.items():
        if not total:
            problems.append(f"No Java test classes discovered for {phase}")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"Validated {totals['surefire']} Surefire and {totals['failsafe']} Failsafe reports.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
