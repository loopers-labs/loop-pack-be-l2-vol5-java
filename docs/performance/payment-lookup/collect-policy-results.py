"""전체 check의 JUnit 출력에서 통제 실험 원본과 검증 결과를 보존합니다."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--out', type=Path, default=ROOT / 'docs/performance/payment-lookup/runs/policies-20261023')
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)


def write(name, value):
    (args.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def command(*parts):
    return subprocess.check_output(parts, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


prefixes = {'PG_LOOKUP_POLICY ': [], 'PG_LOOKUP_POLICY_BUSINESS ': []}
counts = {}
for module in ('commerce-api', 'test-pg'):
    counts[module] = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    files = sorted((ROOT / f'apps/{module}/build/test-results/test').glob('TEST-*.xml'))
    if not files:
        raise SystemExit(f'Missing JUnit results: {module}')
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts[module]:
            counts[module][key] += int(suite.get(key, 0))
        for output in suite.findall('.//system-out'):
            for line in (output.text or '').splitlines():
                for prefix, records in prefixes.items():
                    if line.startswith(prefix):
                        records.append(json.loads(line[len(prefix):]))
    if counts[module]['failures'] or counts[module]['errors']:
        raise SystemExit(f'Failed checks: {module} {counts[module]}')
if len(prefixes['PG_LOOKUP_POLICY ']) != 9 or len(prefixes['PG_LOOKUP_POLICY_BUSINESS ']) != 1:
    raise SystemExit('Run the policy experiments and business E2E before collecting results')
write('http-experiments.json', prefixes['PG_LOOKUP_POLICY '])
write('business.json', prefixes['PG_LOOKUP_POLICY_BUSINESS '])
write('checks.json', counts)
paths = []
for pattern in ('apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/*.java',
                'apps/commerce-api/src/main/java/com/loopers/application/payment/PaymentLookupObservation.java',
                'apps/commerce-api/src/main/resources/application.yml',
                'apps/commerce-api/src/main/resources/pg-test/*.yml',
                'apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/*Policy*Test.java',
                'apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/Resilient*Test.java',
                'apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentPoliciesE2ETest.java',
                'apps/test-pg/src/main/java/com/loopers/testpg/**/*.java',
                'apps/commerce-api/build.gradle.kts', 'gradle.properties'):
    paths.extend(ROOT.glob(pattern))
write('environment.json', {
    'collectedAt': datetime.now(timezone.utc).isoformat(),
    'gitCommit': command('git', 'rev-parse', 'HEAD'),
    'workingTree': command('git', 'status', '--short'),
    'java': command('java', '-version'),
    'command': './gradlew :apps:commerce-api:check :apps:test-pg:check',
    'springBoot': '3.4.4', 'springFramework': '6.2.5', 'resilience4j': '2.4.0',
    'sourceSha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))},
})
for result in prefixes['PG_LOOKUP_POLICY ']:
    times = [c['elapsedMillis'] for c in result.get('calls', [])]
    print(json.dumps({k: v for k, v in result.items() if k != 'calls'} | {
        'elapsedMillis': times if len(times) < 20 else {'min': min(times), 'max': max(times)}}, ensure_ascii=False))
print(json.dumps({'checks': counts, 'business': prefixes['PG_LOOKUP_POLICY_BUSINESS ']}, ensure_ascii=False))
