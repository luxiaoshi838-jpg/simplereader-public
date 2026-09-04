from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
release_workflow_path = ROOT / '.github/workflows/android-release-v2.yml'
original_release_workflow = release_workflow_path.read_text(encoding='utf-8')

# Apply the hardened V758 source patch. The helper also drafts the v758 release workflow, but a
# GitHub Actions token is not allowed to push workflow-file changes in this repository. Preserve
# the validated source/build/log changes here and restore the workflow byte-for-byte; the workflow
# is updated separately through the authorized GitHub file API after the source commit lands.
runpy.run_path(str(ROOT / 'tools/v758-scroll-smoothness-fix2.py'), run_name='__main__')
release_workflow_path.write_text(original_release_workflow, encoding='utf-8')

# Deliberately preserve V757's validated 32-page bounded render-cache contract. Cache expansion
# was optional and is not required for the jank fix.
adapter_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt'
adapter = adapter_path.read_text(encoding='utf-8')
adapter = adapter.replace(
    'private val rendered = LruCache<Int, CharSequence>(64)',
    'private val rendered = LruCache<Int, CharSequence>(32)',
)
if 'LruCache<Int, CharSequence>(32)' not in adapter:
    raise SystemExit('v758: failed to restore V757 bounded render-cache contract')
adapter_path.write_text(adapter, encoding='utf-8')

log_path = ROOT / 'TXT_READER_RENDERING_MAINTENANCE_LOG.md'
log = log_path.read_text(encoding='utf-8')
log = log.replace(
    '- 页渲染 LRU 从 32 扩至 64，仍为有界缓存。',
    '- 页渲染 LRU 保持 V757 已验证的 32 页有界缓存；本次不通过扩大缓存换取流畅度，避免增加内存压力并保持旧稳定性 Gate 13。',
)
log = log.replace(
    '5. 垂直页渲染 LRU 仅 32 页，快速来回滚动更容易重新绑定文本。\n',
    '5. 审计曾考虑扩大 32 页 LRU，但该容量属于 V757 已验证的稳定性契约；V758 最终不改容量，把优化集中在每帧主线程工作。\n',
)
log_path.write_text(log, encoding='utf-8')

print('v758 smoothness source patch applied; V757 cache contract preserved; release workflow deferred')
