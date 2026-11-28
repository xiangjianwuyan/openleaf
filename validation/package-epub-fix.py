from pathlib import Path
import difflib
import json
import zipfile

repo = Path(__file__).resolve().parents[1]
baseline = repo.parent / 'openleaf-qa-c0b6e3'
delivery = repo.parent / 'deliverables'
paths = [
    'app/build.gradle',
    'app/src/main/java/org/openleaf/reader/EpubActivity.java',
    'app/src/main/java/org/openleaf/reader/EpubPageLayout.java',
    'app/src/main/java/org/openleaf/reader/core/BookParser.java',
    'app/src/debug/AndroidManifest.xml',
    'app/src/debug/java/org/openleaf/reader/EpubQaBootstrapActivity.java',
    'app/src/debug/java/org/openleaf/reader/EpubReaderInstrumentation.java',
    'core-tests/BookParserTest.java',
    'core-tests/EpubChapterIndexerTest.java',
    'core-tests/EpubCompatibilityTest.java',
    'validation/epub-qa.init.gradle',
    'validation/run-epub-coretests.ps1',
    'validation/package-epub-fix.py',
]
patch = []
summary = []
with zipfile.ZipFile(delivery / 'openleaf-0.63.1-epub-source-changes.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in paths:
        current_path = repo / name
        previous_path = baseline / name
        current = current_path.read_text(encoding='utf-8-sig').replace('\r\n', '\n')
        previous = previous_path.read_text(encoding='utf-8-sig').replace('\r\n', '\n') if previous_path.exists() else ''
        if current == previous:
            continue
        before = previous.splitlines(keepends=True)
        after = current.splitlines(keepends=True)
        change = list(difflib.unified_diff(before, after,
                       fromfile='a/' + name if previous_path.exists() else '/dev/null',
                       tofile='b/' + name))
        patch.append('diff --git a/' + name + ' b/' + name + '\n')
        if not previous_path.exists():
            patch.append('new file mode 100644\n')
        patch.extend(change)
        summary.append({'path': name, 'added': sum(line.startswith('+') and not line.startswith('+++') for line in change),
                        'removed': sum(line.startswith('-') and not line.startswith('---') for line in change)})
        archive.write(current_path, name)
(delivery / 'openleaf-0.63.1-from-0.63.0.patch').write_text(''.join(patch), encoding='utf-8')
(delivery / 'epub-0.63.1-change-manifest.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=True, indent=2))
