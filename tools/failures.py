#!/usr/bin/env python3
"""Print failed tests from Gradle JUnit XML reports (usage: tools/failures.py [module...])."""
import glob, sys, xml.etree.ElementTree as ET

modules = sys.argv[1:] or ['*']
for module in modules:
    for path in glob.glob(f'{module}/build/test-results/test/*.xml') + glob.glob(f'samples/{module}/build/test-results/test/*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                text = (failure.get('message') or '') + '\n' + (failure.text or '')
                lines = [l for l in text.splitlines() if l.strip()][:14]
                print(f"FAIL {case.get('classname')}.{case.get('name')}")
                print('   ' + '\n   '.join(lines))
