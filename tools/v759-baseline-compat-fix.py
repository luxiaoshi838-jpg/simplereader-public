from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

main_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
main = main_path.read_text(encoding='utf-8')
old = '.setTitle("异常退出/闪退日志")'
new = '.setTitle("异常退出/闪退/崩溃日志")'
if old in main:
    main = main.replace(old, new, 1)
elif new not in main:
    raise SystemExit('v759 baseline compat: crash log dialog title not found')
main_path.write_text(main, encoding='utf-8')

gate_path = ROOT / 'tools/v759-crash-diagnostics-gates.sh'
gate = gate_path.read_text(encoding='utf-8')
gate = gate.replace("assert '异常退出/闪退日志' in main", "assert '异常退出/闪退/崩溃日志' in main")
gate_path.write_text(gate, encoding='utf-8')

patch_path = ROOT / 'tools/v759-crash-diagnostics-fix.py'
patch = patch_path.read_text(encoding='utf-8')
patch = patch.replace(
    "main = replace_once(main, '.setTitle(\"闪退/崩溃日志\")', '.setTitle(\"异常退出/闪退日志\")', 'crash dialog title')",
    "main = replace_once(main, '.setTitle(\"闪退/崩溃日志\")', '.setTitle(\"异常退出/闪退/崩溃日志\")', 'crash dialog title')"
)
patch_path.write_text(patch, encoding='utf-8')

log_path = ROOT / 'TXT_READER_RENDERING_MAINTENANCE_LOG.md'
log = log_path.read_text(encoding='utf-8')
note = '''\n### V759 全量旧单测基线核对\n- 未修改 V758 的 `testDebugUnitTest` 共 96 项，已有 10 项历史失败。\n- V759 首次全量测试为相同 10 项历史失败 + 1 项新增失败。\n- 唯一新增失败来自旧 `CrashLogAndCoverContractTest` 硬编码查找“闪退/崩溃日志”，而 V759 初稿把对话框标题改成“异常退出/闪退日志”；复制、清除与持久日志逻辑均未丢失。\n- V759 将标题改为“异常退出/闪退/崩溃日志”，兼容旧契约文字并保留系统异常退出含义。\n- 正式构建不得简单跳过全量测试：允许的失败只能是 V758 已确认存在的历史失败集合，任何 V759 新增失败都必须阻断发布。\n'''
if note.strip() not in log:
    log += note
    log_path.write_text(log, encoding='utf-8')

print('v759 baseline compatibility fix applied')
