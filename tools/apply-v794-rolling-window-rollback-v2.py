from pathlib import Path

source = Path('tools/apply-v794-rolling-window-rollback.py').read_text(encoding='utf-8')
source = source.replace(
    '// stopScroll() can synchronously dispatch IDLE, which may schedule a burst reset.\n                // Cancel that newly scheduled reset too before this real gesture continues.',
    '// stopScroll() may synchronously emit IDLE and schedule a burst reset.\n                // Cancel again so the new real gesture keeps the same short-burst origin.'
)
if 'stopScroll() can synchronously dispatch IDLE' in source:
    raise SystemExit('failed to adapt v794 driver to final v793 touch comments')
exec(compile(source, 'tools/apply-v794-rolling-window-rollback.py', 'exec'))
