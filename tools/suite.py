#!/usr/bin/env python3
"""Summarize JSON-Schema-Test-Suite failures by file (usage: tools/suite.py [filter] [-v])."""
import sys, collections, xml.etree.ElementTree as ET
root = ET.parse('openapi-fn-core/build/test-results/test/TEST-dev.openapifn.schema.JsonSchemaTestSuiteTest.xml').getroot()
flt = next((a for a in sys.argv[1:] if not a.startswith('-')), '')
verbose = '-v' in sys.argv
counter = collections.Counter()
for case in root.iter('testcase'):
    f = case.findall('failure') + case.findall('error')
    if not f:
        continue
    msg = (f[0].get('message') or '').replace('org.opentest4j.AssertionFailedError: [', '')
    key = msg.split(':')[0]
    parts = key.split('/')
    counter['/'.join(parts[:2]) if not parts[1].startswith('optional') else '/'.join(parts[:4])] += 1
    if flt and flt in key and verbose:
        print(msg.split('\n')[0][:400])
for k, v in sorted(counter.items()):
    if flt in k:
        print(v, k)
