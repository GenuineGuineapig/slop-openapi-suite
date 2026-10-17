#!/usr/bin/env python3
"""Print uncovered lines/branches from JaCoCo XML reports (usage: tools/coverage.py [module...])."""
import glob, sys, xml.etree.ElementTree as ET

modules = sys.argv[1:] or ['*']
for module in modules:
    for path in glob.glob(f'{module}/target/site/jacoco/jacoco.xml'):
        root = ET.parse(path).getroot()
        totals = {c.get('type'): (int(c.get('missed')), int(c.get('covered'))) for c in root.findall('counter')}
        print(path.split('/')[0], {k: f"{v[1]}/{v[0]+v[1]}" for k, v in totals.items()})
        for pkg in root.findall('package'):
            for src in pkg.findall('sourcefile'):
                problems = []
                for line in src.findall('line'):
                    mi, mb = int(line.get('mi')), int(line.get('mb'))
                    if mi or mb:
                        problems.append(f"{line.get('nr')}{'' if not mb else f'(b{mb})'}{'' if not mi else ''}")
                if problems:
                    print(f"  {pkg.get('name')}/{src.get('name')}: {' '.join(problems)}")
